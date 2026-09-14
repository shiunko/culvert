(ns culvert.probe
  "TCP 延迟探针：对已经配置的转发/反向代理内部目标做连接计时，结果写入带 TTL 的缓存，
   供视图只读。只探测调用方显式登记的目标，不对请求中的任意 host 发起连接；
   探针失败不改变资源状态，仅作为可达性与延迟的补充信号。"
  (:require [clojure.string :as str]
            [culvert.config :refer [config]]))

;; 目标缓存：key 为 "host:port"，value 为 `{:ok? bool :latency-ms int|nil :checked-at ms}`。
(defonce cache (atom {}))

(defn cache-key [host port]
  (str host ":" port))

(defn parse-target
  "解析内部目标字符串为 `[host port]`，支持 `host:port`、`http(s)://host:port`。
   无法解析出端口时返回 nil。纯函数，便于单测。"
  [target]
  (when (string? target)
    (let [t (-> target str/trim (str/replace #"^[a-zA-Z]+://" ""))
          idx (str/last-index-of t ":")]
      (when (and idx (pos? idx) (< (inc idx) (count t)))
        (let [host (subs t 0 idx)
              port-str (subs t (inc idx))
              slash (str/index-of port-str "/")
              port-str (if slash (subs port-str 0 slash) port-str)]
          (when (re-matches #"\d+" port-str)
            [host (Integer/parseInt port-str)]))))))

(defn probe-once
  "尝试与 `host:port` 建立 TCP 连接并计时。成功返回 `{:ok? true :latency-ms n}`，
   超时、拒绝或解析失败返回 `{:ok? false :latency-ms nil}`。不抛异常。"
  ([host port] (probe-once host port 300))
  ([host port timeout-ms]
   (let [start (System/nanoTime)]
     (try
       (with-open [sock (java.net.Socket.)]
         (.connect sock (java.net.InetSocketAddress. ^String (str host) (int port)) (int timeout-ms))
         {:ok? true :latency-ms (int (/ (- (System/nanoTime) start) 1000000))})
       (catch Exception _
         {:ok? false :latency-ms nil})))))

(defn refresh-one!
  "探测单个目标并写入缓存，返回写入的条目。"
  [host port timeout-ms]
  (let [entry (assoc (probe-once host port timeout-ms) :checked-at (System/currentTimeMillis))]
    (swap! cache assoc (cache-key host port) entry)
    entry))

(defn cached
  "读取缓存条目；未探测过返回 nil。"
  [host port]
  (get @cache (cache-key host port)))

(defn enabled?
  "探针是否启用，由 `config` 的 `:probe-enabled?` 决定。"
  []
  (boolean (:probe-enabled? config)))

(defn- sample-loop
  [targets-fn running? interval-ms timeout-ms on-result]
  (while @running?
    (try
      (doseq [[host port] (distinct (targets-fn))]
        (when (and host port)
          (let [entry (refresh-one! host port timeout-ms)]
            (when on-result
              (on-result host port entry)))))
      (catch Throwable _ nil))
    (when @running?
      (try
        (Thread/sleep (long interval-ms))
        (catch InterruptedException _ (reset! running? false))))))

(defn start-sampler!
  "启动后台采样线程。`targets-fn` 返回 `[host port]` 序列；`on-result`（可选）会在每个目标
   探测完成后以 `(on-result host port entry)` 被调用，供组合根记录状态迁移。未启用时返回 nil。
   返回用于停止的 handle。"
  ([targets-fn] (start-sampler! targets-fn nil))
  ([targets-fn on-result]
   (when (enabled?)
     (let [running? (atom true)
           interval-ms (long (or (:probe-interval-ms config) 15000))
           timeout-ms (long (or (:probe-timeout-ms config) 300))
           thread (doto (Thread. #(sample-loop targets-fn running? interval-ms timeout-ms on-result))
                    (.setDaemon true)
                    (.setName "culvert-probe-sampler")
                    (.start))]
       {:running? running? :thread thread}))))

(defn stop-sampler!
  "停止采样线程并中断其等待。"
  [handle]
  (when handle
    (reset! (:running? handle) false)
    (when-let [t (:thread handle)]
      (.interrupt ^Thread t))))
