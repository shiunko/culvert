(ns culvert.web.ws
  "WebSocket broadcast hub for real-time multi-user state synchronization.
   After any state mutation, call (broadcast! {:event \"refresh\" :panels [...]})
   to push refresh events to all connected clients.
   Also provides per-user container stats push via broadcast-stats!."
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
;; WebSocket Lifecycle Handler
;; ============================================================

(defn ws-handler
  "Accepts a WebSocket upgrade request and manages the channel lifecycle.
   `user` is the authenticated user map from token validation.
   Returns nil (upgrade handled by http-kit)."
  [req user]
  (let [username (:username user)
        is-admin (= (:role user) "admin")]
    (server/as-channel req
                       {:on-open (fn [ch]
                                   (swap! channels assoc ch {:username username :is-admin is-admin})
                                   (log/info (format "[ws] 客户端已连接: %s (当前 %d 个连接)"
                                                     username (count @channels))))
                        :on-close (fn [ch _status]
                                    (swap! channels dissoc ch)
                                    (log/info (format "[ws] 客户端已断开: %s (当前 %d 个连接)"
                                                      username (count @channels))))
                        :on-receive (fn [_ch msg]
                                      ;; Client → server messages are currently ignored.
                                      ;; Reserved for future use (e.g., echo/ping, subscription filters).
                                      (log/debug "[ws] 收到客户端消息:" (str msg)))})))

;; ============================================================
;; Broadcast API
;; ============================================================

(defn broadcast!
  "Broadcasts a JSON event map to all connected WebSocket clients.
   event should be a Clojure map, e.g.:
     {:event \"refresh\" :panels [\"forward-tbody\" \"quota-bar\"]}"
  [event]
  (when (seq @channels)
    (let [msg (json/generate-string event)]
      (doseq [ch (keys @channels)]
        (try
          (server/send! ch msg)
          (catch Exception e
            (log/warn "[ws] 广播发送失败:" (.getMessage e))
            (swap! channels dissoc ch)))))))

;; ============================================================
;; Per-User Stats Broadcast
;; ============================================================

(defn- compute-user-stats
  "Computes container stats for a specific user.
   Returns a map of {container-id {:cpu-pct ... :mem-pct ... :mem-used ... :mem-limit ...}}"
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
      (log/warn "[ws] 计算容器 stats 失败:" (.getMessage e))
      {})))

(defn- compute-smolvm-stats
  "Computes basic status info for running smolvm machines.
   Note: smolvm has no native stats API (no docker stats equivalent).
   Returns configured resource limits, not live usage."
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
      (log/warn "[ws] 计算 smolvm stats 失败:" (.getMessage e))
      {})))

(defn broadcast-stats!
  "Sends per-user container + VM stats to each connected WebSocket client."
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
            (server/send! ch (json/generate-string {:event "stats" :stats all-stats}))))
        (catch Exception e
          (log/warn "[ws] stats 推送失败:" (.getMessage e))
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
        (log/info "[ws] Stats 推送线程已启动 (每 5 秒)")))
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
      (log/info "[ws] Stats 推送线程已停止"))
    nil))
