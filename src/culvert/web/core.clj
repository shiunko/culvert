(ns culvert.web.core
  "Web 基础设施层：CSRF、限流、安全响应头、请求解析、响应辅助与认证。"
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [culvert.auth :as auth]
            [culvert.config :refer [config]]
            [culvert.i18n :as i18n]
            [culvert.log :as log]))

;; ============================================================
;; CSRF token 存储（基于 atom，并带 TTL）
;; ============================================================

(defonce csrf-store (atom {}))
(defonce ^:private web-cleaner (atom nil))
(def ^:private csrf-max 1000)
(def ^:private csrf-ttl-ms 3600000)
(def ^:private web-cleanup-ms 30000)

;; ============================================================
;; WebSocket 短期票据存储（一次性消费，代替直接将 24 小时 Bearer token 写入页面）
;; ============================================================

(defonce ws-ticket-store (atom {}))
(def ^:private ws-ticket-max 1000)
(def ^:private ws-ticket-ttl-ms 30000)

(def ^:dynamic *request-security-context* nil)

(defn- random-token []
  (let [bytes (byte-array 32)
        sr (java.security.SecureRandom.)]
    (.nextBytes sr bytes)
    (let [hex (StringBuilder.)]
      (doseq [b bytes] (.append hex (format "%02x" b)))
      (.toString hex))))

(defn- csrf-context
  ([] (or *request-security-context* {}))
  ([context]
   (merge (csrf-context) (or context {}))))

(defn- evict-oldest-csrf [store]
  (if (> (count store) csrf-max)
    (dissoc store (key (apply min-key (fn [[token entry]]
                                        [(:created-at entry) token])
                              store)))
    store))

(defn csrf-generate
  ([] (csrf-generate {:action nil}))
  ([context]
   (let [{:keys [session-id username action]} (csrf-context context)]
     (when (or (str/blank? session-id) (str/blank? username))
       (throw (ex-info "生成 CSRF token 需要已认证的会话和用户" {})))
     (let [token (random-token)
           now (System/currentTimeMillis)
           entry {:session-id session-id
                  :username username
                  :action action
                  :created-at now
                  :expires (+ now csrf-ttl-ms)}]
       (swap! csrf-store #(-> % (assoc token entry) evict-oldest-csrf))
       token))))

(defn csrf-validate
  ([token] (csrf-validate token (csrf-context) false))
  ([token context] (csrf-validate token context false))
  ([token context consume?]
   (let [{:keys [session-id username action]} (csrf-context context)
         now (System/currentTimeMillis)
         matches? (fn [entry]
                    (and entry
                         (<= now (:expires entry))
                         (= session-id (:session-id entry))
                         (= username (:username entry))
                         (or (nil? (:action entry))
                             (= action (:action entry)))))]
     (if (str/blank? token)
       false
       (let [[old-store _]
             (swap-vals! csrf-store
                         (fn [store]
                           (if-let [entry (get store token)]
                             (if (or (and consume? (matches? entry))
                                     (< (:expires entry) now))
                               (dissoc store token)
                               store)
                             store)))]
         (boolean (matches? (get old-store token))))))))

(defn get-csrf-token [req params]
  (or (:_csrf params)
      (get params :_csrf)
      (get-in req [:headers "x-csrf-token"])
      (get-in req [:headers "X-CSRF-Token"])))

(defn- evict-oldest-ws-ticket [store]
  (if (> (count store) ws-ticket-max)
    (dissoc store (key (apply min-key (fn [[ticket entry]]
                                        [(:created-at entry) ticket])
                              store)))
    store))

(defn ws-ticket-generate
  "为已认证用户发一张 30 秒有效、一次性消费的 WebSocket 连接票据，避免将长期有效的 Bearer token 写入页面/URL。"
  [user]
  (let [ticket (random-token)
        now (System/currentTimeMillis)
        entry {:username (:username user)
               :session-id (:session-id user)
               :created-at now
               :expires (+ now ws-ticket-ttl-ms)}]
    (swap! ws-ticket-store #(-> % (assoc ticket entry) evict-oldest-ws-ticket))
    ticket))

(defn ws-ticket-consume
  "验证并立即作废一张 WebSocket 票据，返回对应的 {:username :session-id}，失败返回 nil。"
  [ticket]
  (when-not (str/blank? ticket)
    (let [[old-store _] (swap-vals! ws-ticket-store dissoc ticket)
          entry (get old-store ticket)]
      (when (and entry (<= (System/currentTimeMillis) (:expires entry)))
        entry))))

(declare rate-limit-store rate-limit-window-ms)

(defn cleanup-web-security-state! []
  (let [now (System/currentTimeMillis)]
    (swap! csrf-store
           (fn [store]
             (into {} (filter (fn [[_ entry]]
                                (<= now (:expires entry)))
                              store))))
    (swap! ws-ticket-store
           (fn [store]
             (into {} (filter (fn [[_ entry]]
                                (<= now (:expires entry)))
                              store))))
    (swap! rate-limit-store
           (fn [store]
             (into {} (filter (fn [[_ entry]]
                                (or (> (:blocked-until entry 0) now)
                                    (some #(> (+ % rate-limit-window-ms) now)
                                          (:timestamps entry))))
                              store))))))

(defn start-cleaner! []
  (locking web-cleaner
    (when-not @web-cleaner
      (let [running (atom true)
            thread (Thread. (fn []
                              (try
                                (while @running
                                  (Thread/sleep web-cleanup-ms)
                                  (cleanup-web-security-state!))
                                (catch InterruptedException _))))]
        (.setDaemon thread true)
        (.start thread)
        (reset! web-cleaner {:running running :thread thread})))
    @web-cleaner))

(defn stop-cleaner! []
  (locking web-cleaner
    (when-let [{:keys [running thread]} @web-cleaner]
      (reset! running false)
      (.interrupt thread)
      (reset! web-cleaner nil))
    nil))

;; ============================================================
;; Rate Limiting (IP-based sliding window)
;; ============================================================

(defonce rate-limit-store (atom {})) ; ip -> {:timestamps [ms ...] :blocked-until ms}
(def ^:private rate-limit-window-ms 60000)  ; 1 minute window
(def rate-limit-max-requests 120)  ; max requests per window
(def ^:private rate-limit-block-ms 300000)   ; 5 minute block on excess

(defn- trusted-proxy? [remote-addr]
  (contains? (set (or (:trusted-proxies config) [])) remote-addr))

(defn- get-client-ip [req]
  (let [remote-addr (or (:remote-addr req) "unknown")]
    (if (trusted-proxy? remote-addr)
      (or (some-> (get-in req [:headers "x-forwarded-for"])
                  (str/split #",")
                  first
                  str/trim
                  not-empty)
          (some-> (get-in req [:headers "x-real-ip"]) str/trim not-empty)
          remote-addr)
      remote-addr)))

(defn rate-limit-allowed? [ip]
  (let [now (System/currentTimeMillis)
        store (swap! rate-limit-store
                     (fn [store]
                       (let [entry (get store ip {:timestamps [] :blocked-until 0})]
                         (if (> (:blocked-until entry 0) now)
                           store
                           (let [recent (filterv #(> (+ % rate-limit-window-ms) now)
                                                 (:timestamps entry))]
                             (if (>= (count recent) rate-limit-max-requests)
                               (assoc store ip {:timestamps recent
                                                :blocked-until (+ now rate-limit-block-ms)})
                               (assoc store ip {:timestamps (conj recent now)
                                                :blocked-until 0})))))))
        entry (get store ip)]
    (not (> (:blocked-until entry 0) now))))

(defn wrap-rate-limit [handler]
  (fn [req]
    (let [ip (get-client-ip req)]
      (if (rate-limit-allowed? ip)
        (handler req)
        {:status 429
         :headers {"Content-Type" "text/plain"
                   "Retry-After" (str (quot rate-limit-block-ms 1000))}
         :body (:rate-limited (:toast i18n/strings))}))))

;; ============================================================
;; Security Headers (Helmet equivalent)
;; ============================================================

(def ^:private security-headers
  {"X-Content-Type-Options" "nosniff"
   "X-Frame-Options" "DENY"
   "X-XSS-Protection" "1; mode=block"
   "Referrer-Policy" "strict-origin-when-cross-origin"
   "Permissions-Policy" "camera=(), microphone=(), geolocation=()"
   "Content-Security-Policy" (str "default-src 'self'; "
                                  "script-src 'self' https://unpkg.com https://cdn.jsdelivr.net; "
                                  "style-src 'self' https://cdn.jsdelivr.net; "
                                  "img-src 'self' data:; "
                                  "connect-src 'self' ws: wss:; "
                                  "frame-ancestors 'none'; "
                                  "form-action 'self'")
   "Cache-Control" "no-store"})

(defn wrap-security-headers [handler]
  (fn [req]
    (let [response (handler req)]
      (update response :headers (fn [h] (merge security-headers h))))))

;; ============================================================
;; Request Parameter Parsers
;; ============================================================

(defn parse-query-string [s]
  (if (str/blank? s)
    {}
    (into {}
          (map (fn [pair]
                 (let [[k v] (str/split pair #"=" 2)]
                   [(keyword k) (java.net.URLDecoder/decode (or v "") "UTF-8")]))
               (str/split s #"&")))))

(defn parse-form-body [req]
  (let [body-str (if (string? (:body req))
                   (:body req)
                   (when (:body req) (slurp (:body req))))]
    (parse-query-string body-str)))

;; ============================================================
;; Response Helpers
;; ============================================================

(defn html-response [hiccup-content]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str "<!DOCTYPE html>\n" (h/html hiccup-content))})

(defn json-response [data]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string data)})

;; ============================================================
;; htmx Response Helper (toast notifications)
;; ============================================================

(defn htmx-response
  ([hiccup-content toast-msg toast-type]
   (htmx-response hiccup-content toast-msg toast-type {}))
  ([hiccup-content toast-msg toast-type extra-triggers]
   {:status 200
    :headers {"Content-Type" "text/html; charset=utf-8"
              "HX-Trigger" (json/generate-string (merge {:show-toast {:message toast-msg :type toast-type}}
                                                        extra-triggers)
                                                 {:escape-non-ascii true})}
    :body (str (h/html hiccup-content))}))

;; ============================================================
;; 错误协议（不允许将未预期异常的 .getMessage 直接透传给客户端）
;; ============================================================

(defn error-info
  "把异常整理为 {:message 用户可见文本 :status HTTP 状态码}，并在服务端记一条日志。

   本项目领域层（auth/caddy/docker/forward/smolvm 等）均用 `(throw (Exception. 安全提示))` 或
   `(throw (ex-info 安全提示 {...}))` 主动抛出错误，消息本身就是为用户书写的中文提示（已全库扫描确认，
   无需区分异常类型才能安全直接展示，否则会把现有的具体提示（如“用户名已存在”）退化成无用的通用提示，反而降低可用性）。
   本函数修正的是两个实际问题：过去无论成功失败 HTTP 状态码都固定为 200（不反映失败），
   且失败完全不记日志（服务端零可观测性）。消息为空时回退到通用提示，作为防御。"
  [e]
  (log/warn "请求处理失败:" (.getMessage e))
  {:message (let [msg (.getMessage e)]
              (if (str/blank? msg) (:operation-failed (:toast i18n/strings)) msg))
   :status 400})

(defn htmx-error-response
  "与 `htmx-response` 形式一致，但从异常推导安全消息与正确的 HTTP 失败状态码（不再固定返回 200）。"
  ([hiccup-content e] (htmx-error-response hiccup-content e {}))
  ([hiccup-content e extra-triggers]
   (let [{:keys [message status]} (error-info e)]
     {:status status
      :headers {"Content-Type" "text/html; charset=utf-8"
                "HX-Trigger" (json/generate-string (merge {:show-toast {:message message :type "error"}}
                                                          extra-triggers)
                                                   {:escape-non-ascii true})}
      :body (str (h/html hiccup-content))})))

(defn json-error-response
  "JSON 接口的错误形式：`{:error \"安全消息\"}`，与现有 JS 消费方 `data.error` 的字符串契约保持一致，只修正消息安全性和 HTTP 状态码。"
  [e]
  (let [{:keys [message status]} (error-info e)]
    {:status status
     :headers {"Content-Type" "application/json"}
     :body (json/generate-string {:error message})}))

;; ============================================================
;; Static File Serving
;; ============================================================

(def ^:private static-hash-cache (atom {}))

(defn- static-resource [filename]
  (or (io/resource (str "public/" filename))
      (let [file (io/file "public" filename)]
        (when (.isFile file) file))))

(defn static-file-hash
  "Returns a stable cache-busting hash for a classpath or development asset."
  [filename]
  (if-let [cached (get @static-hash-cache filename)]
    cached
    (let [resource (static-resource filename)
          hash (if resource
                 (format "%x" (hash (slurp resource)))
                 "0")]
      (swap! static-hash-cache assoc filename hash)
      hash)))

(defn asset-url
  "Returns a versioned asset URL: /filename?v=HASH"
  [filename]
  (str "/" filename "?v=" (static-file-hash filename)))

(defn serve-static-file [filename mime-type]
  (if-let [resource (static-resource filename)]
    {:status 200
     :headers {"Content-Type" mime-type}
     :body (slurp resource)}
    {:status 404
     :body "Not Found"}))

;; ============================================================
;; 认证中间件（优先 token，回退 Basic）
;; ============================================================

(defn parse-basic-auth [auth-header]
  (when (and auth-header (str/starts-with? auth-header "Basic "))
    (try
      (let [b64-str (subs auth-header 6)
            decoded (String. (.decode (java.util.Base64/getDecoder) b64-str) "UTF-8")
            [user pass] (str/split decoded #":" 2)]
        {:username user :password pass})
      (catch Exception _ nil))))

(defn- parse-bearer-token [auth-header]
  (when (and auth-header (str/starts-with? auth-header "Bearer "))
    (subs auth-header 7)))

(defn wrap-auth [handler]
  (fn [req]
    (let [auth-header (get-in req [:headers "authorization"])
          ;; 优先使用 token 认证，避免重复计算密码哈希
          token (parse-bearer-token auth-header)
          token-user (when token (auth/validate-token token))
          ;; token 无效时回退到 Basic Auth
          creds (when-not token-user (parse-basic-auth auth-header))
          user (or token-user
                   (when creds (auth/authenticate (:username creds) (:password creds))))]
      (if user
        (let [request-context {:session-id (:session-id user)
                               :username (:username user)
                               :action [(:request-method req) (:uri req)]}]
          (binding [*request-security-context* request-context]
            (handler (assoc req :user user :security-context request-context))))
        {:status 401
         :headers {"WWW-Authenticate" "Basic realm=\"culvert\""}
         :body "Unauthorized"}))))

;; 登录：用 Basic Auth 凭据换取会话 token
(defn handle-login [req]
  (let [auth-header (get-in req [:headers "authorization"])
        creds (parse-basic-auth auth-header)
        user (when creds (auth/authenticate (:username creds) (:password creds)))]
    (if user
      (let [token (auth/issue-token user)]
        (json-response {:token token :user (dissoc user :quotas :session-id :auth-version)}))
      {:status 401
       :headers {"WWW-Authenticate" "Basic realm=\"culvert\""}
       :body "Unauthorized"})))

;; 注销：撤销会话 token
(defn handle-logout [req]
  (let [auth-header (get-in req [:headers "authorization"])
        token (parse-bearer-token auth-header)]
    (when token (auth/revoke-token token))
    (json-response {:status "ok"})))
