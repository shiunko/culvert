(ns culvert.runtime.process
  "兼容 Babashka/JVM 的进程适配器。

  命令始终使用 argv 向量且不经过 shell。`run` 等待命令结束并返回命令、
  耗时、退出码、标准输出和标准错误；默认返回非零退出码，`:check?` 为
  true 时改为抛出 `ExceptionInfo`。`spawn` 返回用于生命周期操作的不透明句柄。"
  (:require [babashka.process :as process]))

(defn- argv! [argv]
  (when-not (and (vector? argv) (seq argv))
    (throw (IllegalArgumentException. "process argv must be a non-empty vector")))
  (mapv str argv))

(defn- failed! [argv result]
  (throw (ex-info (format "process exited with status %d: %s"
                          (:exit result)
                          (pr-str argv))
                  (assoc result :argv argv))))

(def ^:private default-grace-timeout-ms 1000)
(def ^:private default-force-timeout-ms 1000)
(def ^:private wait-poll-ms 10)

(defn- duration-ms [started-at]
  (quot (- (System/nanoTime) started-at) 1000000))

(defn- timeout! [option value]
  (when-not (and (integer? value) (not (neg? value)))
    (throw (IllegalArgumentException.
            (format "%s must be a non-negative integer" (name option)))))
  value)

(defn- process-handles [handle]
  (when handle
    (let [root (.toHandle ^Process (:proc handle))]
      (vec (cons root (.toArray (.descendants root)))))))

(defn- live-handles [handles]
  (filterv #(.isAlive %) handles))

(defn- wait-until-dead [handles timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop [remaining (live-handles handles)]
      (if (or (empty? remaining)
              (>= (System/nanoTime) deadline))
        remaining
        (do
          (Thread/sleep (long (min wait-poll-ms
                                   (max 1 (quot (- deadline (System/nanoTime))
                                                1000000)))))
          (recur (live-handles remaining)))))))

(defn- signal! [handles force?]
  (doseq [process-handle handles]
    (when (.isAlive process-handle)
      (if force?
        (.destroyForcibly process-handle)
        (.destroy process-handle)))))

(defn destroy!
  "停止受管进程及调用时已存在的全部后代。

  先发送温和终止信号并等待 `:grace-timeout-ms`，再强制终止剩余进程并
  等待 `:force-timeout-ms`。nil 与已退出句柄均可重复调用。成功返回结构化
  结果；最终仍存活时抛出类型为 `::termination-timeout` 的 `ExceptionInfo`。"
  ([handle] (destroy! handle {}))
  ([handle {:keys [grace-timeout-ms force-timeout-ms]
            :or {grace-timeout-ms default-grace-timeout-ms
                 force-timeout-ms default-force-timeout-ms}}]
   (let [grace-timeout-ms (timeout! :grace-timeout-ms grace-timeout-ms)
         force-timeout-ms (timeout! :force-timeout-ms force-timeout-ms)
         started-at (System/nanoTime)
         handles (or (process-handles handle) [])
         root-pid (some-> handles first .pid)
         initial (live-handles handles)
         initial-pids (mapv #(.pid %) initial)]
     (signal! initial false)
     (let [after-grace (wait-until-dead initial grace-timeout-ms)
           forced-pids (mapv #(.pid %) after-grace)]
       (signal! after-grace true)
       (let [remaining (wait-until-dead after-grace force-timeout-ms)
             remaining-pids (mapv #(.pid %) remaining)
             result {:pid root-pid
                     :pids initial-pids
                     :forced-pids forced-pids
                     :remaining-pids remaining-pids
                     :grace-timeout-ms grace-timeout-ms
                     :force-timeout-ms force-timeout-ms
                     :duration-ms (duration-ms started-at)}]
         (if (seq remaining-pids)
           (throw (ex-info (format "process termination timed out: %s"
                                   (pr-str remaining-pids))
                           (assoc result :type ::termination-timeout)))
           result))))))

(defn run
  "同步运行 argv。可传入 babashka.process 接受的选项。

  默认把输出捕获为字符串。`:check? true` 在非零退出时抛出异常；
  `:timeout-ms` 到期后使用与 `destroy!` 相同的有界终止协议。"
  ([argv] (run argv {}))
  ([argv opts]
   (let [argv (argv! argv)
         check? (:check? opts)
         timeout-ms (:timeout-ms opts)
         termination-opts (select-keys opts [:grace-timeout-ms :force-timeout-ms])
         started-at (System/nanoTime)
         process-opts (merge {:out :string :err :string}
                             (dissoc opts
                                     :check?
                                     :timeout-ms
                                     :grace-timeout-ms
                                     :force-timeout-ms))
         handle (process/process argv process-opts)
         completed (if timeout-ms
                     (deref handle timeout-ms ::timeout)
                     @handle)]
     (when (= completed ::timeout)
       (let [elapsed (duration-ms started-at)
             data {:type ::timeout
                   :command argv
                   :timeout-ms timeout-ms
                   :duration-ms elapsed}]
         (try
           (destroy! handle termination-opts)
           (throw (ex-info (format "process timed out after %dms: %s"
                                   timeout-ms (pr-str argv))
                           data))
           (catch clojure.lang.ExceptionInfo error
             (if (= ::termination-timeout (:type (ex-data error)))
               (throw (ex-info (format "process timed out and could not be terminated: %s"
                                       (pr-str argv))
                               (assoc data :termination (ex-data error))
                               error))
               (throw error))))))
     (let [result (assoc (select-keys completed [:exit :out :err])
                         :command argv
                         :duration-ms (duration-ms started-at))]
       (if (and check? (not (zero? (:exit result))))
         (failed! argv result)
         result)))))

(defn spawn
  "启动 argv 但不等待。返回句柄以 map 项暴露配置的 `:out` 与 `:err`
  流，并且必须通过本命名空间管理其生命周期。"
  ([argv] (spawn argv {}))
  ([argv opts]
   (process/process (argv! argv) opts)))

(defn pid [handle]
  (.pid ^Process (:proc handle)))

(defn wait
  "等待已启动进程并返回数字退出状态。"
  [handle]
  (:exit @handle))

(defn alive? [handle]
  (boolean (and handle (process/alive? handle))))
