(ns culvert.web.server
  "HTTP 服务器的启动与停止生命周期。"
  (:require [org.httpkit.server :as server]
            [culvert.auth :as auth]
            [culvert.config :refer [config]]
            [culvert.log :as log]
            [culvert.observability.context :as request-context]
            [culvert.web.core :as core]
            [culvert.web.handlers :as handlers]
            [culvert.web.sse :as sse]))

(defonce server-inst (atom nil))

(defn start-server! []
  (let [port (:port config)
        listen-address (:listen-address config)
        raw-handler (fn [req]
                      ;; SSE 长连接在进入常规中间件链前单独处理：
                      ;; 验证页面嵌入的 SSE ticket，通过后建立 text/event-stream 连接。
                      (if (and (= (:uri req) "/events")
                               (= (:request-method req) :get))
                        (let [qs (core/parse-query-string (:query-string req))
                              ticket (:ticket qs)
                              ticket-info (when ticket (core/sse-ticket-validate ticket))
                              user (when ticket-info
                                     (some-> (auth/get-user (:username ticket-info))
                                             (assoc :session-id (:session-id ticket-info))))]
                          (if user
                            (sse/sse-handler req user)
                            {:status 401 :body "Unauthorized"}))
                        (try
                          (handlers/route-dispatcher req)
                          (catch Exception e
                            (log/error "处理请求错误:" (.getMessage e))
                            (.printStackTrace e)
                            {:status 500 :body "Internal Server Error"}))))
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
