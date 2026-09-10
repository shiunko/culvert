(ns culvert.web.server
  "HTTP 服务器的启动与停止生命周期。"
  (:require [org.httpkit.server :as server]
            [culvert.auth :as auth]
            [culvert.config :refer [config]]
            [culvert.log :as log]
            [culvert.observability.context :as request-context]
            [culvert.web.core :as core]
            [culvert.web.handlers :as handlers]
            [culvert.web.ws :as ws]))

;; ============================================================
;; Server Lifecycle
;; ============================================================

(defonce server-inst (atom nil))

(defn start-server! []
  (let [port (:port config)
        listen-address (:listen-address config)
        raw-handler (fn [req]
                      ;; WebSocket 升级在进入常规中间件链前验证一次性票据（见 POST /ws/ticket 与 core/ws-ticket-consume）。
                      ;; 票据只能证明“请求时已登录”，还需重新从用户表取角色/配额，避免票据中残留过期信息。
                      (if (and (= (:uri req) "/ws")
                               (= (get-in req [:headers "upgrade"]) "websocket"))
                        (let [qs (core/parse-query-string (:query-string req))
                              ticket (:ticket qs)
                              ticket-info (when ticket (core/ws-ticket-consume ticket))
                              user (when ticket-info
                                     (some-> (auth/get-user (:username ticket-info))
                                             (assoc :session-id (:session-id ticket-info))))]
                          (if user
                            (ws/ws-handler req user)
                            {:status 401 :body "Unauthorized"}))
                        (try
                          (handlers/route-dispatcher req)
                          (catch Exception e
                            (log/error "处理请求错误:" (.getMessage e))
                            (.printStackTrace e)
                            {:status 500 :body "Internal Server Error"}))))
        ;; 中间件顺序：安全响应头 → 限流 → 核心 Handler。
        handler (-> raw-handler
                    core/wrap-rate-limit
                    core/wrap-security-headers
                    request-context/wrap-request-context)]
    (reset! server-inst (server/run-server handler {:ip listen-address :port port}))
    (log/info (format "HTTP 服务器已启动，监听 %s:%d" listen-address port))))

(defn stop-server! []
  (when-let [stop-fn @server-inst]
    (stop-fn)
    (log/info "HTTP 服务器已停止")
    (reset! server-inst nil)))
