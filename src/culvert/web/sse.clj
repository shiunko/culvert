(ns culvert.web.sse
  "SSE（Server-Sent Events）推送中枢，用于服务端→客户端的单向实时同步。
   任何状态变更后调用 (broadcast! {:event \"refresh\" :panels [...]}) 即可推送刷新事件。
   每隔 5 秒还会向各连接的用户推送容器/虚机资源 stats。"
  (:require [cheshire.core :as json]
            [org.httpkit.server :as server]
            [culvert.log :as log]
            [culvert.docker :as docker]
            [culvert.cli :as cli]
            [culvert.smolvm.manager :as smolvm]))

;; ============================================================
;; Channel Registry (channel -> user info)
;; ============================================================

(defonce channels (atom {})) ;; {ch {:username "..." :is-admin true/false}}

;; ============================================================
;; SSE Connection Handler
;; ============================================================

(defn sse-handler
  "建立 SSE 长连接，发送初始 HTTP 响应头后将 channel 注册到全局连接表。
   认证（SSE ticket 验证）已在 server.clj 的路由层完成，此处只管推送。"
  [req user]
  (let [username (:username user)
        is-admin (= (:role user) "admin")]
    (server/as-channel req
                       {:on-open (fn [ch]
                                   (server/send! ch {:status 200
                                                     :headers {"Content-Type" "text/event-stream"
                                                               "Cache-Control" "no-cache"
                                                               "Connection" "keep-alive"
                                                               "X-Accel-Buffering" "no"}}
                                                 false)
                                   (swap! channels assoc ch {:username username :is-admin is-admin})
                                   (log/info (format "[sse] 客户端已连接: %s（当前 %d 个连接）"
                                                     username (count @channels))))
                        :on-close (fn [ch _status]
                                    (swap! channels dissoc ch)
                                    (log/info (format "[sse] 客户端已断开: %s（当前 %d 个连接）"
                                                      username (count @channels))))})))

;; ============================================================
;; Broadcast API
;; ============================================================

(defn broadcast!
  "向所有已连接的 SSE 客户端广播一条事件。
   event 为 Clojure map，例如：
     {:event \"refresh\" :panels [\"forward-tbody\" \"quota-bar\"]}"
  [event]
  (when (seq @channels)
    (let [msg (str "data: " (json/generate-string event) "\n\n")]
      (doseq [ch (keys @channels)]
        (try
          (server/send! ch msg false)
          (catch Exception e
            (log/warn "[sse] 广播发送失败:" (.getMessage e))
            (swap! channels dissoc ch)))))))

;; ============================================================
;; Per-User Stats Broadcast
;; ============================================================

(defn- compute-user-stats
  [username is-admin]
  (try
    (let [containers (docker/get-containers username is-admin)
          all-stats (cli/get-container-stats)]
      (into {}
            (for [c containers
                  :when (= (:status c) "running")
                  :let [stats (get all-stats (:name c))]]
              [(:id c)
               (or stats {:cpu-pct 0.0 :mem-pct 0.0 :mem-used "—" :mem-limit "—"})])))
    (catch Exception e
      (log/warn "[sse] 计算容器 stats 失败:" (.getMessage e))
      {})))

(defn- compute-smolvm-stats
  [username is-admin]
  (try
    (let [machines (smolvm/get-machines username is-admin)]
      (into {}
            (for [m machines
                  :when (= (:status m) "running")]
              [(:id m) {:status "running"
                        :type "smolvm"
                        :cpu-pct 0.0
                        :mem-pct 0.0
                        :mem-used "—"
                        :mem-limit (str (:memLimit m) "MiB")}])))
    (catch Exception e
      (log/warn "[sse] 计算 smolvm stats 失败:" (.getMessage e))
      {})))

(defn broadcast-stats!
  "向每个已连接的 SSE 客户端推送其专属的容器和虚机资源统计。"
  []
  (when (seq @channels)
    (doseq [[ch user-info] @channels]
      (try
        (let [username (:username user-info)
              is-admin (:is-admin user-info)
              docker-stats (compute-user-stats username is-admin)
              smolvm-stats (compute-smolvm-stats username is-admin)
              all-stats (merge docker-stats smolvm-stats)]
          (when (seq all-stats)
            (server/send! ch (str "data: " (json/generate-string {:event "stats" :stats all-stats}) "\n\n") false)))
        (catch Exception e
          (log/warn "[sse] stats 推送失败:" (.getMessage e))
          (swap! channels dissoc ch))))))

;; ============================================================
;; Stats Push Daemon Thread
;; ============================================================

(defonce ^:private stats-broadcaster (atom nil))

(defn stats-broadcaster-running? []
  (boolean (some-> @stats-broadcaster :thread .isAlive)))

(defn start-stats-broadcaster! []
  (locking stats-broadcaster
    (when-not (stats-broadcaster-running?)
      (let [running (atom true)
            thread (Thread. (fn []
                              (try
                                (while @running
                                  (Thread/sleep 5000)
                                  (when @running
                                    (broadcast-stats!)))
                                (catch InterruptedException _))))]
        (.setDaemon thread true)
        (.setName thread "culvert-stats-broadcaster")
        (.start thread)
        (reset! stats-broadcaster {:running running :thread thread})
        (log/info "[sse] Stats 推送线程已启动（每 5 秒）")))
    @stats-broadcaster))

(defn stop-stats-broadcaster! []
  (locking stats-broadcaster
    (when-let [{:keys [running thread]} @stats-broadcaster]
      (reset! running false)
      (.interrupt thread)
      (try
        (.join thread 1000)
        (catch InterruptedException _
          (.interrupt (Thread/currentThread))))
      (reset! stats-broadcaster nil)
      (log/info "[sse] Stats 推送线程已停止"))
    nil))
