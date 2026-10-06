(ns culvert.web-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [culvert.auth :as auth]
            [culvert.config :as config]
            [culvert.docker :as docker]
            [culvert.forward :as forward]
            [culvert.i18n :as i18n]
            [culvert.observability.activity :as activity]
            [culvert.smolvm.manager :as smolvm]
            [culvert.web.core :as web]
            [culvert.web.handlers :as handlers]
            [culvert.web.views :as views]
            [culvert.web.sse :as sse]))

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
;; SSE 票据测试
;; ============================================================

(deftest sse-ticket-issue-and-validate
  (reset! web/csrf-store {})
  (reset! web/sse-ticket-store {})
  (with-redefs [auth/authenticate (fn [_ _] {:username "alice"
                                             :session-id "basic:alice:1"
                                             :role "user"
                                             :quotas {}})]
    (testing "仪表板页面返回 200，且包含 sse-ticket meta 标签"
      (let [panel-response (handlers/route-dispatcher
                            {:request-method :get
                             :uri "/"
                             :headers {"authorization" (basic-authorization "alice" "secret")}})]
        (is (= 200 (:status panel-response)))
        (is (str/includes? (:body panel-response) "sse-ticket"))
        (is (not (str/includes? (:body panel-response) "ws-token")))))

    (testing "SSE 票据可重复验证（允许 EventSource 断线重连）"
      (let [user {:username "alice" :session-id "basic:alice:1" :role "user"}
            ticket (web/sse-ticket-generate user)]
        (is (string? ticket))
        (is (= 64 (count ticket)))
        (is (= {:username "alice" :session-id "basic:alice:1"}
               (select-keys (web/sse-ticket-validate ticket) [:username :session-id])))
        ;; 票据不消费，第二次仍有效
        (is (= {:username "alice" :session-id "basic:alice:1"}
               (select-keys (web/sse-ticket-validate ticket) [:username :session-id])))))

    (testing "不存在、空白的票据均被拒绝"
      (is (nil? (web/sse-ticket-validate "no-such-ticket")))
      (is (nil? (web/sse-ticket-validate nil)))
      (is (nil? (web/sse-ticket-validate ""))))))

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
      (is (str/includes? csp "form-action 'self'"))
      ;; script-src 绝不允许 unsafe-inline（XSS 风险）
      (let [script-src (second (re-find #"script-src ([^;]+)" csp))]
        (is (not (str/includes? (or script-src "") "'unsafe-inline'"))))
      ;; style-src 允许 unsafe-inline（htmx 写 element.style 需要）
      (let [style-src (second (re-find #"style-src ([^;]+)" csp))]
        (is (str/includes? (or style-src "") "'unsafe-inline'"))))))

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

;; ============================================================
;; Kiosk 大屏：只读且与 / 相同的认证边界
;; ============================================================

(deftest wall-is-read-only-and-authenticated
  (testing "未认证被拒绝"
    (is (= 401
           (:status (handlers/route-dispatcher {:request-method :get :uri "/wall"})))))
  (testing "已认证返回只读大屏，不含任何 mutation 入口"
    (with-redefs [auth/authenticate (fn [_ _] {:username "alice"
                                               :session-id "basic:alice:1"
                                               :role "user"
                                               :quotas {}})]
      (let [response (handlers/route-dispatcher
                      {:request-method :get
                       :uri "/wall"
                       :headers {"authorization" (basic-authorization "alice" "secret")}})
            body (:body response)]
        (is (= 200 (:status response)))
        (is (str/includes? body "CULVERT MONITOR"))
        (is (str/includes? body "wall-clock"))
        (is (not (str/includes? body "hx-post")))
        (is (not (str/includes? body "command-palette")))
        (is (not (str/includes? body "app-tabs")))))))

;; ============================================================
;; 活动记录：mutation 成功后写入事件（P4）
;; ============================================================

(deftest successful-mutation-records-activity
  (activity/clear!)
  (with-redefs [auth/authenticate (fn [_ _] {:username "alice"
                                             :session-id "basic:alice:1"
                                             :role "user"
                                             :quotas {}})
                web/csrf-validate (fn [& _] true)
                forward/get-entries (fn [& _] [])
                forward/add-forward! (fn [& _] nil)]
    (handlers/route-dispatcher
     {:request-method :post
      :uri "/add"
      :headers {"authorization" (basic-authorization "alice" "secret")}
      :body "ip=10.0.0.5&port=8080&toPort=80&protocol=tcp&method=socat&_csrf=t"})
    (let [event (first (activity/recent 1))]
      (is (= :forward/add (:action event)))
      (is (= :success (:outcome event)))
      (is (= :info (:severity event)))
      (is (= "alice" (:actor event)))
      (is (= "fwd-8080-tcp-socat" (get-in event [:resource :key])))))
  (activity/clear!))

;; ============================================================
;; 管理页主机指标：端口池聚合（P5）
;; ============================================================

(deftest port-pool-usage-aggregates-real-ports
  (with-redefs [forward/get-entries (fn [& _] [{:isRange false}
                                               {:isRange true :fromPort 10000 :toRangePort 10009}])
                docker/get-containers (fn [& _] [{:ports {"8080" {:enabled true}
                                                          "8081" {:enabled false}}}])
                smolvm/get-machines (fn [& _] [{:ports {"9000" {:enabled true}}}])]
    (let [usage (views/port-pool-usage "admin" true)]
      (testing "占用 = 1 单端口 + 10 范围 + 1 已启用容器端口 + 1 已启用微虚机端口"
        (is (= 13 (:used usage))))
      (is (pos? (:total usage)))
      (is (number? (:used-pct usage))))))

;; ============================================================
;; 管理页主机指标：/panel/host 仅管理员（P5）
;; ============================================================

(deftest host-panel-is-admin-only
  (testing "普通用户被拒绝"
    (with-redefs [auth/authenticate (fn [_ _] {:username "alice"
                                               :session-id "basic:alice:1"
                                               :role "user"
                                               :quotas {}})]
      (is (= 403
             (:status (handlers/route-dispatcher
                       {:request-method :get
                        :uri "/panel/host"
                        :headers {"authorization" (basic-authorization "alice" "secret")}}))))))
  (testing "管理员返回主机指标卡"
    (with-redefs [auth/authenticate (fn [_ _] {:username "admin"
                                               :session-id "basic:admin:1"
                                               :role "admin"
                                               :quotas {}})]
      (let [response (handlers/route-dispatcher
                      {:request-method :get
                       :uri "/panel/host"
                       :headers {"authorization" (basic-authorization "admin" "secret")}})]
        (is (= 200 (:status response)))
        (is (str/includes? (:body response) "pf-host"))
        (is (str/includes? (:body response) "端口池"))))))

;; ============================================================
;; 容器日志面板：同源读取与授权（应用内替代 ttyd 日志弹窗）
;; ============================================================

(defn- admin-user []
  {:username "admin" :session-id "basic:admin:1" :role "admin" :quotas {}})

(deftest container-logs-panel-renders-for-authorized-target
  (testing "管理员可读取未受管宿主机容器的日志，且日志文本被放进面板"
    (let [original @docker/state]
      (try
        (reset! docker/state {:containers {} :used-ports #{} :ttyd-procs {}})
        (with-redefs [auth/authenticate (fn [_ _] (admin-user))
                      docker/read-logs (fn [target actor opts]
                                         (is (= "host-box" target))
                                         (is (= "admin" (:username actor)))
                                         (is (= 200 (:tail opts)))
                                         {:source :host :id "host-box" :name "host-box"
                                          :state "exited" :text "line-1\nline-2"})]
          (let [response (handlers/route-dispatcher
                          {:request-method :get
                           :uri "/docker/logs"
                           :query-string "target=host-box"
                           :headers {"authorization" (basic-authorization "admin" "secret")}})]
            (is (= 200 (:status response)))
            (is (str/includes? (:body response) "logModal"))
            (is (str/includes? (:body response) "line-1"))
            (is (str/includes? (:body response) "/docker/logs/content"))))
        (finally
          (reset! docker/state original)))))
  (testing "无权目标返回面板内提示，不返回 5xx（保证 htmx 能换入）"
    (let [original @docker/state]
      (try
        (reset! docker/state {:containers {} :used-ports #{} :ttyd-procs {}})
        (with-redefs [auth/authenticate (fn [_ _] {:username "alice"
                                                   :session-id "basic:alice:1"
                                                   :role "user"
                                                   :quotas {}})
                      docker/read-logs (fn [& _] (throw (Exception. "无权操作此容器")))]
          (let [response (handlers/route-dispatcher
                          {:request-method :get
                           :uri "/docker/logs"
                           :query-string "target=other-box"
                           :headers {"authorization" (basic-authorization "alice" "secret")}})]
            (is (= 200 (:status response)))
            (is (str/includes? (:body response) "无权操作此容器"))))
        (finally
          (reset! docker/state original))))))

(deftest container-logs-content-returns-pre-only
  (testing "轮询端点只返回日志正文，保留外层 #log-stream 的 hx-* 属性"
    (with-redefs [auth/authenticate (fn [_ _] (admin-user))
                  docker/read-logs (fn [& _] {:name "box" :state "running" :text "tail-text"})]
      (let [response (handlers/route-dispatcher
                      {:request-method :get
                       :uri "/docker/logs/content"
                       :query-string "target=box&tail=1000"
                       :headers {"authorization" (basic-authorization "admin" "secret")}})]
        (is (= 200 (:status response)))
        (is (str/includes? (:body response) "tail-text"))
        (is (not (str/includes? (:body response) "logModal")))))))

(deftest removed-log-ttyd-routes-are-gone
  (testing "旧 ttyd 日志弹窗路由已下线"
    (with-redefs [auth/authenticate (fn [_ _] (admin-user))]
      (is (= 404
             (:status (handlers/route-dispatcher
                       {:request-method :post
                        :uri "/docker/logs/start"
                        :headers {"authorization" (basic-authorization "admin" "secret")}}))))
      (is (= 404
             (:status (handlers/route-dispatcher
                       {:request-method :post
                        :uri "/docker/logs/stop"
                        :headers {"authorization" (basic-authorization "admin" "secret")}})))))))

;; ============================================================
;; 管理员容器终端：路由授权、CSRF 与凭据面板
;; ============================================================

(defn- admin-terminal-request [method uri csrf-token]
  (cond-> {:request-method method
           :uri uri
           :headers {"authorization" (basic-authorization "admin" "secret")}}
    csrf-token (assoc :body (str "target=host-box&_csrf=" csrf-token))))

(deftest admin-terminal-routes-are-admin-only
  (testing "普通用户被拒绝，且不进入领域入口"
    (let [called (atom 0)]
      (with-redefs [auth/authenticate (fn [_ _] {:username "alice"
                                                 :session-id "basic:alice:1"
                                                 :role "user"
                                                 :quotas {}})
                    docker/open-webshell! (fn [& _] (swap! called inc))]
        (is (= 403 (:status (handlers/route-dispatcher
                             {:request-method :post
                              :uri "/admin/containers/terminal/open"
                              :headers {"authorization" (basic-authorization "alice" "secret")}}))))
        (is (= 403 (:status (handlers/route-dispatcher
                             {:request-method :post
                              :uri "/admin/containers/terminal/close"
                              :headers {"authorization" (basic-authorization "alice" "secret")}}))))
        (is (= 403 (:status (handlers/route-dispatcher
                             {:request-method :post
                              :uri "/admin/containers/terminal/credentials"
                              :headers {"authorization" (basic-authorization "alice" "secret")}}))))
        (is (zero? @called))))))

(deftest admin-terminal-open-requires-csrf
  (testing "管理员缺 CSRF token 时被拒绝"
    (let [called (atom 0)]
      (with-redefs [auth/authenticate (fn [_ _] (admin-user))
                    docker/open-webshell! (fn [& _] (swap! called inc))]
        (is (= 403 (:status (handlers/route-dispatcher
                             (admin-terminal-request :post "/admin/containers/terminal/open" nil)))))
        (is (zero? @called))))))

(deftest admin-terminal-open-returns-credential-panel
  (testing "开启成功返回凭据面板（明文只在此片段出现）并广播列表刷新"
    (reset! web/csrf-store {})
    (let [broadcasts (atom [])
          recorded (atom [])]
      (with-redefs [auth/authenticate (fn [_ _] (admin-user))
                    docker/open-webshell! (fn [target actor]
                                            (is (= "host-box" target))
                                            (is (true? (:admin? actor)))
                                            {:name "host-box" :status "running"
                                             :url "http://term-x.example:17680"
                                             :user "dev_ab" :passMasked "●●●"})
                    docker/reveal-webshell-credentials (fn [& _] {:user "dev_ab" :pass "pw-123"})
                    sse/broadcast! (fn [msg] (swap! broadcasts conj msg))
                    activity/record! (fn [event] (swap! recorded conj event))]
        (handlers/route-dispatcher {:request-method :get
                                    :uri "/admin/containers"
                                    :headers {"authorization" (basic-authorization "admin" "secret")}})
        (let [csrf-token (first (keys @web/csrf-store))
              response (handlers/route-dispatcher
                        (admin-terminal-request :post "/admin/containers/terminal/open" csrf-token))]
          (is (= 200 (:status response)))
          (is (str/includes? (:body response) "terminalModal"))
          (is (str/includes? (:body response) "pw-123"))
          (is (some #(= [:docker/terminal-open] [(:action %)]) @recorded))
          (is (some #(some #{"admin-containers-tbody"} (:panels %)) @broadcasts)))))))

(deftest admin-terminal-close-refreshes-container-table
  (testing "关闭终端返回管理表片段并记录活动"
    (reset! web/csrf-store {})
    (let [closed (atom 0)
          recorded (atom [])]
      (with-redefs [auth/authenticate (fn [_ _] (admin-user))
                    docker/get-all-host-containers (fn [] [])
                    docker/close-webshell! (fn [target _actor]
                                             (swap! closed inc)
                                             (is (= "host-box" target))
                                             {:status "ok"})
                    sse/broadcast! (fn [_] nil)
                    activity/record! (fn [event] (swap! recorded conj event))]
        (handlers/route-dispatcher {:request-method :get
                                    :uri "/admin/containers"
                                    :headers {"authorization" (basic-authorization "admin" "secret")}})
        (let [csrf-token (first (keys @web/csrf-store))
              response (handlers/route-dispatcher
                        (admin-terminal-request :post "/admin/containers/terminal/close" csrf-token))]
          (is (= 200 (:status response)))
          (is (str/includes? (:body response) "pf-row-actions"))
          (is (= 1 @closed))
          (is (some #(= [:docker/terminal-close] [(:action %)]) @recorded)))))))

;; ============================================================
;; 总览：端口频谱测试（数值升序排序、范围端口、预留池与渲染）
;; ============================================================

(deftest port-segments-sorted-and-normalized
  (testing "端口段严格按起始端口数值升序排序，且正确区分单端口与端口范围"
    (with-redefs [forward/get-entries
                  (fn [& _]
                    ;; 故意提供无序的真实模拟数据（与用户提供的 HTML 一致）
                    [{:port 21000 :protocol "tcp" :method "socat" :ip "192.168.99.4" :toPortDisplay "3389" :userId "moonling" :enabled true}
                     {:port 22224 :protocol "tcp" :method "socat" :ip "192.168.172.1" :toPortDisplay "5555" :userId "moonling" :enabled true}
                     {:isRange true :fromPort 21001 :toRangePort 21009 :protocol "tcp" :method "socat" :ip "192.168.99.4" :toPortDisplay "21001-21009" :userId "moonling" :enabled true}
                     {:isRange true :fromPort 19010 :toRangePort 19019 :protocol "tcp" :method "socat" :ip "192.168.99.3" :toPortDisplay "19010-19019" :userId "weilai" :enabled true}
                     {:port 18000 :protocol "tcp" :method "socat" :ip "192.168.172.1" :toPortDisplay "22" :userId "moonling" :enabled true}])
                  docker/get-containers (fn [& _] [])
                  smolvm/get-machines (fn [& _] [])]
      (let [segs (views/port-segments "admin" true)
            starts (mapv :start segs)]
        ;; 1. 验证排序是严格升序的
        (is (apply <= starts) "端口段起始端口必须单调不减")
        ;; 2. 验证范围端口具有正确属性
        (let [range-seg (first (filter :is-range? segs))]
          (is (some? range-seg))
          (is (pos? (:port-count range-seg)))
          (is (str/includes? (:label range-seg) "端口")))
        ;; 3. 验证未被占用的预留池（如 Shell、微虚机）包含在段中且排在正确位置
        (let [reserved (filter :reserved? segs)]
          (is (seq reserved))
          (is (every? #(= "系统预留" (:owner %)) reserved)))))))

(deftest render-port-spectrum-content-structure
  (testing "端口频谱内容包含卡片头部、预留池概览、协议筛选与动态带"
    (with-redefs [forward/get-entries
                  (fn [& _]
                    [{:port 18000 :protocol "tcp" :method "socat" :ip "127.0.0.1" :toPortDisplay "22" :userId "admin" :enabled true}
                     {:isRange true :fromPort 19010 :toRangePort 19019 :protocol "tcp" :method "socat" :ip "127.0.0.1" :toPortDisplay "19010-19019" :userId "admin" :enabled true}])
                  docker/get-containers (fn [& _] [])
                  smolvm/get-machines (fn [& _] [])]
      (let [html (views/render-port-spectrum-content "admin" true)]
        (is (str/includes? html "id=\"port-spectrum-track\""))
        (is (str/includes? html "pf-spectrum-pools"))
        (is (str/includes? html "pf-spectrum-bandlabel"))
        (is (str/includes? html "data-band=\"low\""))
        (is (str/includes? html "data-band=\"high\""))
        (is (str/includes? html "pf-seg--range"))
        (is (str/includes? html "全部协议"))))))
