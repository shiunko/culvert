(ns culvert.web-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [culvert.auth :as auth]
            [culvert.config :as config]
            [culvert.docker :as docker]
            [culvert.forward :as forward]
            [culvert.i18n :as i18n]
            [culvert.web.core :as web]
            [culvert.web.handlers :as handlers]
            [culvert.web.views :as views]))

;; ============================================================
;; CSRF token 测试
;; ============================================================

(defn- basic-authorization [username password]
  (str "Basic "
       (.encodeToString (java.util.Base64/getEncoder)
                        (.getBytes (str username ":" password) "UTF-8"))))

(deftest test-csrf-token-lifecycle
  (reset! web/csrf-store {})
  (let [alice {:session-id "session-a" :username "alice" :action [:post "/save"]}
        bob {:session-id "session-b" :username "bob" :action [:post "/save"]}]
    (testing "CSRF generation requires authenticated context"
      (is (thrown? Exception (web/csrf-generate))))

    (testing "CSRF token is bound to session, user and action"
      (let [token (web/csrf-generate alice)]
        (is (string? token))
        (is (= 64 (count token)))
        (is (not (web/csrf-validate token bob true)))
        (is (not (web/csrf-validate token (assoc alice :action [:post "/other"]) true)))
        (is (web/csrf-validate token alice true))
        (is (not (web/csrf-validate token alice true)))))

    (testing "Compatibility validation may be non-consuming but never cross-user"
      (let [token (web/csrf-generate (dissoc alice :action))]
        (is (web/csrf-validate token alice))
        (is (web/csrf-validate token alice))
        (is (not (web/csrf-validate token bob)))))

    (testing "CSRF validate rejects nil and random strings"
      (is (not (web/csrf-validate nil alice true)))
      (is (not (web/csrf-validate "invalid-token" alice true)))
      (is (not (web/csrf-validate "" alice true))))))

(deftest htmx-mutation-carries-csrf-token
  (doseq [[authorization authenticated-user]
          [[(basic-authorization "alice" "secret")
            {:username "alice" :session-id "basic:alice:1" :role "user" :quotas {}}]
           ["Bearer session-token"
            {:username "alice" :session-id "bearer-session" :role "user" :quotas {}}]]]
    (reset! web/csrf-store {})
    (let [removed (atom nil)]
      (with-redefs [auth/authenticate (fn [_ _] authenticated-user)
                    auth/validate-token (fn [_] authenticated-user)
                    forward/get-entries (fn [_ _]
                                          [{:port "10001"
                                            :protocol "tcp"
                                            :method "socat"
                                            :ip "127.0.0.1"
                                            :toPort 10002
                                            :toPortDisplay "10002"
                                            :enabled true
                                            :alive true
                                            :remark "测试"}])
                    forward/remove-forward! (fn [& args] (reset! removed args))
                    views/build-oob-response (fn [& _] {:status 200 :body "ok"})]
        (let [panel-response (handlers/route-dispatcher
                              {:request-method :get
                               :uri "/panel/forwards"
                               :headers {"authorization" authorization}})
              csrf-token (first (keys @web/csrf-store))]
          (testing "动态片段把当前会话的 CSRF token 写入 mutation 参数"
            (is (= 200 (:status panel-response)))
            (is (string? csrf-token))
            (is (str/includes? (:body panel-response) "_csrf"))
            (is (str/includes? (:body panel-response) csrf-token)))

          (testing "相同认证会话可使用片段中的 token 删除资源"
            (is (= 200
                   (:status
                    (handlers/route-dispatcher
                     {:request-method :post
                      :uri "/remove"
                      :headers {"authorization" authorization}
                      :body (str "port=10001&protocol=tcp&method=socat&_csrf=" csrf-token)}))))
            (is (= ["10001" "tcp" "socat" "alice" false] @removed)))

          (testing "缺少 token 的删除请求仍然被拒绝"
            (is (= 403
                   (:status
                    (handlers/route-dispatcher
                     {:request-method :post
                      :uri "/remove"
                      :headers {"authorization" authorization}
                      :body "port=10001&protocol=tcp&method=socat"}))))))))))

(deftest mutation-error-uses-safe-message-and-non-200-status
  (reset! web/csrf-store {})
  (let [authorization (basic-authorization "alice" "secret")
        authenticated-user {:username "alice" :session-id "basic:alice:1" :role "user" :quotas {}}]
    (with-redefs [auth/authenticate (fn [_ _] authenticated-user)
                  forward/get-entries (fn [_ _] [])
                  forward/remove-forward! (fn [& _]
                                            (throw (Exception. "端口未被转发")))]
      (let [panel-response (handlers/route-dispatcher
                            {:request-method :get
                             :uri "/panel/forwards"
                             :headers {"authorization" authorization}})
            csrf-token (first (keys @web/csrf-store))
            error-response (handlers/route-dispatcher
                            {:request-method :post
                             :uri "/remove"
                             :headers {"authorization" authorization}
                             :body (str "port=10001&protocol=tcp&method=socat&_csrf=" csrf-token)})
            trigger (json/parse-string (get-in error-response [:headers "HX-Trigger"]) true)]
        (is (= 200 (:status panel-response)))

        (testing "领域层安全提示保持原样透传，但 HTTP 状态码不再固定为 200"
          (is (= 400 (:status error-response)))
          (is (= "端口未被转发" (get-in trigger [:show-toast :message])))
          (is (= "error" (get-in trigger [:show-toast :type]))))))))

(deftest unexpected-error-falls-back-to-generic-message
  (testing "空白消息时回退到通用提示，不向客户端暴露 nil/空字符串"
    (let [{:keys [message status]} (web/error-info (Exception. ""))]
      (is (= 400 status))
      (is (= (:operation-failed (:toast i18n/strings)) message))))
  (testing "存在消息时直接透传，无论异常类型"
    (is (= "自定义错误" (:message (web/error-info (ex-info "自定义错误" {:type ::demo})))))
    (is (= "普通异常" (:message (web/error-info (Exception. "普通异常")))))))

(deftest credential-endpoint-requires-post-and-csrf
  (testing "凭据读取不再从带查询参数的状态 GET 返回"
    (with-redefs [auth/authenticate (fn [_ _] {:username "alice"
                                               :session-id "basic:alice:1"
                                               :role "user"
                                               :quotas {}})
                  docker/get-container (fn [_ _ _] {:id "container-1"})
                  docker/refresh-status! (fn [_])
                  docker/get-shell-pass (fn [& _] (throw (ex-info "不应读取密码" {})))]
      (let [response (handlers/route-dispatcher
                      {:request-method :get
                       :uri "/docker/status/container-1"
                       :query-string "pass=1"
                       :headers {"authorization" (basic-authorization "alice" "secret")}})]
        (is (= 200 (:status response)))
        (is (not (str/includes? (:body response) "shellPass"))))))

  (testing "凭据 POST 没有 CSRF 时被拒绝"
    (with-redefs [auth/authenticate (fn [_ _] {:username "alice"
                                               :session-id "basic:alice:1"
                                               :role "user"
                                               :quotas {}})]
      (is (= 403
             (:status
              (handlers/route-dispatcher
               {:request-method :post
                :uri "/docker/credentials"
                :headers {"authorization" (basic-authorization "alice" "secret")}
                :body "id=container-1"})))))))

;; ============================================================
;; WebSocket 短期票据测试
;; ============================================================

(deftest ws-ticket-issue-and-consume
  (reset! web/csrf-store {})
  (reset! web/ws-ticket-store {})
  (with-redefs [auth/authenticate (fn [_ _] {:username "alice"
                                             :session-id "basic:alice:1"
                                             :role "user"
                                             :quotas {}})]
    (testing "没有 CSRF 时拒绝发票"
      (is (= 403
             (:status
              (handlers/route-dispatcher
               {:request-method :post
                :uri "/ws/ticket"
                :headers {"authorization" (basic-authorization "alice" "secret")}
                :body ""})))))

    (testing "携带有效 CSRF 时发票，且不写入页面的任何长期 token"
      (let [panel-response (handlers/route-dispatcher
                            {:request-method :get
                             :uri "/"
                             :headers {"authorization" (basic-authorization "alice" "secret")}})
            csrf-token (first (keys @web/csrf-store))
            ticket-response (handlers/route-dispatcher
                             {:request-method :post
                              :uri "/ws/ticket"
                              :headers {"authorization" (basic-authorization "alice" "secret")}
                              :body (str "_csrf=" csrf-token)})
            ticket (get (json/parse-string (:body ticket-response) true) :ticket)]
        (is (= 200 (:status panel-response)))
        (is (not (str/includes? (:body panel-response) "ws-token")))
        (is (= 200 (:status ticket-response)))
        (is (string? ticket))
        (is (= 64 (count ticket)))

        (testing "票据一次性消费：首次成功，二次失败"
          (is (= {:username "alice" :session-id "basic:alice:1"}
                 (select-keys (web/ws-ticket-consume ticket) [:username :session-id])))
          (is (nil? (web/ws-ticket-consume ticket))))))

    (testing "不存在、空白的票据均被拒绝"
      (is (nil? (web/ws-ticket-consume "no-such-ticket")))
      (is (nil? (web/ws-ticket-consume nil)))
      (is (nil? (web/ws-ticket-consume ""))))))

;; ============================================================
;; 限流测试
;; ============================================================

(deftest test-rate-limiting
  (testing "Requests under limit are allowed"
    ;; Reset state for clean test
    (reset! web/rate-limit-store {})
    (let [ip "127.0.0.1"]
      (dotimes [_ 50]
        (is (web/rate-limit-allowed? ip)))))

  (testing "Requests over limit are blocked"
    (reset! web/rate-limit-store {})
    (let [ip "192.168.1.100"]
      ;; Exhaust the limit
      (dotimes [_ web/rate-limit-max-requests]
        (web/rate-limit-allowed? ip))
      ;; Next request should be blocked
      (is (not (web/rate-limit-allowed? ip)))))

  (testing "Different IPs are tracked independently"
    (reset! web/rate-limit-store {})
    (let [ip-a "10.0.0.1"
          ip-b "10.0.0.2"]
      (dotimes [_ web/rate-limit-max-requests]
        (web/rate-limit-allowed? ip-a))
      (is (not (web/rate-limit-allowed? ip-a)))
      (is (web/rate-limit-allowed? ip-b))))

  (testing "Concurrent requests cannot exceed the limit"
    (reset! web/rate-limit-store {})
    (let [ip "10.0.0.3"
          attempts (+ web/rate-limit-max-requests 40)
          results (doall (map deref
                              (repeatedly attempts
                                          #(future (web/rate-limit-allowed? ip)))))]
      (is (= web/rate-limit-max-requests (count (filter true? results))))
      (is (= web/rate-limit-max-requests
             (count (get-in @web/rate-limit-store [ip :timestamps])))))))

;; ============================================================
;; Security Headers Tests
;; ============================================================

(deftest test-security-headers-middleware
  (testing "wrap-security-headers adds all expected headers"
    (let [handler (web/wrap-security-headers (fn [_req] {:status 200 :headers {} :body "ok"}))
          response (handler {})]
      (is (= (:status response) 200))
      (is (= (get-in response [:headers "X-Content-Type-Options"]) "nosniff"))
      (is (= (get-in response [:headers "X-Frame-Options"]) "DENY"))
      (is (= (get-in response [:headers "X-XSS-Protection"]) "1; mode=block"))
      (is (= (get-in response [:headers "Referrer-Policy"]) "strict-origin-when-cross-origin"))
      (is (some? (get-in response [:headers "Content-Security-Policy"])))
      (is (= (get-in response [:headers "Cache-Control"]) "no-store"))))

  (testing "wrap-security-headers preserves existing response headers"
    (let [handler (web/wrap-security-headers
                   (fn [_req] {:status 200
                               :headers {"Content-Type" "text/html"}
                               :body "ok"}))
          response (handler {})]
      (is (= (get-in response [:headers "Content-Type"]) "text/html"))
      (is (some? (get-in response [:headers "X-Content-Type-Options"])))))

  (testing "CSP header contains required directives"
    (let [handler (web/wrap-security-headers (fn [_] {:status 200 :headers {} :body "ok"}))
          response (handler {})
          csp (get-in response [:headers "Content-Security-Policy"])]
      (is (str/includes? csp "default-src 'self'"))
      (is (str/includes? csp "frame-ancestors 'none'"))
      (is (not (str/includes? csp "'unsafe-inline'")))
      (is (str/includes? csp "form-action 'self'")))))

;; ============================================================
;; Rate Limit Middleware Integration Test
;; ============================================================

(deftest test-rate-limit-middleware
  (testing "wrap-rate-limit passes through when under limit"
    (reset! web/rate-limit-store {})
    (let [handler (web/wrap-rate-limit (fn [_req] {:status 200 :body "ok"}))
          response (handler {:remote-addr "172.16.0.1"})]
      (is (= (:status response) 200))))

  (testing "Forwarding headers are ignored unless the direct peer is trusted"
    (reset! web/rate-limit-store {})
    (let [handler (web/wrap-rate-limit (fn [_req] {:status 200 :body "ok"}))
          req {:remote-addr "203.0.113.10"
               :headers {"x-forwarded-for" "198.51.100.1"}}]
      (with-redefs [config/config (dissoc config/config :trusted-proxies)]
        (handler req)
        (is (contains? @web/rate-limit-store "203.0.113.10"))
        (is (not (contains? @web/rate-limit-store "198.51.100.1"))))
      (reset! web/rate-limit-store {})
      (with-redefs [config/config (assoc config/config :trusted-proxies ["203.0.113.10"])]
        (handler req)
        (is (contains? @web/rate-limit-store "198.51.100.1")))))

  (testing "wrap-rate-limit returns 429 when blocked"
    (reset! web/rate-limit-store {})
    (let [handler (web/wrap-rate-limit (fn [_req] {:status 200 :body "ok"}))
          ip "172.16.0.2"]
      ;; Exhaust limit
      (dotimes [_ web/rate-limit-max-requests]
        (handler {:remote-addr ip}))
      ;; Next should be 429
      (let [response (handler {:remote-addr ip})]
        (is (= (:status response) 429))
        (is (some? (get-in response [:headers "Retry-After"])))))))

;; ============================================================
;; Basic Auth Parsing Tests
;; ============================================================

(deftest test-basic-auth-parsing
  (testing "Valid Basic auth header is parsed correctly"
    (let [b64 (.encodeToString (java.util.Base64/getEncoder)
                               (.getBytes "alice:secret123" "UTF-8"))
          result (web/parse-basic-auth (str "Basic " b64))]
      (is (= (:username result) "alice"))
      (is (= (:password result) "secret123"))))

  (testing "Non-Basic auth header returns nil"
    (is (nil? (web/parse-basic-auth "Bearer token123")))
    (is (nil? (web/parse-basic-auth nil)))
    (is (nil? (web/parse-basic-auth ""))))

  (testing "Malformed base64 returns nil"
    (is (nil? (web/parse-basic-auth "Basic !!!invalid!!!")))))

;; ============================================================
;; Response Helper Tests
;; ============================================================

(deftest test-json-response
  (testing "JSON response has correct content-type"
    (let [response (web/json-response {:status "ok" :count 42})]
      (is (= (:status response) 200))
      (is (= (get-in response [:headers "Content-Type"]) "application/json"))
      (is (= (json/parse-string (:body response) true) {:status "ok" :count 42})))))

;; ============================================================
;; Query String / Form Body Parser Tests
;; ============================================================

(deftest test-parse-query-string
  (testing "Standard query string parsing"
    (let [result (web/parse-query-string "name=hello&port=8080&protocol=tcp")]
      (is (= (:name result) "hello"))
      (is (= (:port result) "8080"))
      (is (= (:protocol result) "tcp"))))

  (testing "Empty or nil query string returns empty map"
    (is (= (web/parse-query-string "") {}))
    (is (= (web/parse-query-string nil) {})))

  (testing "URL-encoded values are decoded"
    (let [result (web/parse-query-string "remark=%E4%BD%A0%E5%A5%BD")]
      (is (= (:remark result) "你好")))))

;; ============================================================
;; Static File Serving Tests
;; ============================================================

(deftest test-static-file-serving
  (testing "Classpath assets are served and hashed"
    (let [response (web/serve-static-file "main.js" "application/javascript")]
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) "WebSocket"))
      (is (not= "0" (web/static-file-hash "main.js")))))
  (testing "Non-existent file returns 404"
    (let [response (web/serve-static-file "nonexistent.xyz" "text/plain")]
      (is (= (:status response) 404)))))

;; ============================================================
;; Token Authentication Tests
;; ============================================================

(deftest test-token-auth-flow
  (reset! auth/token-store {})
  (reset! auth/state {:users {:testuser {:password (auth/hash-password "password123")
                                         :authVersion 1
                                         :role "user"
                                         :createdAt "2026-01-01T00:00:00Z"
                                         :quotas {}}
                              :expired-user {:password (auth/hash-password "password123")
                                             :authVersion 1
                                             :role "user"}}})
  (testing "Token issue and validate lifecycle"
    (let [user-info (auth/authenticate "testuser" "password123")
          token (auth/issue-token user-info)
          validated (auth/validate-token token)]
      (is (string? token))
      (is (= 64 (count token)))
      (is (= (:username validated) "testuser"))
      (is (= (:role validated) "user"))
      (is (string? (:session-id validated)))
      (is (nil? (:expires validated)))
      ;; Revoke and validate
      (auth/revoke-token token)
      (is (nil? (auth/validate-token token)))))

  (testing "Invalid tokens are rejected"
    (is (nil? (auth/validate-token nil)))
    (is (nil? (auth/validate-token "")))
    (is (nil? (auth/validate-token "invalid-token"))))

  (testing "Token store cleanup removes expired"
    (let [user-info (auth/authenticate "expired-user" "password123")
          token (auth/issue-token user-info)]
      (swap! auth/token-store assoc-in [token :expires] (- (System/currentTimeMillis) 1000))
      (is (nil? (auth/validate-token token)))
      (is (nil? (get @auth/token-store token))))))
