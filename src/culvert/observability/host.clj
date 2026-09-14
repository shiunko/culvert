(ns culvert.observability.host
  "本机（单节点）主机指标：纯解析函数与边界 I/O 分离，读取失败一律降级为 nil，
   绝不伪造数字。生产 Linux 内存完整；非 Linux 内存项返回 nil 由视图显示为“—”。
   结果带 5 秒 TTL 缓存，视图只读快照。"
  (:require [clojure.string :as str]
            [culvert.config :refer [config]]))

(def cache-ttl-ms 5000)

(defonce ^:private cache (atom nil))

(def ^:private started-at-ms (System/currentTimeMillis))

;; ============================================================
;; 纯解析（可直接单测）
;; ============================================================

(defn parse-meminfo
  "解析 Linux /proc/meminfo 文本，返回 `{:total-kb :available-kb :used-kb :used-pct}`；
   缺少 MemTotal 时返回 nil。MemAvailable 缺失时退回 MemFree。"
  [text]
  (when (string? text)
    (let [field (fn [name]
                  (some->> (re-find (re-pattern (str "(?m)^" name ":\\s+(\\d+)\\s+kB")) text)
                           second
                           Long/parseLong))
          total (field "MemTotal")
          available (or (field "MemAvailable") (field "MemFree"))]
      (when (and total (pos? total))
        (let [available (or available 0)
              used (max 0 (- total available))]
          {:total-kb total
           :available-kb available
           :used-kb used
           :used-pct (long (Math/round (* 100.0 (/ (double used) (double total)))))})))))

(defn parse-loadavg
  "解析 Linux /proc/loadavg 文本，返回 1 分钟平均负载（double）；无法解析返回 nil。"
  [text]
  (when (string? text)
    (when-let [token (first (remove (fn [s] (empty? s)) (str/split (str/trim text) #"\s+")))]
      (try (Double/parseDouble token) (catch Exception _ nil)))))

;; ============================================================
;; 边界 I/O
;; ============================================================

(defn- read-meminfo []
  (try
    (slurp "/proc/meminfo")
    (catch Exception _ nil)))

(defn- jvm-load-average
  "仅 JVM 可用：通过反射读取 OperatingSystemMXBean（Babashka 不含 java.lang.management，返回 nil）。"
  []
  (try
    (if-let [factory (resolve 'java.lang.management.ManagementFactory)]
      (let [bean (.invoke (.getMethod ^Class factory "getOperatingSystemMXBean" (make-array Class 0))
                          factory (object-array 0))
            load (.invoke (.getMethod (class bean) "getSystemLoadAverage" (make-array Class 0))
                          bean (object-array 0))]
        (when (and (number? load) (not (neg? (double load)))) (double load)))
      nil)
    (catch Throwable _ nil)))

(defn- system-load-average []
  (or (parse-loadavg (try (slurp "/proc/loadavg") (catch Exception _ nil)))
      (jvm-load-average)))

(defn- available-processors []
  (try (.availableProcessors (Runtime/getRuntime)) (catch Exception _ nil)))

(defn- disk-usage [path]
  (try
    (let [file (java.io.File. (str path))
          total (.getTotalSpace file)
          usable (.getUsableSpace file)]
      (when (pos? total)
        (let [used (- total usable)]
          {:total-bytes total
           :usable-bytes usable
           :used-bytes used
           :used-pct (long (Math/round (* 100.0 (/ (double used) (double total)))))})))
    (catch Exception _ nil)))

(defn- uptime-seconds []
  (quot (- (System/currentTimeMillis) started-at-ms) 1000))

(defn- cpu-metrics []
  (let [load (system-load-average)
        cores (available-processors)]
    {:load-average load
     :cores cores
     :used-pct (when (and load cores (pos? cores))
                 (long (min 100 (Math/round (* 100.0 (/ load cores))))))}))

(defn- compute []
  {:cpu (cpu-metrics)
   :memory (parse-meminfo (read-meminfo))
   :disk (disk-usage (:data-dir config))
   :uptime-seconds (uptime-seconds)
   :platform (str (System/getProperty "os.name") " "
                  (System/getProperty "os.arch"))
   :checked-at (System/currentTimeMillis)})

;; ============================================================
;; 快照（TTL 缓存）
;; ============================================================

(defn snapshot
  "返回主机指标快照；缓存未过期时直接返回缓存，否则重新计算并写入。
   计算失败返回 nil，由视图降级，不抛异常、不阻塞请求线程之外的行为。"
  []
  (let [now (System/currentTimeMillis)
        cached @cache]
    (if (and cached (< (- now (:at cached)) cache-ttl-ms))
      (:data cached)
      (try
        (let [data (compute)]
          (reset! cache {:at now :data data})
          data)
        (catch Exception _
          nil)))))

(defn refresh!
  "丢弃缓存并立即重算，返回新快照。"
  []
  (reset! cache nil)
  (snapshot))

(defn clear-cache!
  "清空缓存，仅供测试。"
  []
  (reset! cache nil))
