(ns culvert.s3
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [culvert.config :as config]
            [culvert.i18n :as i18n]
            [culvert.log :as log]
            [culvert.runtime.process :as process]))

;; ============================================================
;; S3 存储 FUSE 挂载管理器（goofys/rustfs）
;; ============================================================

(defonce state (atom {})) ; containerName -> mountState

(defn- current-config []
  (:s3 config/config))

(defn available? []
  (let [s3-config (current-config)]
    (and (:enabled s3-config)
         (let [f (io/file (:fs-path s3-config))]
           (and (.exists f) (.canExecute f))))))

;; --- 辅助函数：检查路径当前是否为 FUSE 挂载 ---

(defn- mounted? [s3-config mount-point]
  (try
    (let [mounts (slurp "/proc/mounts")]
      (boolean
       (some (fn [line]
               (and (str/starts-with? line (str (:fs-type s3-config) " "))
                    (.contains line mount-point)))
             (str/split-lines mounts))))
    (catch Exception _
      (try
        (let [res (process/run ["mount"])]
          (and (zero? (:exit res))
               (.contains (:out res) mount-point)))
        (catch Exception _
          false)))))

(defn- wait-mount-ready [s3-config mount-point timeout-sec]
  (let [deadline (+ (System/currentTimeMillis) (* timeout-sec 1000))]
    (loop []
      (cond
        (mounted? s3-config mount-point) true
        (>= (System/currentTimeMillis) deadline) false
        :else
        (do
          (Thread/sleep 200)
          (recur))))))

(defn- cleanup-failure [stage message]
  {:stage stage :message message})

(defn- force-cleanup [s3-config container-name mount-point proc]
  (let [failures (atom [])]
    (when proc
      (try
        (process/destroy! proc)
        (catch Exception e
          (swap! failures conj (cleanup-failure :destroy (.getMessage e))))))
    (when (mounted? s3-config mount-point)
      (try
        (let [result (process/run ["fusermount" "-uz" mount-point])]
          (when-not (zero? (:exit result))
            (swap! failures conj
                   (cleanup-failure :unmount
                                    (format "fusermount 退出码为 %s" (:exit result))))))
        (catch Exception e
          (swap! failures conj (cleanup-failure :unmount (.getMessage e))))))
    (try
      (let [f (io/file mount-point)]
        (when (and (.exists f) (not (.delete f)))
          (swap! failures conj (cleanup-failure :delete "无法删除挂载目录"))))
      (catch Exception e
        (swap! failures conj (cleanup-failure :delete (.getMessage e)))))
    (if (seq @failures)
      (throw (ex-info (format "S3 挂载清理失败: %s" container-name)
                      {:type ::cleanup-failed
                       :container-name container-name
                       :mount-point mount-point
                       :failures @failures}))
      (swap! state dissoc container-name))))

;; --- 挂载进程创建 ---

(defn- spawn-goofys [s3-config container-name mount-point user-s3-config]
  (let [opts (:default-options s3-config)
        args (cond-> []
               (seq (:endpoint user-s3-config)) (conj "--endpoint" (:endpoint user-s3-config))
               (seq (:region user-s3-config)) (conj "--region" (:region user-s3-config))
               true (conj "--dir-mode" (:dir-mode opts)
                          "--file-mode" (:file-mode opts)
                          "--uid" (:uid opts)
                          "--gid" (:gid opts)
                          (:bucket user-s3-config)
                          mount-point))
        env {"AWS_ACCESS_KEY_ID" (:accessKey user-s3-config)
             "AWS_SECRET_ACCESS_KEY" (:secretKey user-s3-config)}
        full-cmd (into [(:fs-path s3-config)] args)]

    (log/info (format "%s: %s" container-name (str/join " " (map str (butlast full-cmd)))))
    (let [proc (process/spawn full-cmd {:env env :out :inherit :err :inherit})]
      proc)))

;; --- 公共 API ---

(defn mount! [container-name user-s3-config]
  (let [s3-config (current-config)]
    (if-not (:enabled s3-config)
      {:mountPoint nil :status "disabled"}
      (if (or (str/blank? (:bucket user-s3-config))
              (str/blank? (:accessKey user-s3-config))
              (str/blank? (:secretKey user-s3-config)))
        {:mountPoint nil :status "disabled"}
        (let [mount-point (str/trim (str (io/file (:host-mount-root s3-config) container-name)))
              existing (get @state container-name)]
          (if (and existing
                   (= (:status existing) "mounted")
                   (mounted? s3-config mount-point))
            (do
              (log/info (format "%s 已挂载，复用" container-name))
              {:mountPoint mount-point :status "mounted"})
            (do
              (when existing
                (force-cleanup s3-config container-name mount-point (:proc existing)))

              ;; 创建目录
              (try
                (.mkdirs (io/file (:host-mount-root s3-config)))
                (.mkdirs (io/file mount-point))
                (catch Exception _))

              (log/info (format "挂载: %s → s3://%s @ %s" container-name (:bucket user-s3-config) mount-point))
              (let [proc (try
                           (spawn-goofys s3-config container-name mount-point user-s3-config)
                           (catch Exception e
                             (force-cleanup s3-config container-name mount-point nil)
                             (throw (ex-info "S3 挂载子进程创建失败"
                                             {:type ::mount-spawn-failed
                                              :container-name container-name}
                                             e))))
                    ready (wait-mount-ready s3-config mount-point (:mount-timeout-sec s3-config))]
                (if ready
                  (do
                    (swap! state assoc container-name
                           {:mountPoint mount-point
                            :proc proc
                            :status "mounted"
                            :s3Config {:endpoint (or (:endpoint user-s3-config) "")
                                       :bucket (:bucket user-s3-config)
                                       :region (or (:region user-s3-config) "")}
                            :mountedAt (.toString (java.time.Instant/now))})
                    (log/info (format "挂载成功: %s" container-name))
                    {:mountPoint mount-point :status "mounted"})
                  (do
                    (force-cleanup s3-config container-name mount-point proc)
                    (throw (ex-info (format "S3 挂载超时 (%ds): s3://%s → %s"
                                            (:mount-timeout-sec s3-config)
                                            (:bucket user-s3-config)
                                            mount-point)
                                    {:type ::mount-timeout
                                     :container-name container-name
                                     :mount-point mount-point}))))))))))))

(defn unmount! [container-name]
  (when-let [entry (get @state container-name)]
    (let [s3-config (current-config)]
      (log/info (format "卸载: %s" container-name))
      (force-cleanup s3-config container-name (:mountPoint entry) (:proc entry))
      (log/info (format "已卸载: %s" container-name)))))

(defn test-connection [user-s3-config]
  (let [s3-config (current-config)]
    (if-not (and (:enabled s3-config)
                 (let [f (io/file (:fs-path s3-config))]
                   (and (.exists f) (.canExecute f))))
      {:success false :message (:unavailable (:s3 i18n/strings))}
      (if (or (str/blank? (:bucket user-s3-config))
              (str/blank? (:accessKey user-s3-config))
              (str/blank? (:secretKey user-s3-config)))
        {:success false :message (:fill-credentials (:s3 i18n/strings))}
        (let [test-name (str "s3-test-" (System/currentTimeMillis) "-" (int (rand 10000)))
              test-mount-point (str/trim (str (io/file (:host-mount-root s3-config) test-name)))
              test-timeout 10]
          (log/info (format "测试连接: s3://%s%s" (:bucket user-s3-config)
                            (if (seq (:endpoint user-s3-config)) (str " @ " (:endpoint user-s3-config)) "")))

          (try
            (.mkdirs (io/file (:host-mount-root s3-config)))
            (.mkdirs (io/file test-mount-point))
            (catch Exception _))

          (let [proc (try
                       (spawn-goofys s3-config test-name test-mount-point user-s3-config)
                       (catch Exception e
                         (force-cleanup s3-config test-name test-mount-point nil)
                         (throw e)))]
            (try
              (let [ready (wait-mount-ready s3-config test-mount-point test-timeout)]
                (force-cleanup s3-config test-name test-mount-point proc)
                (if ready
                  (do
                    (log/info (format "连接测试成功: s3://%s" (:bucket user-s3-config)))
                    {:success true
                     :message ((:conn-success (:s3 i18n/strings)) {:bucket (:bucket user-s3-config) :endpoint (:endpoint user-s3-config)})})
                  {:success false
                   :message ((:conn-timeout (:s3 i18n/strings)) {:timeout test-timeout :bucket (:bucket user-s3-config)})}))
              (catch Exception e
                (force-cleanup s3-config test-name test-mount-point proc)
                {:success false :message ((:conn-failure (:s3 i18n/strings)) (.getMessage e))}))))))))

(defn get-status [container-name]
  (let [s3-config (current-config)
        entry (get @state container-name)]
    (if-not entry
      {:mountPoint nil :status "unmounted"}
      (let [mount-point (:mountPoint entry)
            current-mounted (mounted? s3-config mount-point)
            status (if (and (= (:status entry) "mounted") (not current-mounted))
                     "error"
                     (:status entry))]
        {:mountPoint mount-point
         :status status
         :s3Config (:s3Config entry)}))))

(defn get-all-mounts []
  (map (fn [[name entry]]
         (assoc entry :containerName name))
       @state))

;; --- 生命周期钩子 ---

(defn sync-mounts! [containers]
  (let [s3-config (current-config)]
    (when (:enabled s3-config)
      (log/info "同步挂载状态...")
      (let [active-mounts (atom {})
            failures (atom [])]
        (try
          (let [root-dir (io/file (:host-mount-root s3-config))
                files (when (.exists root-dir) (.listFiles root-dir))]
            (doseq [f files]
              (when (.isDirectory f)
                (let [mount-point (.getAbsolutePath f)
                      container-name (.getName f)]
                  (when (mounted? s3-config mount-point)
                    (swap! active-mounts assoc container-name mount-point))))))
          (catch Exception e
            (throw (ex-info "无法检查 S3 挂载状态"
                            {:type ::startup-recovery-failed
                             :failures [{:stage :inspect-mounts
                                         :message (.getMessage e)}]}
                            e))))

        (doseq [[_id container] containers]
          (let [container-name (:name container)
                persisted (:s3Mount container)]
            (when (and container-name
                       (:enabled persisted)
                       (= (:status persisted) "mounted"))
              (let [active-mount-point (get @active-mounts container-name)
                    persisted-mount-point (:mountPoint persisted)]
                (if (and active-mount-point
                         persisted-mount-point
                         (= (.getAbsolutePath (io/file active-mount-point))
                            (.getAbsolutePath (io/file persisted-mount-point))))
                  (do
                    (swap! state assoc container-name
                           {:mountPoint active-mount-point
                            :proc nil
                            :status "mounted"
                            :s3Config {:endpoint (or (:endpoint persisted) "")
                                       :bucket (or (:bucket persisted) "")
                                       :region (or (:region persisted) "")}
                            :mountedAt (or (:mountedAt persisted)
                                           (.toString (java.time.Instant/now)))})
                    (swap! active-mounts dissoc container-name)
                    (log/info (format "恢复已挂载状态: %s" container-name)))
                  (do
                    (log/warn (format "预期挂载缺失: %s" container-name))
                    (swap! failures conj
                           {:stage :verify-persisted-mount
                            :container-name container-name
                            :mount-point persisted-mount-point
                            :message "持久记录声明已挂载，但操作系统挂载缺失"})))))))

        (doseq [[container-name mount-point] @active-mounts]
          (log/warn (format "发现来源不明的 S3 挂载，仅报告且不清理: %s @ %s"
                            container-name mount-point)))

        (when (seq @failures)
          (throw (ex-info "S3 启动恢复失败"
                          {:type ::startup-recovery-failed
                           :failures @failures})))

        (log/info (format "同步完成: %d 个活跃挂载" (count @state)))))))

(defn shutdown! []
  (when (:enabled (current-config))
    (println "[s3-shutdown] 正在清理所有 S3 挂载...")
    (doseq [name (keys @state)]
      (unmount! name))))
