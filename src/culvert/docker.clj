(ns culvert.docker
  (:require [clojure.string :as str]
            [culvert.auth :as auth]
            [culvert.authorization :as authorization]
            [culvert.caddy :as caddy]
            [culvert.cli :as cli]
            [culvert.config :refer [config]]
            [culvert.db :as db]
            [culvert.forward :as forward]
            [culvert.i18n :as i18n]
            [culvert.runtime.process :as process]
            [culvert.s3 :as s3])
  (:import [java.net ServerSocket]
           [java.security SecureRandom]))

;; ============================================================
;; 容器、ttyd 与 Caddy 工作区管理器
;; ============================================================

(defonce state (atom {:containers {}   ; id -> 容器
                      :webshells {}      ; 容器名 -> 管理员终端会话记录（只存数据，不存进程）
                      :webshell-procs {} ; 容器名 -> {:pid pid :proc proc}
                      :used-ports #{}
                      :ttyd-procs {}})) ; id -> {:pid pid :proc proc}

(def legacy-user (or (:auth-user config) "admin"))

(defn- require-mutations-enabled! []
  (when-not (:docker-mutations-enabled config false)
    (throw (ex-info "Docker 变更功能未启用"
                    {:type ::mutations-disabled}))))

;; --- Port Allocator ---

(defn- port-available? [port]
  (try
    (let [socket (ServerSocket. port)]
      (.close socket)
      true)
    (catch Exception _
      false)))

(defn- allocate-port []
  (let [start (:ttyd-port-start config)
        end (:ttyd-port-end config)
        used (:used-ports @state)]
    (loop [port start]
      (if (> port end)
        (throw (Exception. (format "端口池耗尽 (%d-%d)，无法分配新端口" start end)))
        (if (and (not (contains? used port)) (port-available? port))
          (do
            (swap! state update :used-ports conj port)
            port)
          (recur (inc port)))))))

(defn- release-port [port]
  (swap! state update :used-ports disj port))

;; --- Helpers: String Generators ---

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

(defn sanitize-container-name [user-id image short-id]
  (let [safe-user (-> (str user-id)
                      (str/replace #"[^a-zA-Z0-9_.-]" "_")
                      (subs 0 (min 32 (count user-id))))
        image-slug (-> (str image)
                       (str/replace #"[/:]" "-")
                       (str/replace #"[^a-zA-Z0-9_.-]" "-")
                       (subs 0 (min 20 (count image))))]
    (str safe-user "-" image-slug "-" short-id)))

;; --- Memory Math Helpers ---

(defn- parse-mem-bytes [mem-str]
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

(defn- compare-memory [mem-a mem-b]
  (compare (parse-mem-bytes mem-a) (parse-mem-bytes mem-b)))

(defn add-memory [mem-a mem-b]
  (let [total (long (+ (parse-mem-bytes mem-a) (parse-mem-bytes mem-b)))]
    (cond
      (and (>= total 1073741824) (zero? (mod total 1073741824)))
      (str (quot total 1073741824) "g")

      (and (>= total 1048576) (zero? (mod total 1048576)))
      (str (quot total 1048576) "m")

      :else
      (str (format "%.1f" (/ (double total) 1048576.0)) "m"))))

(defn- get-user-resource-usage [user-id]
  (reduce (fn [acc c]
            (if (= (:userId c) user-id)
              (-> acc
                  (update :totalCpu + (Double/parseDouble (or (:cpuLimit c) "0")))
                  (update :totalMem add-memory (or (:memLimit c) "0")))
              acc))
          {:totalCpu 0.0 :totalMem "0"}
          (vals (:containers @state))))

;; --- Caddy Rule Helpers ---

(defn- add-shell-caddy-rule [container]
  (if (:ip-mode config)
    nil
    (let [domain (:caddy-domain config)
          worker (str "shell-" (:shellToken container))
          target (str "http://127.0.0.1:" (:shellPort container))]
      (caddy/add-managed-rule! domain worker target (str "shell:" (:name container)) (:userId container))
      (caddy/build-key domain worker))))

(defn- add-port-caddy-rule [container port remark]
  (if (:ip-mode config)
    ;; IP mode: allocate host port + socat forward to container
    (let [host-port (allocate-port)
          ip (if (cli/can-access-container-ip?)
               (cli/get-container-ip (:name container))
               "127.0.0.1")]
      (forward/start-socat! "tcp" host-port ip (Integer/parseInt port))
      {:caddyRuleId nil
       :domain (str (:public-ip config) ":" host-port)
       :hostPort host-port})
    ;; Domain mode: create Caddy reverse proxy rule
    (let [domain (:caddy-domain config)
          worker (str "p" port "-" (:shellToken container))
          ip (if (cli/can-access-container-ip?)
               (cli/get-container-ip (:name container))
               "127.0.0.1")
          target (str "http://" ip ":" port)]
      (caddy/add-managed-rule! domain worker target (or remark (str "port:" (:name container) ":" port)) (:userId container))
      {:caddyRuleId (caddy/build-key domain worker)
       :domain (str worker "." domain)})))

;; --- DB Operations ---

(defn load-entries! []
  (let [data (db/read-json (:docker-db-path config) {:containers {} :webshells {}})
        containers (reduce (fn [acc [k c]]
                             (let [k-str (if (keyword? k) (name k) k)
                                   c-updated (cond-> c
                                               (nil? (:shell c)) (assoc :shell (get-in config [:images-map (:image c) :entry] (:default-shell config)))
                                               true (assoc :userId (or (:userId c) legacy-user)))]
                               (assoc acc k-str c-updated)))
                           {}
                           (:containers data {}))
        webshells (reduce (fn [acc [k ws]]
                            (let [k-str (if (keyword? k) (name k) k)]
                              (assoc acc k-str
                                     ;; 进程不跨重启存活，恢复前的状态一律视为已停止。
                                     (assoc ws :ttydPid nil :status "stopped"))))
                          {}
                          (:webshells data {}))]
    (swap! state assoc :containers containers :webshells webshells)
    (doseq [c (vals containers)]
      (when (:shellPort c)
        (swap! state update :used-ports conj (:shellPort c)))
      ;; IP mode: register exposed port host ports too
      (doseq [[_port info] (:ports c)]
        (when (:hostPort info)
          (swap! state update :used-ports conj (:hostPort info)))))
    (doseq [ws (vals webshells)]
      (when (:port ws)
        (swap! state update :used-ports conj (:port ws))))
    (println (format "[docker] 已加载 %d 个容器记录、%d 个终端会话"
                     (count (:containers @state)) (count (:webshells @state))))))

(defn save-entries! []
  (db/write-json (:docker-db-path config) {:containers (:containers @state)
                                           :webshells (:webshells @state)}))

;; --- ttyd Process Controls ---

(defn stop-ttyd! [container]
  (let [pid (:ttydPid container)
        id (:id container)]
    (when pid
      (try
        (let [entry (get-in @state [:ttyd-procs id])]
          (when entry (process/destroy! (:proc entry))))
        (println (format "[ttyd] 已停止 pid=%d" pid))
        (catch Exception _)))
    (swap! state update :ttyd-procs dissoc id)
    (swap! state assoc-in [:containers id :ttydPid] nil)))

(defn- require-ttyd-enabled! []
  (when-not (:ttyd-enabled config false)
    (throw (ex-info "ttyd 功能未启用" {:type ::ttyd-disabled}))))

(defn start-ttyd! [container]
  (require-ttyd-enabled!)
  (let [id (:id container)
        max-clients (:ttyd-max-clients config 1)
        base-args ["-i" "127.0.0.1"
                   "-p" (str (:shellPort container))
                   "-c" (str (:shellUser container) ":" (:shellPass container))
                   "-W"]
        args (cond-> base-args
               (pos? max-clients) (into ["--max-clients" (str max-clients)])
               true (into ["--client-option" "fontSize=14"
                           "--client-option" "theme=monokai"
                           (:container-cli-path config) "exec" "-it" (:name container) (:shell container)]))
        proc (process/spawn (into [(:ttyd-path config)] args) {:out :inherit :err :inherit})
        pid (process/pid proc)]
    (swap! state assoc-in [:ttyd-procs id] {:pid pid :proc proc})
    (println (format "[ttyd] 已启动 pid=%d port=%d" pid (:shellPort container)))

    ;; Daemon thread: watches ttyd process exit — won't block JVM shutdown
    (let [watcher (Thread. (fn []
                             (try
                               (let [exit-code (process/wait proc)]
                                 (println (format "[ttyd:%d] 已退出，退出码 %s" pid exit-code))
                                 (when (= (get-in @state [:ttyd-procs id :pid]) pid)
                                   (swap! state update :ttyd-procs dissoc id)
                                   (swap! state assoc-in [:containers id :ttydPid] nil)
                                   (swap! state assoc-in [:containers id :status] "error")
                                   (save-entries!)))
                               (catch InterruptedException _))))]
      (.setDaemon watcher true)
      (.start watcher))
    pid))

(defn ttyd-alive? [container]
  (let [id (:id container)
        entry (get-in @state [:ttyd-procs id])]
    (and entry (process/alive? (:proc entry)))))

;; --- 日志读取（受管容器按所有权，宿主机容器按管理员） ---

(defn- require-container-access! [container-id actor]
  (authorization/require-actor! actor)
  (let [c (get-in @state [:containers container-id])]
    (when-not c
      (throw (Exception. (:container-not-found (:validation i18n/strings)))))
    (authorization/require-resource-access!
     actor (:userId c) legacy-user
     (:no-permission-container (:validation i18n/strings)))
    c))

(defn- find-managed-container
  "按应用记录 id 或容器名匹配受管容器，未命中返回 nil。"
  [target]
  (let [t (str target)]
    (or (get-in @state [:containers t])
        (some #(when (= (:name %) t) %) (vals (:containers @state))))))

(defn resolve-log-target
  "解析日志目标：受管容器按所有权校验（管理员可看全部），宿主机上其他容器只有管理员可看。
   返回 {:source :managed|:host :id :name :state}；目标不存在或无权时抛异常。"
  [target actor]
  (authorization/require-actor! actor)
  (if-let [c (find-managed-container target)]
    (let [managed (require-container-access! (:id c) actor)]
      {:source :managed :id (:id managed) :name (:name managed) :state (:status managed)})
    (do
      ;; 宿主机上的未知容器没有可校验的所有者，只开放给管理员。
      (authorization/require-admin! actor (:no-permission-container (:validation i18n/strings)))
      (let [info (try
                   (cli/inspect-container-details target)
                   (catch Exception e
                     (throw (ex-info (:container-not-found (:validation i18n/strings))
                                     {:type ::container-not-found :target (str target)}
                                     e))))]
        {:source :host :id (:id info) :name (:name info) :state (:state info)}))))

(defn read-logs
  "读取可访问容器的最近日志，opts 支持 :tail（默认 200）。
   日志读取不需要 PTY，也不再依赖 ttyd：容器已停止（exited）时同样可读。"
  ([target actor] (read-logs target actor {}))
  ([target actor opts]
   (let [resolved (resolve-log-target target actor)]
     (assoc resolved
            :text (cli/get-container-logs (:name resolved) {:tail (:tail opts 200)})))))

;; --- 管理员容器终端（持久 webshell） ---
;;
;; 与受管容器记录里的所有者 shell 是两套东西：
;; - 所有者 shell 随容器记录生命周期，凭据属于容器所有者，本模块不动它；
;; - 管理员终端是独立注册表 `:webshells`，对宿主机上任意容器开放，
;;   只要容器存在且运行就保持存活（不随浏览器关闭或空闲回收），容器停止、
;;   被删除（含 CLI 直接删除）或管理员显式关闭时结束。

(def ^:private webshell-shell-candidates ["/bin/bash" "/bin/sh"])

(defn- require-webshell-exposure!
  "域模式走 Caddy 子域；IP 模式默认只绑 loopback，需要显式开启 ttydSessionPublic。"
  []
  (require-ttyd-enabled!)
  (when (and (:ip-mode config) (not (:ttyd-session-public config false)))
    (throw (ex-info (:terminal-exposure-disabled (:validation i18n/strings))
                    {:type ::terminal-exposure-disabled}))))

(defn- webshell-bind-address []
  (if (:ip-mode config) (:listen-address config) "127.0.0.1"))

(defn- webshell-url [ws]
  (if (:ip-mode config)
    (str "http://" (:public-ip config) ":" (:port ws))
    (when-let [parsed (caddy/parse-key (:caddyRuleId ws))]
      ;; Caddyfile 里受管规则固定是 http://<worker>.<domain>，不能写成 https。
      (str "http://" (:worker parsed) "." (:domain parsed)))))

(defn- webshell-alive? [ws]
  (let [entry (get-in @state [:webshell-procs (:name ws)])]
    (boolean (and ws entry (:proc entry) (process/alive? (:proc entry))))))

(defn- to-public-webshell [ws]
  (when ws
    {:name (:name ws)
     :status (cond
               (and (= (:status ws) "running") (webshell-alive? ws)) "running"
               (= (:status ws) "running") "stopped"
               :else (:status ws))
     :shell (:shell ws)
     :user (:user ws)
     :passMasked "●●●●●●●●●●●●"
     :url (when (= (:status ws) "running") (webshell-url ws))
     :createdBy (:createdBy ws)
     :createdAt (:createdAt ws)}))

(defn webshell-for
  "按容器名或应用记录 id 查公开终端信息，未开启返回 nil。"
  [target]
  (let [t (str target)
        managed (find-managed-container t)
        name (if managed (:name managed) t)]
    (to-public-webshell (get-in @state [:webshells name]))))

(defn resolve-shell-target
  "解析终端目标：宿主机上任意容器（含未受管容器）均可，只有管理员能调用。
   返回 {:name :containerId :image :state}；容器不存在抛 ::container-not-found。"
  [target actor]
  (authorization/require-actor! actor)
  (authorization/require-admin! actor (:no-permission-container (:validation i18n/strings)))
  (let [managed (find-managed-container target)
        name (if managed (:name managed) (str target))
        info (try
               (cli/inspect-container-details name)
               (catch Exception e
                 (throw (ex-info (:container-not-found (:validation i18n/strings))
                                 {:type ::container-not-found :target (str target)}
                                 e))))]
    {:name (:name info)
     :containerId (:id info)
     :image (:image info)
     :state (:state info)}))

(defn- resolve-webshell-shell [name image]
  (let [candidates (->> (concat [(get-in config [:images-map image :entry])]
                                [(:default-shell config)]
                                webshell-shell-candidates)
                        (remove str/blank?)
                        distinct)]
    (or (some #(when (cli/shell-available-in-container? name %) %) candidates)
        (throw (ex-info (:terminal-shell-unavailable (:validation i18n/strings))
                        {:type ::terminal-shell-unavailable
                         :containerName name})))))

(defn- add-webshell-caddy-rule [ws]
  (if (:ip-mode config)
    nil
    (let [domain (:caddy-domain config)
          worker (str "term-" (generate-token) (generate-token))
          target (str "http://127.0.0.1:" (:port ws))]
      (caddy/add-managed-rule! domain worker target
                               (str "terminal:" (:name ws)) (:createdBy ws))
      (caddy/build-key domain worker))))

(defn- remove-webshell-caddy-rule! [ws]
  (when-let [rule-id (:caddyRuleId ws)]
    (try
      (let [parsed (caddy/parse-key rule-id)]
        (when parsed (caddy/remove-rule! (:domain parsed) (:worker parsed) nil true)))
      (catch Exception e
        (println (format "[webshell] ⚠️ 移除终端 Caddy 规则失败: %s" (.getMessage e)))))))

(defn- watch-webshell! [name pid proc]
  ;; 守护线程：ttyd 退出说明容器已停止或被删除，回收端口与代理规则。
  (let [watcher (Thread. (fn []
                           (try
                             (let [exit-code (process/wait proc)
                                   ws (get-in @state [:webshells name])]
                               (println (format "[webshell:%s] 已退出，退出码 %s" name exit-code))
                               (when (and ws (= (get-in @state [:webshell-procs name :pid]) pid))
                                 (release-port (:port ws))
                                 (remove-webshell-caddy-rule! ws)
                                 (swap! state update :webshell-procs dissoc name)
                                 (swap! state assoc-in [:webshells name :ttydPid] nil)
                                 (swap! state assoc-in [:webshells name :status] "stopped")))
                             (catch InterruptedException _)
                             (catch Exception e
                               (println (format "[webshell:%s] 监听退出失败: %s" name (.getMessage e)))))))]
    (.setDaemon watcher true)
    (.start watcher)))

(defn- start-webshell-process!
  "启动（或重启）某个已登记容器的 ttyd 进程，复用已存在的 Caddy 规则。"
  [name]
  (let [ws (get-in @state [:webshells name])
        args (vec (concat [(:ttyd-path config)
                           "-i" (webshell-bind-address)
                           "-p" (str (:port ws))
                           "-c" (str (:user ws) ":" (:pass ws))
                           "-W"
                           "--client-option" "fontSize=14"
                           "--client-option" "theme=monokai"]
                          [(:container-cli-path config) "exec" "-it" name (:shell ws)]))
        proc (process/spawn args {:out :inherit :err :inherit})
        pid (process/pid proc)
        existing-rule (:caddyRuleId ws)
        rule-live? (and existing-rule
                        (get-in @caddy/state [:entries (keyword existing-rule)]))]
    (swap! state assoc-in [:webshell-procs name] {:pid pid :proc proc})
    (swap! state assoc-in [:webshells name :ttydPid] pid)
    (when-not rule-live?
      (let [rule-id (add-webshell-caddy-rule (get-in @state [:webshells name]))]
        (swap! state assoc-in [:webshells name :caddyRuleId] rule-id)))
    (swap! state assoc-in [:webshells name :status] "running")
    (println (format "[webshell] 已启动 container=%s pid=%d port=%d" name pid (:port ws)))
    (watch-webshell! name pid proc)))

(defn- stop-webshell-process! [name]
  (when-let [ws (get-in @state [:webshells name])]
    (when-let [entry (get-in @state [:webshell-procs name])]
      (try
        (when (process/alive? (:proc entry)) (process/destroy! (:proc entry)))
        (catch Exception e
          (println (format "[webshell:%s] 停止 ttyd 失败: %s" name (.getMessage e))))))
    (release-port (:port ws))
    (remove-webshell-caddy-rule! ws)
    (swap! state update :webshell-procs dissoc name)
    (swap! state update :webshells dissoc name)))

(defn open-webshell!
  "为运行中的容器开启（或复用）管理员终端。
   返回公开终端信息；容器未运行、曝光未开启、shell 不可用时抛异常且不产生副作用。"
  [target actor]
  (require-mutations-enabled!)
  (require-webshell-exposure!)
  (let [{:keys [name containerId image] :as target-info} (resolve-shell-target target actor)]
    (when-not (= (:state target-info) "running")
      (throw (ex-info (:terminal-not-running (:validation i18n/strings))
                      {:type ::terminal-not-running :containerName name})))
    (let [existing (get-in @state [:webshells name])]
      (if (and existing
               (= (:containerId existing) containerId)
               (webshell-alive? existing))
        (to-public-webshell existing)
        (do
          ;; 同名旧容器已被删除、进程已退出、或首次开启：记录重建。
          (when existing (stop-webshell-process! name))
          (let [shell-path (resolve-webshell-shell name image)
                creds (generate-credentials)
                port (allocate-port)
                ws {:name name
                    :containerId containerId
                    :image image
                    :shell shell-path
                    :user (:shellUser creds)
                    :pass (:shellPass creds)
                    :port port
                    :ttydPid nil
                    :caddyRuleId nil
                    :status "stopped"
                    :createdBy (:username actor)
                    :createdAt (.toString (java.time.Instant/now))}]
            (swap! state assoc-in [:webshells name] ws)
            (try
              (start-webshell-process! name)
              (save-entries!)
              (to-public-webshell (get-in @state [:webshells name]))
              (catch Exception e
                ;; 启动失败不留下半开状态：回收端口、规则与记录后原样抛出。
                (stop-webshell-process! name)
                (throw e)))))))))

(defn close-webshell!
  "管理员显式关闭终端：停止进程、移除规则并丢弃记录。"
  [target actor]
  (require-mutations-enabled!)
  (authorization/require-actor! actor)
  (authorization/require-admin! actor (:no-permission-container (:validation i18n/strings)))
  (let [t (str target)
        managed (find-managed-container t)
        name (if managed (:name managed) t)]
    (when (get-in @state [:webshells name])
      (stop-webshell-process! name)
      (save-entries!))
    {:status "ok"}))

(defn reveal-webshell-credentials
  "按需返回终端明文凭据（仅管理员），不进入状态 GET、URL 或普通 DTO。"
  [target actor]
  (authorization/require-actor! actor)
  (authorization/require-admin! actor (:no-permission-container (:validation i18n/strings)))
  (let [ws (webshell-for target)]
    (when-not ws
      (throw (ex-info (:terminal-session-not-found (:validation i18n/strings))
                      {:type ::terminal-session-not-found :target (str target)})))
    {:user (:user ws) :pass (:pass (get-in @state [:webshells (:name ws)]))}))

(defn restore-webshells!
  "启动恢复：运行中的容器重建 ttyd，已停止的标为 stopped，已被删除的丢弃记录。

   与受管容器不同，终端会话恢复失败不阻塞启动：它不拥有容器或数据，
   丢掉只损失一个调试入口，而被删除的容器只能靠命令行判断。
   调用方负责持久化（`sync-containers!` 在容器恢复后统一保存）。"
  []
  (when (and (:docker-mutations-enabled config false) (cli/available?))
    (doseq [[name ws] (:webshells @state)]
      (try
        (if-not (cli/container-exists? name)
          (do
            (println (format "[webshell] 容器已不存在，丢弃终端记录: %s" name))
            (stop-webshell-process! name))
          (let [info (cli/inspect-container-details name)]
            (swap! state assoc-in [:webshells name :containerId] (:id info))
            (if (= (:state info) "running")
              (if (webshell-alive? ws)
                (swap! state assoc-in [:webshells name :status] "running")
                (try
                  (require-webshell-exposure!)
                  (start-webshell-process! name)
                  (catch Exception e
                    (println (format "[webshell] 恢复终端失败，保持 stopped: %s — %s"
                                     name (.getMessage e)))
                    (release-port (:port ws))
                    (remove-webshell-caddy-rule! ws)
                    (swap! state assoc-in [:webshells name :caddyRuleId] nil)
                    (swap! state assoc-in [:webshells name :ttydPid] nil)
                    (swap! state assoc-in [:webshells name :status] "stopped"))))
              (do
                ;; 容器存在但未运行：ttyd 已不可能存活，保留记录供容器重新运行后再开启。
                (release-port (:port ws))
                (remove-webshell-caddy-rule! ws)
                (swap! state assoc-in [:webshells name :caddyRuleId] nil)
                (swap! state assoc-in [:webshells name :ttydPid] nil)
                (swap! state assoc-in [:webshells name :status] "stopped")))))
        (catch Exception e
          (println (format "[webshell] 恢复终端会话失败 %s: %s" name (.getMessage e)))
          (swap! state assoc-in [:webshells name :status] "stopped"))))))

(defn stop-webshells!
  "停机：停止所有终端 ttyd 进程（保留记录与 Caddy 规则，下次启动按同一规则恢复）。"
  []
  (doseq [name (keys (:webshells @state))]
    (let [ws (get-in @state [:webshells name])
          entry (get-in @state [:webshell-procs name])]
      (when entry
        (try
          (when (process/alive? (:proc entry)) (process/destroy! (:proc entry)))
          (catch Exception e
            (println (format "[webshell:%s] 停机停止 ttyd 失败: %s" name (.getMessage e))))))
      (release-port (:port ws))
      (swap! state update :webshell-procs dissoc name)
      (swap! state assoc-in [:webshells name :ttydPid] nil)
      (swap! state assoc-in [:webshells name :status] "stopped"))))

;; --- Container Lifecycle Management ---

(defn count-by-user [user-id]
  (count (filter #(= (:userId %) user-id) (vals (:containers @state)))))

(defn- to-public-container [c]
  (let [shell-url (if (:ip-mode config)
                    ;; IP mode: direct ttyd access via PUBLIC_IP:SHELL_PORT
                    (when (:shellPort c)
                      (format "http://%s:%d"
                              (:public-ip config)
                              (:shellPort c)))
                    ;; Domain mode: Caddy subdomain URL
                    (when (:caddyRuleId c)
                      (let [parsed (caddy/parse-key (:caddyRuleId c))]
                        (when parsed
                          (format "https://%s.%s"
                                  (:worker parsed) (:domain parsed))))))
        ports-public (reduce (fn [acc [port info]]
                               (assoc acc port
                                      {:domain (:domain info "")
                                       :enabled (:enabled info)
                                       :remark (:remark info "")
                                       :url (if (:ip-mode config)
                                              ;; IP mode: http://PUBLIC_IP:HOST_PORT
                                              (when-let [hp (:hostPort info)]
                                                (str "http://" (:public-ip config) ":" hp))
                                              ;; Domain mode: https://subdomain.domain
                                              (when (:domain info) (str "https://" (:domain info))))}))
                             {}
                             (:ports c {}))]
    {:id (:id c)
     :name (:name c)
     :image (:image c)
     :userId (:userId c)
     :shellToken (:shellToken c)
     :shellUser (:shellUser c)
     :shellPassMasked "●●●●●●●●●●●●"
     :shellUrl shell-url
     :shellPort (:shellPort c)
     :shell (:shell c)
     :createdAt (:createdAt c)
     :status (:status c)
     :cpuLimit (:cpuLimit c)
     :memLimit (:memLimit c)
     :gpu (:gpu c false)
     :s3Mount (when (:s3Mount c)
                {:enabled (get-in c [:s3Mount :enabled])
                 :mountPoint (get-in c [:s3Mount :containerPath])
                 :endpoint (get-in c [:s3Mount :endpoint])
                 :bucket (get-in c [:s3Mount :bucket])
                 :status (get-in c [:s3Mount :status])})
     :ports ports-public}))

(defn get-containers
  ([actor]
   (authorization/require-actor! actor)
   (->> (vals (:containers @state))
        (filter #(authorization/authorized? actor (:userId %) legacy-user))
        (map to-public-container)
        (sort-by :createdAt #(compare %2 %1))))
  ([user-id is-admin]
   (get-containers (authorization/actor user-id is-admin))))

(defn get-container
  ([id actor]
   (authorization/require-actor! actor)
   (let [c (get-in @state [:containers id])]
     (when (and c (authorization/authorized? actor (:userId c) legacy-user))
       (to-public-container c))))
  ([id user-id is-admin]
   (get-container id (authorization/actor user-id is-admin))))

(defn get-shell-pass
  ([id actor]
   (authorization/require-actor! actor)
   (let [c (get-in @state [:containers id])]
     (when (and c (authorization/authorized? actor (:userId c) legacy-user))
       (:shellPass c))))
  ([id user-id is-admin]
   (get-shell-pass id (authorization/actor user-id is-admin))))

(defn create-container! [user-id image cpu-limit mem-limit s3-config gpu?]
  (require-mutations-enabled!)
  ;; 1. 检查镜像白名单
  (when (seq (:allowed-images config))
    (when-not (some #(= image %) (:allowed-images config))
      (throw (Exception. ((:image-not-allowed (:validation i18n/strings))
                          {:image image :allowed (str/join ", " (:allowed-images config))})))))

  (let [cpu-str (str (or cpu-limit (:default-cpu-limit config)))
        mem-str (str (or mem-limit (:default-mem-limit config)))]

    ;; 2. 校验资源限制
    (when (> (Double/parseDouble cpu-str) (Double/parseDouble (:max-cpu-limit config)))
      (throw (Exception. ((:cpu-exceeded (:validation i18n/strings)) (:max-cpu-limit config)))))
    (when (> (compare-memory mem-str (:max-mem-limit config)) 0)
      (throw (Exception. ((:mem-exceeded (:validation i18n/strings)) (:max-mem-limit config)))))

    ;; 3. 校验用户配额
    (when user-id
      (let [quotas (auth/get-user-quotas user-id)
            usage (get-user-resource-usage user-id)]
        (when (>= (count-by-user user-id) (:containers quotas))
          (throw (Exception. ((:container-quota-exhausted (:validation i18n/strings))
                              {:used (count-by-user user-id) :limit (:containers quotas)}))))
        (when (> (+ (:totalCpu usage) (Double/parseDouble cpu-str)) (:cpuLimit quotas))
          (throw (Exception. ((:cpu-quota-exceeded (:validation i18n/strings))
                              {:used (:totalCpu usage) :added cpu-str :limit (:cpuLimit quotas)}))))
        (when (> (compare-memory (add-memory (:totalMem usage) mem-str) (:memLimit quotas)) 0)
          (throw (Exception. ((:mem-quota-exceeded (:validation i18n/strings))
                              {:used (:totalMem usage) :added mem-str :limit (:memLimit quotas)}))))))

    ;; 4. 准备容器属性
    (let [shell (get-in config [:images-map image :entry] (:default-shell config))
          shell-token (generate-token)
          creds (generate-credentials)
          shell-port (allocate-port)
          short-id (generate-token)
          c-name (sanitize-container-name user-id image short-id)]

      ;; 5. 拉取镜像
      (cli/pull-image image)

      ;; 6. 挂载 FUSE 存储
      (let [s3-mount-res (if (and s3-config (:bucket s3-config) (:accessKey s3-config) (:secretKey s3-config))
                           (try
                             (s3/mount! c-name s3-config)
                             (catch Exception e
                               {:status "error" :warning (.getMessage e)}))
                           {:status "disabled"})

            volumes (if (and (= (:status s3-mount-res) "mounted") (:mountPoint s3-mount-res))
                      {(:mountPoint s3-mount-res) (get-in config [:s3 :container-mount-path])}
                      {})]

        ;; 6.5 校验 GPU
        (when gpu?
          (when-not (cli/gpu-available?)
            (throw (Exception. (:gpu-unavailable (:validation i18n/strings))))))

        ;; 7. 运行容器
        (try
          (let [run-opts {:image image
                          :name c-name
                          :cpus cpu-str
                          :memory mem-str
                          :volumes volumes}
                run-opts (if gpu? (assoc run-opts :gpu true) run-opts)
                c-id (cli/run-container run-opts)
                container {:id c-id
                           :name c-name
                           :image image
                           :userId user-id
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
                           :gpu gpu?
                           :ports {}}]

            ;; 8. 启动 ttyd
            (try
              (let [container (if (= (:status s3-mount-res) "mounted")
                                (assoc container :s3Mount {:enabled true
                                                           :mountPoint (:mountPoint s3-mount-res)
                                                           :containerPath (get-in config [:s3 :container-mount-path])
                                                           :endpoint (or (:endpoint s3-config) "")
                                                           :bucket (:bucket s3-config)
                                                           :region (or (:region s3-config) "")
                                                           :status "mounted"})
                                container)
                    ttyd-pid (start-ttyd! container)
                    container (assoc container :ttydPid ttyd-pid)
                      ;; 9. 注册 Caddy Shell 规则
                    caddy-rule-id (add-shell-caddy-rule container)
                    container (assoc container :caddyRuleId caddy-rule-id)]

                  ;; 10. 持久化
                (swap! state assoc-in [:containers c-id] container)
                (save-entries!)
                (-> container to-public-container
                    (assoc :gpu gpu?)
                    (cond-> (:warning s3-mount-res) (assoc :s3Warning (:warning s3-mount-res)))))
              (catch Exception e
                  ;; 回滚容器与 FUSE 挂载
                (try (cli/kill-container c-name) (catch Exception _))
                (try (s3/unmount! c-name) (catch Exception _))
                (release-port shell-port)
                (throw e))))
          (catch Exception e
            ;; 回滚 FUSE 挂载
            (try (s3/unmount! c-name) (catch Exception _))
            (release-port shell-port)
            (throw e)))))))

(defn kill-container!
  ([id actor]
   (require-mutations-enabled!)
   (let [c (require-container-access! id actor)]

     (println (format "[docker] 销毁容器: %s" (:name c)))

    ;; 1. Stop ttyd
     (stop-ttyd! c)

    ;; 1.5 容器即将消失，管理员终端记录同步清理
     (stop-webshell-process! (:name c))

    ;; 2. Remove Caddy shell rule
     (when (:caddyRuleId c)
       (try
         (let [parsed (caddy/parse-key (:caddyRuleId c))]
           (when parsed (caddy/remove-rule! (:domain parsed) (:worker parsed) nil true)))
         (catch Exception e
           (println (format "[docker] ⚠️ 移除 Shell Caddy 规则失败: %s" (.getMessage e))))))

    ;; 3. Remove Caddy port rules (domain mode) + IP mode socat cleanup
     (doseq [[_port info] (:ports c)]
      ;; IP mode: stop socat + release host port
       (when (:hostPort info)
         (try (forward/stop-socat! (:hostPort info) "tcp") (catch Exception _))
         (release-port (:hostPort info)))
      ;; Domain mode: remove Caddy rule
       (when (:caddyRuleId info)
         (try
           (let [parsed (caddy/parse-key (:caddyRuleId info))]
             (when parsed (caddy/remove-rule! (:domain parsed) (:worker parsed) nil true)))
           (catch Exception e
             (println (format "[docker] ⚠️ 移除端口 Caddy 规则失败: %s" (.getMessage e)))))))

    ;; 4. Kill container
     (try
       (cli/kill-container (:name c))
       (catch Exception _))

    ;; 5. S3 unmount
     (when (get-in c [:s3Mount :enabled])
       (try
         (s3/unmount! (:name c))
         (catch Exception _)))

    ;; 6. Release port and state cleanup
     (release-port (:shellPort c))
     (swap! state update :containers dissoc id)
     (save-entries!)))
  ([id user-id is-admin]
   (kill-container! id (authorization/actor user-id is-admin))))

;; --- Host Container Discovery & Assignment ---

(defn get-all-host-containers
  "Returns all containers visible via docker ps -a, enriched with app assignment state.
   Each entry: {:name :image :state :status :assignedUser :appManaged :appContainerId :webshell}"
  []
  (try
    (let [host-containers (cli/list-containers)
          app-containers (:containers @state)
          app-by-name (reduce (fn [acc [id c]]
                                (assoc acc (:name c) {:id id :userId (:userId c)}))
                              {}
                              app-containers)]
      (mapv (fn [hc]
              (let [app-c (get app-by-name (:name hc))]
                (merge hc
                       {:assignedUser (:userId app-c)
                        :appManaged (some? app-c)
                        :appContainerId (:id app-c)
                        :webshell (to-public-webshell (get-in @state [:webshells (:name hc)]))})))
            host-containers))
    (catch Exception _
      [])))

(defn- adopt-container-shell [image]
  (get-in config [:images-map image :entry] (:default-shell config)))

(defn assign-container!
  "将现有 Docker 宿主机容器分配给用户。
   创建最小应用记录，并在容器运行时启动 ttyd。
   返回公开容器信息。"
  [container-name user-id]
  (require-mutations-enabled!)
  (when (str/blank? user-id)
    (throw (Exception. (:user-not-selected (:validation i18n/strings)))))

  ;; Quota check
  (let [quotas (auth/get-user-quotas user-id)
        used (count-by-user user-id)]
    (when (>= used (:containers quotas))
      (throw (Exception. ((:container-quota-exhausted (:validation i18n/strings))
                          {:used used :limit (:containers quotas)})))))

  (let [existing (some #(when (= (:name %) container-name) %) (vals (:containers @state)))]
    (when (and existing (not= (:userId existing) user-id))
      (throw (Exception. (:container-already-assigned (:validation i18n/strings)))))
    (when (and existing (= (:userId existing) user-id))
      (throw (Exception. (str "容器 \"" container-name "\" 已分配给当前用户"))))

    (let [info (cli/get-container-info container-name)
          image (:image info)
          shell (adopt-container-shell image)
          running? (cli/container-running? container-name)
          shell-port (allocate-port)
          creds (generate-credentials)
          shell-token (generate-token)
          c-id (or (:appContainerId
                    (some #(when (= (:name %) container-name) %)
                          (for [[id c] (:containers @state)] (assoc c :appContainerId id))))
                   (str (gensym "adopted-")))
          container {:id c-id
                     :name container-name
                     :image image
                     :userId user-id
                     :shellToken shell-token
                     :shellUser (:shellUser creds)
                     :shellPass (:shellPass creds)
                     :shellPort shell-port
                     :shell shell
                     :ttydPid nil
                     :caddyRuleId nil
                     :createdAt (.toString (java.time.Instant/now))
                     :status (if running? "running" "stopped")
                     :cpuLimit "0"
                     :memLimit "0"
                     :gpu false
                     :ports {}}]

      (swap! state assoc-in [:containers c-id] container)
      (save-entries!)

      (when running?
        (try
          (let [ttyd-pid (start-ttyd! container)
                container (assoc container :ttydPid ttyd-pid)
                caddy-rule-id (add-shell-caddy-rule container)
                container (assoc container :caddyRuleId caddy-rule-id)]
            (swap! state assoc-in [:containers c-id] container)
            (save-entries!))
          (catch Exception e
            (println (format "[docker] 认领容器 ttyd 启动失败: %s" (.getMessage e))))))

      (to-public-container (get-in @state [:containers c-id])))))

(defn start-container!
  "启动已授权且已停止的容器，并恢复 ttyd 与 Caddy 规则。"
  ([id actor]
   (require-mutations-enabled!)
   (let [c (require-container-access! id actor)]
     (when (= (:status c) "running")
       (throw (Exception. (:container-already-running (:validation i18n/strings)))))

     (cli/start-container (:name c))
     (let [ttyd-pid (start-ttyd! c)
           caddy-rule-id (add-shell-caddy-rule c)]
       (swap! state assoc-in [:containers id :ttydPid] ttyd-pid)
       (swap! state assoc-in [:containers id :caddyRuleId] caddy-rule-id)
       (swap! state assoc-in [:containers id :status] "running")
       (save-entries!)
       (println (format "[docker] 已启动容器: %s" (:name c)))
       (to-public-container (get-in @state [:containers id])))))
  ([id user-id is-admin]
   (start-container! id (authorization/actor user-id is-admin))))

;; --- Port Proxy Exposure API ---

(defn expose-port!
  ([id port remark]
   (expose-port! id port remark nil))
  ([id port remark actor]
   (require-mutations-enabled!)
   (let [c (require-container-access! id actor)
         port-str (str/trim (str port))]
     (when-not c (throw (Exception. (:container-not-found (:validation i18n/strings)))))
     (when-not (re-matches #"^\d{1,5}$" port-str)
       (throw (Exception. (:port-range-invalid (:validation i18n/strings)))))
     (let [p-val (Integer/parseInt port-str)]
       (when (or (< p-val 1) (> p-val 65535))
         (throw (Exception. (:port-range-invalid (:validation i18n/strings)))))
       (when (get-in c [:ports port-str])
         (throw (Exception. ((:port-already-exposed (:validation i18n/strings)) port-str))))

      ;; Add caddy rule (or socat forward in IP mode)
       (let [res (add-port-caddy-rule c port-str remark)]
         (swap! state assoc-in [:containers id :ports port-str]
                {:caddyRuleId (:caddyRuleId res)
                 :domain (:domain res)
                 :hostPort (:hostPort res)
                 :enabled true
                 :remark (or remark "")})
         (save-entries!)
         (println (format "[docker] 端口已暴露: %s:%s → %s" (:name c) port-str (:domain res)))
         (get-in @state [:containers id :ports port-str]))))))

(defn unexpose-port!
  ([id port]
   (unexpose-port! id port nil))
  ([id port actor]
   (require-mutations-enabled!)
   (let [c (require-container-access! id actor)
         port-str (str/trim (str port))]
     (when-not c (throw (Exception. (:container-not-found (:validation i18n/strings)))))
     (let [p-info (get-in c [:ports port-str])]
       (when-not p-info (throw (Exception. ((:port-not-exposed (:validation i18n/strings)) port-str))))

      ;; IP mode: stop socat + release host port
       (when (:hostPort p-info)
         (try (forward/stop-socat! (:hostPort p-info) "tcp") (catch Exception _))
         (release-port (:hostPort p-info)))

      ;; Domain mode: delete caddy rule
       (when (:caddyRuleId p-info)
         (try
           (let [parsed (caddy/parse-key (:caddyRuleId p-info))]
             (when parsed (caddy/remove-rule! (:domain parsed) (:worker parsed) nil true)))
           (catch Exception _)))

       (swap! state update-in [:containers id :ports] dissoc port-str)
       (save-entries!)
       (println (format "[docker] 取消暴露端口: %s:%s" (:name c) port-str))))))

(defn toggle-port!
  ([id port]
   (toggle-port! id port nil))
  ([id port actor]
   (require-mutations-enabled!)
   (let [c (require-container-access! id actor)
         port-str (str/trim (str port))]
     (when-not c (throw (Exception. (:container-not-found (:validation i18n/strings)))))
     (let [p-info (get-in c [:ports port-str])]
       (when-not p-info (throw (Exception. ((:port-not-exposed (:validation i18n/strings)) port-str))))

       (if (:ip-mode config)
        ;; IP mode: toggle socat
         (if (:enabled p-info)
           (try (forward/stop-socat! (:hostPort p-info) "tcp") (catch Exception _))
           (let [ip (if (cli/can-access-container-ip?)
                      (cli/get-container-ip (:name c))
                      "127.0.0.1")]
             (forward/start-socat! "tcp" (:hostPort p-info) ip (Integer/parseInt port-str))))
        ;; Domain mode: toggle Caddy rule
         (when (:caddyRuleId p-info)
           (let [parsed (caddy/parse-key (:caddyRuleId p-info))]
             (when parsed (caddy/toggle-rule! (:domain parsed) (:worker parsed) nil true)))))

       (let [new-enabled (not (:enabled p-info))]
         (swap! state assoc-in [:containers id :ports port-str :enabled] new-enabled)
         (save-entries!)
         new-enabled)))))

;; --- 启动恢复与状态刷新 ---

(defn refresh-status! [id]
  (let [c (get-in @state [:containers id])]
    (when c
      (let [c-running (cli/container-running? (:name c))
            ttyd-required? (:ttyd-enabled config false)
            status (cond
                     (not c-running) "stopped"
                     (and ttyd-required? (not (ttyd-alive? c))) "error"
                     :else "running")]
        (swap! state assoc-in [:containers id :status] status)
        (when-not ttyd-required?
          (swap! state assoc-in [:containers id :ttydPid] nil))
        status))))

(defn- failure [stage container-id container-name e]
  (cond-> {:stage stage
           :message (.getMessage e)}
    container-id (assoc :containerId container-id)
    container-name (assoc :containerName container-name)))

(defn- remove-caddy-rule! [rule-id]
  (let [parsed (caddy/parse-key rule-id)]
    (when-not parsed
      (throw (ex-info "无法解析待回滚的 Caddy 规则" {:ruleId rule-id})))
    (caddy/remove-rule! (:domain parsed) (:worker parsed) nil true)))

(defn- rollback-recovery! [actions]
  (reduce (fn [failures action]
            (try
              (case (:kind action)
                :ttyd (do
                        (swap! state update :ttyd-procs dissoc (:containerId action))
                        (process/destroy! (:proc action)))
                :socat (forward/stop-socat! (:hostPort action) "tcp")
                :caddy (remove-caddy-rule! (:ruleId action)))
              failures
              (catch Exception e
                (conj failures
                      (failure :rollback
                               (:containerId action)
                               (:containerName action)
                               e)))))
          []
          (reverse actions)))

(defn- track-action! [actions action]
  (swap! actions conj action))

(defn- restore-shell-access! [id c actions]
  (if-not (:ttyd-enabled config false)
    (do
      (swap! state assoc-in [:containers id :ttydPid] nil)
      (swap! state assoc-in [:containers id :status] "running"))
    (do
      (when-not (ttyd-alive? c)
        (let [ttyd-pid (start-ttyd! c)
              proc (get-in @state [:ttyd-procs id :proc])]
          (track-action! actions {:kind :ttyd
                                  :containerId id
                                  :containerName (:name c)
                                  :proc proc})
          (swap! state assoc-in [:containers id :ttydPid] ttyd-pid)))
      (when-not (:ip-mode config)
        (let [rule-id (:caddyRuleId c)]
          (when-not (and rule-id
                         (get-in @caddy/state [:entries (keyword rule-id)]))
            (let [new-rule-id (add-shell-caddy-rule c)]
              (track-action! actions {:kind :caddy
                                      :containerId id
                                      :containerName (:name c)
                                      :ruleId new-rule-id})
              (swap! state assoc-in [:containers id :caddyRuleId] new-rule-id)))))
      (swap! state assoc-in [:containers id :status] "running"))))

(defn- restore-port-access! [id c port info actions]
  (when (:enabled info)
    (if (:ip-mode config)
      (let [host-port (:hostPort info)]
        (when-not host-port
          (throw (ex-info "启用的端口记录缺少 hostPort"
                          {:containerId id :port port})))
        (let [ip (if (cli/can-access-container-ip?)
                   (cli/get-container-ip-strict (:name c))
                   "127.0.0.1")]
          (when (str/blank? ip)
            (throw (ex-info "容器 IP 为空" {:containerId id :port port})))
          (forward/start-socat! "tcp" host-port ip (Integer/parseInt (str port)))
          (track-action! actions {:kind :socat
                                  :containerId id
                                  :containerName (:name c)
                                  :hostPort host-port})))
      (let [rule-id (:caddyRuleId info)]
        (when-not (and rule-id
                       (get-in @caddy/state [:entries (keyword rule-id)]))
          (let [result (add-port-caddy-rule c (str port) (:remark info))
                new-rule-id (:caddyRuleId result)]
            (when-not new-rule-id
              (throw (ex-info "端口访问恢复未生成 Caddy 规则"
                              {:containerId id :port port})))
            (track-action! actions {:kind :caddy
                                    :containerId id
                                    :containerName (:name c)
                                    :ruleId new-rule-id})
            (swap! state assoc-in [:containers id :ports port :caddyRuleId]
                   new-rule-id)))))))

(defn- restore-container! [id c actions]
  ;; container-running-strict? 会在容器被宿主机删除时抛出；
  ;; 这里捕获后视为"未运行"，避免一个已消失的容器阻塞整体启动恢复。
  (let [running? (try (cli/container-running-strict? (:name c))
                      (catch Exception _
                        (println (format "[docker] 容器 %s 已不存在于宿主机，标记为停止" (:name c)))
                        false))]
    (if-not running?
      (do
        (swap! state assoc-in [:containers id :status] "stopped")
        (swap! state assoc-in [:containers id :ttydPid] nil))
      (do
        (restore-shell-access! id c actions)
        (doseq [[port info] (:ports c)]
          (restore-port-access! id c port info actions))))))

(defn- throw-startup-recovery-failed! [failures cause]
  (throw (ex-info "Docker 启动恢复失败"
                  {:type ::startup-recovery-failed
                   :failures failures}
                  cause)))

(defn sync-containers! []
  (load-entries!)
  (let [containers (:containers @state)]
    (if-not (:docker-mutations-enabled config false)
      (when (seq containers)
        (throw-startup-recovery-failed!
         [{:stage :feature-disabled
           :message "Docker 变更功能未启用；请先迁移或清空 Docker 持久数据后再启动"}]
         nil))
      (if-not (cli/available?)
        (when (seq containers)
          (throw-startup-recovery-failed!
           [{:stage :cli-availability
             :message "容器 CLI 不可用，无法恢复已有容器"}]
           nil))
        (let [snapshot @state
              actions (atom [])
              failures (atom [])]
          (try
            (s3/sync-mounts! containers)
            (catch Exception e
              (swap! failures conj (failure :s3 nil nil e))))
          (doseq [[id c] containers]
            (try
              (restore-container! id c actions)
              (catch Exception e
                (swap! failures conj (failure :container id (:name c) e)))))
          (if (seq @failures)
            (let [rollback-failures (rollback-recovery! @actions)
                  all-failures (into @failures rollback-failures)]
              (reset! state snapshot)
              (throw-startup-recovery-failed! all-failures nil))
            (do
              ;; 终端会话恢复是尽力而为：失败不阻止启动（见 restore-webshells!），
              ;; 两者都在本次统一持久化。
              (restore-webshells!)
              (save-entries!))))))))

(defn shutdown! []
  (println "[docker-shutdown] 停止所有容器 ttyd 与终端会话进程...")
  (doseq [c (vals (:containers @state))]
    (stop-ttyd! c))
  (stop-webshells!))
