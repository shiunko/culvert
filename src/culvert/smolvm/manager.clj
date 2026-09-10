(ns culvert.smolvm.manager
  "MicroVM 生命周期状态管理，包括 atom、CRUD、端口暴露、ttyd、同步与关闭。"
  (:require [clojure.string :as str]
            [culvert.auth :as auth]
            [culvert.authorization :as authorization]
            [culvert.caddy :as caddy]
            [culvert.config :refer [config]]
            [culvert.db :as db]
            [culvert.i18n :as i18n]
            [culvert.log :as log]
            [culvert.runtime.process :as process]
            [culvert.smolvm.cli :as smolvm-cli])
  (:import [java.net ServerSocket]
           [java.security SecureRandom]))

;; ============================================================
;; SmolVM 管理器
;; ============================================================

(defonce state (atom {:machines {}   ; id -> VM 映射
                      :used-ports #{} ; 从 SmolVM 端口范围分配的端口
                      :ttyd-procs {}})) ; id -> {:pid pid :proc proc}

(def legacy-user (or (:auth-user config) "admin"))

(defn- require-mutations-enabled! []
  (when-not (:smolvm-mutations-enabled config false)
    (throw (ex-info "SmolVM 变更功能未启用"
                    {:type ::mutations-disabled}))))

;; ============================================================
;; 端口分配器
;; ============================================================

(defn- port-available? [port]
  (try
    (let [socket (ServerSocket. port)]
      (.close socket)
      true)
    (catch Exception _
      false)))

(defn allocate-port []
  (let [start (:smolvm-port-start config)
        end (:smolvm-port-end config)
        used (:used-ports @state)]
    (loop [port start]
      (if (> port end)
        (throw (Exception. (format "端口池耗尽 (%d-%d)，无法分配新端口" start end)))
        (if (and (not (contains? used port)) (port-available? port))
          (do
            (swap! state update :used-ports conj port)
            port)
          (recur (inc port)))))))

(defn release-port [port]
  (swap! state update :used-ports disj port))

;; ============================================================
;; 字符串生成器（沿用 docker.clj 的模式）
;; ============================================================

(defn- generate-token []
  (let [bytes (byte-array 4)
        sr (SecureRandom.)]
    (.nextBytes sr bytes)
    (let [hex (StringBuilder.)]
      (doseq [b bytes]
        (.append hex (format "%02x" b)))
      (.toString hex))))

(defn- generate-credentials []
  (let [bytes (byte-array 2)
        sr (SecureRandom.)]
    (.nextBytes sr bytes)
    (let [user (str "dev_" (let [h (StringBuilder.)]
                             (doseq [b bytes] (.append h (format "%02x" b)))
                             (.toString h)))
          chars "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
          pass (str/join "" (repeatedly 12 #(nth chars (.nextInt sr (count chars)))))]
      {:shellUser user :shellPass pass})))

(defn sanitize-machine-name [user-id image short-id]
  (let [safe-user (-> (str user-id)
                      (str/replace #"[^a-zA-Z0-9_-]" "_")
                      (subs 0 (min 32 (count (str user-id)))))
        image-slug (-> (str image)
                       (str/replace #"[/:.]" "-")
                       (str/replace #"[^a-zA-Z0-9_-]" "-")
                       (subs 0 (min 20 (count (str image)))))]
    (str "vm-" safe-user "-" image-slug "-" short-id)))

;; ============================================================
;; 内存辅助函数（沿用 docker.clj 的模式）
;; ============================================================

(defn parse-mem-bytes [mem-str]
  (let [m (re-find #"^(\d+\.?\d*)\s*(b|k|m|g)?$" (str/lower-case (str mem-str)))]
    (if-not m
      0.0
      (let [num (Double/parseDouble (nth m 1))
            unit (or (nth m 2) "b")
            mult {"b" 1.0
                  "k" 1024.0
                  "m" (* 1024.0 1024.0)
                  "g" (* 1024.0 1024.0 1024.0)}]
        (* num (get mult unit 1.0))))))

;; ============================================================
;; 数据库操作
;; ============================================================

(defn load-entries! []
  (let [data (db/read-json (:smolvm-db-path config) {:machines {}})
        machines (reduce (fn [acc [k m]]
                           (let [k-str (if (keyword? k) (name k) k)
                                 m-updated (cond-> m
                                             (nil? (:shell m)) (assoc :shell (get-in config [:smolvm-images-map (:image m) :entry] (:smolvm-default-shell config)))
                                             true (assoc :userId (or (:userId m) legacy-user)))]
                             (assoc acc k-str m-updated)))
                         {}
                         (:machines data {}))]
    (swap! state assoc :machines machines)
    (doseq [m (vals machines)]
      (when (:shellPort m)
        (swap! state update :used-ports conj (:shellPort m)))
      (doseq [[_port info] (:ports m)]
        (when (:smolvmPort info)
          (swap! state update :used-ports conj (:smolvmPort info)))))
    (log/info (format "[smolvm] 已加载 %d 个 VM 记录" (count (:machines @state))))))

(defn save-entries! []
  (db/write-json (:smolvm-db-path config) {:machines (:machines @state)}))

;; ============================================================
;; ttyd 进程控制
;; ============================================================

(defn stop-ttyd!
  ([machine]
   (stop-ttyd! machine false))
  ([machine strict?]
   (let [pid (:ttydPid machine)
         id (:id machine)]
     (try
       (when pid
         (let [entry (get-in @state [:ttyd-procs id])]
           (when entry
             (process/destroy! (:proc entry))))
         (log/info (format "[smolvm-ttyd] 已停止 pid=%d" pid)))
       (catch Exception e
         (when strict?
           (throw e)))
       (finally
         (swap! state update :ttyd-procs dissoc id)
         (swap! state assoc-in [:machines id :ttydPid] nil))))))

(defn start-ttyd! [machine]
  (when-not (:ttyd-enabled config false)
    (throw (ex-info "ttyd 功能未启用" {:type ::ttyd-disabled})))
  (let [id (:id machine)
        exec-args (smolvm-cli/get-ttyd-exec-args (:name machine) (:shell machine))
        args ["-i" "127.0.0.1"
              "-p" (str (:shellPort machine))
              "-c" (str (:shellUser machine) ":" (:shellPass machine))
              "-W"
              "--max-clients" (str (:ttyd-max-clients config))
              "--client-option" "fontSize=14"
              "--client-option" "theme=monokai"]
        proc (process/spawn (into [(:ttyd-path config)] (concat args exec-args))
                            {:out :inherit :err :inherit})
        pid (process/pid proc)]
    (swap! state assoc-in [:ttyd-procs id] {:pid pid :proc proc})
    (log/info (format "[smolvm-ttyd] 已启动 pid=%d port=%d" pid (:shellPort machine)))

    ;; 监视进程退出
    (let [watcher (Thread. (fn []
                             (try
                               (let [exit-code (process/wait proc)]
                                 (log/info (format "[smolvm-ttyd:%d] 已退出，退出码 %s" pid exit-code))
                                 (when (= (get-in @state [:ttyd-procs id :pid]) pid)
                                   (swap! state update :ttyd-procs dissoc id)
                                   (swap! state assoc-in [:machines id :ttydPid] nil)
                                   (swap! state assoc-in [:machines id :status] "error")
                                   (save-entries!)))
                               (catch InterruptedException _))))]
      (.setDaemon watcher true)
      (.start watcher))
    pid))

(defn ttyd-alive? [machine]
  (let [id (:id machine)
        entry (get-in @state [:ttyd-procs id])]
    (and entry (process/alive? (:proc entry)))))

;; ============================================================
;; Caddy 规则辅助函数（沿用 caddy.clj 的模式）
;; ============================================================

(defn- add-shell-caddy-rule [machine]
  (if (:ip-mode config)
    nil
    (let [domain (:caddy-domain config)
          worker (str "shell-" (:shellToken machine))
          target (str "http://127.0.0.1:" (:shellPort machine))]
      (caddy/add-managed-rule! domain worker target (str "smolvm-shell:" (:name machine)) (:userId machine))
      (caddy/build-key domain worker))))

(defn- add-port-caddy-rule [machine port-str smolvm-host-port remark]
  (if (:ip-mode config)
    ;; IP 模式：SmolVM -p 绑定 0.0.0.0，可直接访问
    {:caddyRuleId nil
     :domain (str (:public-ip config) ":" smolvm-host-port)}
    ;; 域名模式：Caddy 反向代理到 localhost
    (let [domain (:caddy-domain config)
          worker (str "p" port-str "-" (:shellToken machine))
          target (str "http://127.0.0.1:" smolvm-host-port)]
      (caddy/add-managed-rule! domain worker target (or remark (str "smolvm-port:" (:name machine) ":" port-str)) (:userId machine))
      {:caddyRuleId (caddy/build-key domain worker)
       :domain (str worker "." domain)})))

;; ============================================================
;; 查询 API
;; ============================================================

(defn count-by-user [user-id]
  (count (filter #(= (:userId %) user-id) (vals (:machines @state)))))

(defn to-public-machine [m]
  (let [shell-url (if (:ip-mode config)
                    (when (:shellPort m)
                      (format "http://%s:%d"
                              (:public-ip config)
                              (:shellPort m)))
                    (if (:caddyRuleId m)
                      (let [parsed (caddy/parse-key (:caddyRuleId m))]
                        (if parsed
                          (format "https://%s.%s"
                                  (:worker parsed) (:domain parsed))
                          ""))
                      ""))
        ports-public (reduce (fn [acc [port info]]
                               (assoc acc port
                                      {:domain (:domain info "")
                                       :enabled (:enabled info)
                                       :remark (:remark info "")
                                       :url (if (:ip-mode config)
                                              (when-let [sp (:smolvmPort info)]
                                                (str "http://" (:public-ip config) ":" sp))
                                              (when (:domain info) (str "https://" (:domain info))))}))
                             {}
                             (:ports m {}))]
    {:id (:id m)
     :name (:name m)
     :image (:image m)
     :userId (:userId m)
     :netEnabled (:netEnabled m)
     :shellToken (:shellToken m)
     :shellUser (:shellUser m)
     :shellPassMasked "●●●●●●●●●●●●"
     :shellUrl shell-url
     :shellPort (:shellPort m)
     :shell (:shell m)
     :createdAt (:createdAt m)
     :status (:status m)
     :cpuLimit (:cpuLimit m)
     :memLimit (:memLimit m)
     :gpu (:gpu m false)
     :sshAgent (:sshAgent m false)
     :ports ports-public}))

(defn get-machines
  ([actor]
   (authorization/require-actor! actor)
   (->> (vals (:machines @state))
        (filter #(authorization/authorized? actor (:userId %) legacy-user))
        (map to-public-machine)
        (sort-by :createdAt #(compare %2 %1))))
  ([user-id is-admin]
   (get-machines (authorization/actor user-id is-admin))))

(defn get-machine
  ([id actor]
   (authorization/require-actor! actor)
   (let [m (get-in @state [:machines id])]
     (when (and m (authorization/authorized? actor (:userId m) legacy-user))
       (to-public-machine m))))
  ([id user-id is-admin]
   (get-machine id (authorization/actor user-id is-admin))))

(defn get-shell-pass
  ([id actor]
   (authorization/require-actor! actor)
   (let [m (get-in @state [:machines id])]
     (when (and m (authorization/authorized? actor (:userId m) legacy-user))
       (:shellPass m))))
  ([id user-id is-admin]
   (get-shell-pass id (authorization/actor user-id is-admin))))

;; ============================================================
;; VM 生命周期
;; ============================================================

(defn- require-machine-access! [id actor]
  (authorization/require-actor! actor)
  (let [m (get-in @state [:machines id])]
    (when-not m
      (throw (Exception. (:machine-not-found (:validation i18n/strings)))))
    (authorization/require-resource-access!
     actor (:userId m) legacy-user
     (:no-permission-machine (:validation i18n/strings)))
    m))

(defn create-machine!
  "创建新的 MicroVM。opts 参数：
   :image       — OCI 镜像字符串
   :cpu-limit   — vCPU 数量字符串
   :mem-limit   — MiB 内存字符串
   :net?        — 是否启用网络
   :gpu?        — 是否启用 GPU
   :ssh-agent?  — 是否转发 SSH agent
   :volumes     — {:host PATH :guest PATH :ro BOOLEAN} 向量
   :allow-hosts — 主机名字符串向量
   :allow-cidrs — CIDR 字符串向量"
  [user-id image cpu-limit mem-limit opts]
  (require-mutations-enabled!)
  ;; 1. 镜像白名单
  (when (seq (:smolvm-allowed-images config))
    (when-not (some #(= image %) (:smolvm-allowed-images config))
      (throw (Exception. ((:smolvm-image-not-allowed (:validation i18n/strings))
                          {:image image :allowed (str/join ", " (:smolvm-allowed-images config))})))))

  ;; 2. 资源限制校验
  (let [cpu-str (str (or cpu-limit (:smolvm-default-cpu config)))
        mem-str (str (or mem-limit (:smolvm-default-mem config)))]
    ;; 3. 用户配额
    (when user-id
      (let [quotas (auth/get-user-quotas user-id)]
        (when (>= (count-by-user user-id) (get quotas :smolvmMachines 2))
          (throw (Exception. ((:machine-quota-exhausted (:validation i18n/strings))
                              {:used (count-by-user user-id) :limit (get quotas :smolvmMachines 2)}))))))

    ;; 4. 准备 VM 属性
    (let [shell (get-in config [:smolvm-images-map image :entry] (:smolvm-default-shell config))
          shell-token (generate-token)
          creds (generate-credentials)
          shell-port (allocate-port)
          short-id (generate-token)
          m-name (sanitize-machine-name user-id image short-id)
          ;; 构建端口映射：ttyd 通过 smolvm machine exec 访问 shell，
          ;; 无需预先添加 -p；仅在用户后续暴露端口时添加映射
          vol-args (mapv (fn [v]
                           (let [base (str (:host v) ":" (:guest v))]
                             (if (:ro v) (str base ":ro") base)))
                         (or (:volumes opts) []))
          create-opts {:image image
                       :name m-name
                       :net (or (:net? opts) false)
                       :gpu (or (:gpu? opts) false)
                       :volumes vol-args
                       :ports []
                       :ssh-agent (or (:ssh-agent? opts) false)
                       :allow-hosts (or (:allow-hosts opts) [])
                       :allow-cidrs (or (:allow-cidrs opts) [])}]

      ;; 5. 通过 SmolVM CLI 创建 VM
      (smolvm-cli/create-machine! create-opts)

      ;; 6. 启动 VM
      (try
        (smolvm-cli/start-machine! m-name)
        (catch Exception e
          (try (smolvm-cli/delete-machine! m-name) (catch Exception _))
          (release-port shell-port)
          (throw e)))

      ;; 7. 构建 VM 记录
      (let [machine {:id short-id
                     :name m-name
                     :image image
                     :userId user-id
                     :netEnabled (:net? opts false)
                     :sshAgent (:ssh-agent? opts false)
                     :allowHosts (or (:allow-hosts opts) [])
                     :allowCidrs (or (:allow-cidrs opts) [])
                     :volumes (or (:volumes opts) [])
                     :ports {}
                     :shellToken shell-token
                     :shellUser (:shellUser creds)
                     :shellPass (:shellPass creds)
                     :shellPort shell-port
                     :shell shell
                     :ttydPid nil
                     :caddyRuleId nil
                     :createdAt (.toString (java.time.Instant/now))
                     :status "running"
                     :cpuLimit cpu-str
                     :memLimit mem-str
                     :gpu (or (:gpu? opts) false)}]

        ;; 8. 启动 ttyd
        (try
          (let [ttyd-pid (start-ttyd! machine)
                machine (assoc machine :ttydPid ttyd-pid)
                ;; 9. 注册 Caddy shell 规则
                caddy-rule-id (add-shell-caddy-rule machine)
                machine (assoc machine :caddyRuleId caddy-rule-id)]
            ;; 10. 持久化
            (swap! state assoc-in [:machines short-id] machine)
            (save-entries!)
            (to-public-machine machine))
          (catch Exception e
            ;; 回滚：删除 VM
            (try (smolvm-cli/delete-machine! m-name) (catch Exception _))
            (release-port shell-port)
            (throw e)))))))

(defn kill-machine!
  ([id actor]
   (require-mutations-enabled!)
   (let [m (require-machine-access! id actor)]

     (log/info (format "[smolvm] 销毁 VM: %s" (:name m)))

    ;; 1. 停止 ttyd
     (stop-ttyd! m)

    ;; 2. 删除 Caddy shell 规则
     (when (:caddyRuleId m)
       (try
         (let [parsed (caddy/parse-key (:caddyRuleId m))]
           (when parsed (caddy/remove-rule! (:domain parsed) (:worker parsed) nil true)))
         (catch Exception e
           (log/warn (format "[smolvm] ⚠️ 移除 Shell Caddy 规则失败: %s" (.getMessage e))))))

    ;; 3. 删除 Caddy 端口规则
     (doseq [[_port info] (:ports m)]
       (when (:caddyRuleId info)
         (try
           (let [parsed (caddy/parse-key (:caddyRuleId info))]
             (when parsed (caddy/remove-rule! (:domain parsed) (:worker parsed) nil true)))
           (catch Exception e
             (log/warn (format "[smolvm] ⚠️ 移除端口 Caddy 规则失败: %s" (.getMessage e)))))))

    ;; 4. 通过 SmolVM 删除 VM
     (try
       (smolvm-cli/delete-machine! (:name m))
       (catch Exception _))

    ;; 5. 释放端口并清理状态
     (release-port (:shellPort m))
     (doseq [[_port info] (:ports m)]
       (when (:smolvmPort info)
         (release-port (:smolvmPort info))))
     (swap! state update :machines dissoc id)
     (save-entries!)))
  ([id user-id is-admin]
   (kill-machine! id (authorization/actor user-id is-admin))))

(defn start-machine!
  ([id actor]
   (require-mutations-enabled!)
   (let [m (require-machine-access! id actor)]
     (when (= (:status m) "running")
       (throw (Exception. (:machine-already-running (:validation i18n/strings)))))

     (smolvm-cli/start-machine! (:name m))
     (let [ttyd-pid (start-ttyd! m)
           caddy-rule-id (if (:caddyRuleId m) (:caddyRuleId m) (add-shell-caddy-rule m))]
       (swap! state assoc-in [:machines id :ttydPid] ttyd-pid)
       (swap! state assoc-in [:machines id :caddyRuleId] caddy-rule-id)
       (swap! state assoc-in [:machines id :status] "running")
       (save-entries!)
       (log/info (format "[smolvm] 已启动 VM: %s" (:name m)))
       (to-public-machine (get-in @state [:machines id])))))
  ([id user-id is-admin]
   (start-machine! id (authorization/actor user-id is-admin))))

(defn stop-machine!
  ([id actor]
   (require-mutations-enabled!)
   (let [m (require-machine-access! id actor)]
     (when (= (:status m) "stopped")
       (throw (Exception. (:machine-already-stopped (:validation i18n/strings)))))

    ;; 先停止 ttyd
     (stop-ttyd! m)
    ;; 再通过 SmolVM 停止 VM
     (smolvm-cli/stop-machine! (:name m))
     (swap! state assoc-in [:machines id :status] "stopped")
     (save-entries!)
     (log/info (format "[smolvm] 已停止 VM: %s" (:name m)))
     (to-public-machine (get-in @state [:machines id]))))
  ([id user-id is-admin]
   (stop-machine! id (authorization/actor user-id is-admin))))

;; ============================================================
;; 端口暴露（沿用 caddy.clj 的模式）
;; ============================================================

(defn expose-port!
  ([id port remark]
   (expose-port! id port remark nil))
  ([id port remark actor]
   (require-mutations-enabled!)
   (let [m (require-machine-access! id actor)
         port-str (str/trim (str port))]
     (when-not m (throw (Exception. (:machine-not-found (:validation i18n/strings)))))
     (when-not (re-matches #"^\d{1,5}$" port-str)
       (throw (Exception. (:port-range-invalid (:validation i18n/strings)))))
     (let [p-val (Integer/parseInt port-str)]
       (when (or (< p-val 1) (> p-val 65535))
         (throw (Exception. (:port-range-invalid (:validation i18n/strings)))))
       (when (get-in m [:ports port-str])
         (throw (Exception. ((:port-already-exposed (:validation i18n/strings)) port-str))))

      ;; 分配 SmolVM 宿主机端口
       (let [smolvm-host-port (allocate-port)]
         (try
          ;; 如果 VM 正在运行则先停止
           (when (= (:status m) "running")
             (smolvm-cli/stop-machine! (:name m)))

          ;; 重新构建端口映射：已有端口加新端口
           (let [new-ports (assoc (:ports m) port-str {:smolvmPort smolvm-host-port
                                                       :caddyRuleId nil
                                                       :domain nil
                                                       :enabled true
                                                       :remark (or remark "")})
                 port-args (mapv (fn [[p info]]
                                   (str (:smolvmPort info) ":" p))
                                 new-ports)]
             (smolvm-cli/update-machine! (:name m) {:ports port-args}))

          ;; 重启 VM
           (smolvm-cli/start-machine! (:name m))

          ;; 域名模式添加 Caddy 规则，IP 模式记录直连地址
           (let [res (add-port-caddy-rule m port-str smolvm-host-port remark)]
             (swap! state assoc-in [:machines id :ports port-str]
                    {:smolvmPort smolvm-host-port
                     :caddyRuleId (:caddyRuleId res)
                     :domain (:domain res)
                     :enabled true
                     :remark (or remark "")})
             (swap! state assoc-in [:machines id :status] "running")
             (save-entries!)
             (log/info (format "[smolvm] VM 端口已暴露: %s:%s → %s" (:name m) port-str (:domain res)))
             (get-in @state [:machines id :ports port-str]))

           (catch Exception e
            ;; 回滚：释放已分配端口
             (release-port smolvm-host-port)
            ;; 尝试重新启动 VM
             (try (smolvm-cli/start-machine! (:name m)) (catch Exception _))
             (throw e))))))))

(defn unexpose-port!
  ([id port]
   (unexpose-port! id port nil))
  ([id port actor]
   (require-mutations-enabled!)
   (let [m (require-machine-access! id actor)
         port-str (str/trim (str port))]
     (when-not m (throw (Exception. (:machine-not-found (:validation i18n/strings)))))
     (let [p-info (get-in m [:ports port-str])]
       (when-not p-info (throw (Exception. ((:port-not-exposed (:validation i18n/strings)) port-str))))

      ;; 删除 Caddy 规则（域名模式）
       (when (:caddyRuleId p-info)
         (try
           (let [parsed (caddy/parse-key (:caddyRuleId p-info))]
             (when parsed (caddy/remove-rule! (:domain parsed) (:worker parsed) nil true)))
           (catch Exception _)))

      ;; 释放已分配端口
       (when (:smolvmPort p-info)
         (release-port (:smolvmPort p-info)))

      ;; 更新剩余端口
       (let [new-ports (dissoc (:ports m) port-str)
             port-args (mapv (fn [[p info]]
                               (str (:smolvmPort info) ":" p))
                             new-ports)]

        ;; 停止 VM、更新 -p 并重启
         (try
           (when (= (:status m) "running")
             (smolvm-cli/stop-machine! (:name m)))
           (smolvm-cli/update-machine! (:name m) {:ports port-args})
           (smolvm-cli/start-machine! (:name m))
           (swap! state assoc-in [:machines id :status] "running")
           (catch Exception e
             (try (smolvm-cli/start-machine! (:name m)) (catch Exception _))
             (log/warn (format "[smolvm] VM 重启失败: %s" (.getMessage e))))))

       (swap! state update-in [:machines id :ports] dissoc port-str)
       (save-entries!)
       (log/info (format "[smolvm] 取消暴露 VM 端口: %s:%s" (:name m) port-str))))))

(defn toggle-port!
  ([id port]
   (toggle-port! id port nil))
  ([id port actor]
   (require-mutations-enabled!)
   (let [m (require-machine-access! id actor)
         port-str (str/trim (str port))]
     (when-not m (throw (Exception. (:machine-not-found (:validation i18n/strings)))))
     (let [p-info (get-in m [:ports port-str])]
       (when-not p-info (throw (Exception. ((:port-not-exposed (:validation i18n/strings)) port-str))))

       (if (:ip-mode config)
        ;; IP 模式：需停止、更新并启动 VM 以增删 -p 绑定
         (let [new-enabled (not (:enabled p-info))
              ;; 构建所有启用端口参数，并按切换结果增删当前端口
               base-ports (dissoc (:ports m) port-str)
               enabled-args (->> base-ports
                                 (filter (fn [[_ i]] (:enabled i)))
                                 (mapv (fn [[p i]] (str (:smolvmPort i) ":" p))))
               port-args (if new-enabled
                           (conj enabled-args (str (:smolvmPort p-info) ":" port-str))
                           enabled-args)]
           (try
             (smolvm-cli/stop-machine! (:name m))
             (smolvm-cli/update-machine! (:name m) {:ports port-args})
             (smolvm-cli/start-machine! (:name m))
             (swap! state assoc-in [:machines id :status] "running")
             (catch Exception e
               (try (smolvm-cli/start-machine! (:name m)) (catch Exception _))
               (throw e))))
        ;; 域名模式：切换 Caddy 规则，无需重启 VM
         (when (:caddyRuleId p-info)
           (let [parsed (caddy/parse-key (:caddyRuleId p-info))]
             (when parsed (caddy/toggle-rule! (:domain parsed) (:worker parsed) nil true)))))

       (let [new-enabled (not (:enabled p-info))]
         (swap! state assoc-in [:machines id :ports port-str :enabled] new-enabled)
         (save-entries!)
         new-enabled)))))

;; ============================================================
;; 打包
;; ============================================================

(defn pack-machine!
  ([id output-dir actor]
   (require-mutations-enabled!)
   (let [m (require-machine-access! id actor)
         output-path (str (or output-dir "/tmp") "/" (:name m) ".smolmachine")]
     (smolvm-cli/pack-machine! (:name m) output-path)
     output-path))
  ([id output-dir user-id is-admin]
   (pack-machine! id output-dir (authorization/actor user-id is-admin))))

;; ============================================================
;; 状态与同步
;; ============================================================

(defn refresh-status! [id]
  (let [m (get-in @state [:machines id])]
    (when m
      (let [m-running (smolvm-cli/machine-running-strict? (:name m))
            ttyd-enabled? (:ttyd-enabled config false)
            ttyd-alive (and ttyd-enabled? (ttyd-alive? m))
            status (cond
                     (not m-running) "stopped"
                     (and ttyd-enabled? (not ttyd-alive)) "error"
                     :else "running")]
        (when-not ttyd-enabled?
          (swap! state assoc-in [:machines id :ttydPid] nil))
        (swap! state assoc-in [:machines id :status] status)
        status))))

(defn- recovery-failure [id machine stage error]
  {:id id
   :name (:name machine)
   :stage stage
   :message (.getMessage error)})

(defn- restore-port-caddy-rules! [id machine]
  (when-not (:ip-mode config)
    (doseq [[port info] (:ports machine)]
      (when (:enabled info)
        (let [rule-id (:caddyRuleId info)]
          (when (and rule-id (nil? (caddy/parse-key rule-id)))
            (throw (ex-info "SmolVM 端口 Caddy 规则标识无效"
                            {:type ::invalid-port-caddy-rule
                             :id id
                             :port port
                             :caddy-rule-id rule-id})))
          (when (or (nil? rule-id)
                    (nil? (get-in @caddy/state [:entries (keyword rule-id)])))
            (let [restored (add-port-caddy-rule machine port (:smolvmPort info) (:remark info))]
              (swap! state update-in [:machines id :ports port] merge restored))))))))

(defn- recover-machine! [id machine]
  (if-not (smolvm-cli/machine-running-strict? (:name machine))
    (do
      (swap! state assoc-in [:machines id :status] "stopped")
      (swap! state assoc-in [:machines id :ttydPid] nil)
      nil)
    (let [ttyd-enabled? (:ttyd-enabled config false)
          started-ttyd? (atom false)]
      (try
        (if-not ttyd-enabled?
          (swap! state assoc-in [:machines id :ttydPid] nil)
          (when-not (ttyd-alive? machine)
            (let [ttyd-pid (start-ttyd! machine)]
              (reset! started-ttyd? true)
              (swap! state assoc-in [:machines id :ttydPid] ttyd-pid))))
        (when ttyd-enabled?
          (let [rule-id (:caddyRuleId machine)]
            (when (and rule-id (nil? (caddy/parse-key rule-id)))
              (throw (ex-info "SmolVM shell Caddy 规则标识无效"
                              {:type ::invalid-shell-caddy-rule
                               :id id
                               :caddy-rule-id rule-id})))
            (when (or (nil? rule-id)
                      (and (not (:ip-mode config))
                           (nil? (get-in @caddy/state [:entries (keyword rule-id)]))))
              (let [restored-rule-id (add-shell-caddy-rule machine)]
                (swap! state assoc-in [:machines id :caddyRuleId] restored-rule-id)))))
        (restore-port-caddy-rules! id machine)
        (swap! state assoc-in [:machines id :status] "running")
        nil
        (catch Exception recovery-error
          (swap! state assoc-in [:machines id :status] "error")
          (let [failures [(recovery-failure id machine :recovery recovery-error)]]
            (if-not @started-ttyd?
              failures
              (try
                (stop-ttyd! (get-in @state [:machines id]) true)
                failures
                (catch Exception rollback-error
                  (conj failures (recovery-failure id machine :ttyd-rollback rollback-error)))))))))))

(defn sync-machines! []
  (load-entries!)
  (let [machines (:machines @state)]
    (if-not (:smolvm-mutations-enabled config false)
      (when (seq machines)
        (throw (ex-info "SmolVM 启动恢复失败：变更功能未启用，请先迁移或清空 SmolVM 持久数据"
                        {:type ::startup-recovery-failed
                         :failures [{:stage :feature-disabled
                                     :message "SmolVM 变更功能未启用；请先迁移或清空 SmolVM 持久数据后再启动"}]})))
      (if-not (smolvm-cli/available?)
        (when (seq machines)
          (throw (ex-info "SmolVM 启动恢复失败：CLI 不可用"
                          {:type ::startup-recovery-failed
                           :failures [{:stage :cli-availability
                                       :message "SmolVM CLI 不可用"}]})))
        (let [failures (reduce (fn [acc [id machine]]
                                 (try
                                   (into acc (or (recover-machine! id machine) []))
                                   (catch Exception e
                                     (conj acc (recovery-failure id machine :machine-status e)))))
                               []
                               machines)]
          (when (seq failures)
            (throw (ex-info "SmolVM 启动恢复失败"
                            {:type ::startup-recovery-failed
                             :failures failures})))
          (save-entries!))))))

(defn shutdown! []
  (log/info "[smolvm-shutdown] 停止所有 VM ttyd 进程...")
  (doseq [m (vals (:machines @state))]
    (stop-ttyd! m)))
