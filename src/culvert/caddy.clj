(ns culvert.caddy
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [culvert.authorization :as authorization]
            [culvert.config :refer [config]]
            [culvert.db :as db]
            [culvert.i18n :as i18n]
            [culvert.runtime.process :as process])
  (:import [java.net Inet4Address Inet6Address InetAddress URI]
           [java.nio.file CopyOption Files StandardCopyOption]))

;; ============================================================
;; Caddy 反向代理规则管理器
;; ============================================================

(defonce state (atom {:entries {}
                      :caddy-proc nil}))

(def ^:private mutation-lock (Object.))

(def caddy-path (:caddy-path config))
(def caddyfile-path (:caddyfile-path config))
(def legacy-user (or (:auth-user config) "admin"))

(defn available? []
  (try
    (let [res (process/run [caddy-path "version"])]
      (zero? (:exit res)))
    (catch Exception _
      false)))

;; --- 键工具 ---

(defn build-key [domain worker]
  (str domain "@@" worker))

(defn parse-key [key]
  (let [idx (.indexOf key "@@")]
    (when-not (= idx -1)
      {:domain (.substring key 0 idx)
       :worker (.substring key (+ idx 2))})))

;; --- 数据库操作 ---

(defn load-entries! []
  (locking mutation-lock
    (let [data (db/read-json (:caddy-db-path config) {})
          ;; 迁移不含 @@ 分隔符的旧键，并为缺失所有者的记录补充 legacyUser
          migrated (reduce (fn [acc [k entry]]
                             (let [k-str (name k)
                                   entry-updated (assoc entry :userId (or (:userId entry) legacy-user))]
                               (if-not (.contains k-str "@@")
                                 (let [new-key (build-key (:caddy-domain config) k-str)]
                                   (assoc acc (keyword new-key) entry-updated))
                                 (assoc acc k entry-updated))))
                           {}
                           data)]
      (when-not (= data migrated)
        (db/write-json (:caddy-db-path config) migrated))
      (swap! state assoc :entries migrated)
      (println (format "[caddy] 已加载 %d 条代理配置记录" (count migrated))))))

(defn save-entries!
  ([]
   (save-entries! (:entries @state)))
  ([entries]
   (db/write-json (:caddy-db-path config) entries)))

;; --- Caddyfile 生成 ---

(def ^:private metadata-hosts
  #{"instance-data.ec2.internal"
    "metadata.google.internal"
    "metadata.google"
    "metadata.azure.internal"
    "metadata.tencentyun.com"
    "metadata.ksyun.com"})

(def ^:private metadata-addresses
  #{"100.100.100.200"
    "192.0.0.192"
    "fd00:ec2::254"
    "fd00:ec2:0:0:0:0:0:254"})

(defn- invalid-target! []
  (throw (Exception. (:target-invalid (:validation i18n/strings)))))

(defn- whitespace-or-control? [s]
  (some (fn [ch]
          (or (Character/isWhitespace ch)
              (Character/isISOControl ch)))
        s))

(defn- canonical-ipv4-literal? [host]
  (when (re-matches #"[0-9]+(?:\.[0-9]+){3}" host)
    (let [parts (str/split host #"\.")]
      (and (every? #(or (= "0" %) (re-matches #"[1-9][0-9]{0,2}" %)) parts)
           (every? #(<= (Long/parseLong %) 255) parts)))))

(defn- noncanonical-numeric-host? [host]
  (and (re-matches #"[0-9.]+" host)
       (not (canonical-ipv4-literal? host))))

(defn resolve-host-addresses
  "解析目标主机的全部地址；独立保留以便校验测试无需真实 DNS。"
  [host]
  (seq (InetAddress/getAllByName host)))

(defn- ipv4-mapped-address? [^InetAddress address]
  (and (instance? Inet6Address address)
       (let [bytes (.getAddress address)]
         (and (every? zero? (take 10 bytes))
              (= -1 (aget bytes 10))
              (= -1 (aget bytes 11))))))

(defn unsafe-target-address?
  "纯地址策略。loopback 和私网地址仅供受控内部代理使用。"
  [^InetAddress address]
  (let [normalized (.getHostAddress address)]
    (or (.isAnyLocalAddress address)
        (.isLinkLocalAddress address)
        (.isMulticastAddress address)
        (ipv4-mapped-address? address)
        (contains? metadata-addresses normalized))))

(defn- normalize-host [host]
  (some-> host
          str/lower-case
          (str/replace #"^\[|\]$" "")
          (str/replace #"\.$" "")))

(defn- valid-authority? [^URI uri]
  (let [authority (.getRawAuthority uri)
        host (normalize-host (.getHost uri))
        ipv6? (str/includes? host ":")
        pattern (if ipv6?
                  #"^\[([^\]]+)\](?::([0-9]+))?$"
                  #"^([^:]+)(?::([0-9]+))?$")
        [_ authority-host port-text] (re-matches pattern authority)]
    (and authority-host
         (.equalsIgnoreCase host authority-host)
         (if port-text
           (try
             (let [port (Long/parseLong port-text)]
               (<= 1 port 65535))
             (catch NumberFormatException _
               false))
           true))))

(defn validate-target! [target]
  (let [target (if (string? target) target "")]
    (when (or (str/blank? target)
              (not= target (str/trim target))
              (whitespace-or-control? target))
      (invalid-target!))
    (try
      (let [uri (URI. target)
            scheme (some-> (.getScheme uri) str/lower-case)
            host (.getHost uri)
            normalized-host (normalize-host host)]
        (when (or (.isOpaque uri)
                  (not (#{"http" "https"} scheme))
                  (nil? host)
                  (str/blank? host)
                  (some? (.getRawUserInfo uri))
                  (whitespace-or-control? (.getSchemeSpecificPart uri))
                  (not (valid-authority? uri))
                  (contains? metadata-hosts normalized-host)
                  (noncanonical-numeric-host? normalized-host))
          (invalid-target!))
        (let [addresses (resolve-host-addresses normalized-host)]
          (when (or (empty? addresses)
                    (and (str/includes? normalized-host ":")
                         (some #(instance? Inet4Address %) addresses))
                    (some unsafe-target-address? addresses))
            (invalid-target!)))
        target)
      (catch java.net.URISyntaxException _
        (invalid-target!))
      (catch java.net.UnknownHostException _
        (invalid-target!))
      (catch IllegalArgumentException _
        (invalid-target!)))))

(defn generate-caddyfile
  ([]
   (generate-caddyfile (:entries @state)))
  ([entries]
   (let [enabled-entries (filter (fn [[_ e]] (:enabled e)) entries)]
     (doseq [[_ entry] enabled-entries]
       (validate-target! (:target entry)))
     (if (empty? enabled-entries)
       "# Auto-generated by culvert — 暂无启用条目\n"
       (let [header (format "# Auto-generated by culvert — %s\n\n" (.toString (java.time.Instant/now)))]
         (str header
              (str/join "\n"
                        (map (fn [[k entry]]
                               (let [parsed (parse-key (name k))
                                     domain (str (:worker parsed) "." (:domain parsed))]
                                 (str "http://" domain " {\n"
                                      "    reverse_proxy " (:target entry) "\n"
                                      "}\n")))
                             enabled-entries))))))))

(defn- replace-file! [source target]
  (Files/move (.toPath source)
              (.toPath target)
              (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])))

(defn write-caddyfile!
  ([]
   (write-caddyfile! (:entries @state)))
  ([entries]
   (let [content (generate-caddyfile entries)
         target-file (io/file caddyfile-path)
         parent (or (.getParentFile target-file) (io/file "."))
         candidate (java.io.File/createTempFile ".caddyfile-" ".tmp" parent)]
     (try
       (spit candidate content)
       (let [res (process/run [caddy-path "validate" "--config" (.getAbsolutePath candidate)
                               "--adapter" "caddyfile"])]
         (when-not (zero? (:exit res))
           (throw (ex-info (str "Caddy 配置校验失败: "
                                (str/trim (or (:err res) (:out res) "")))
                           {:type ::config-validation-failed})))
         (replace-file! candidate target-file)
         (println "[caddy] Caddyfile 已更新"))
       (finally
         (when (.exists candidate)
           (.delete candidate)))))))

;; --- Caddy 进程控制 ---

(defn stop-caddy! []
  (when-let [proc (:caddy-proc @state)]
    (process/destroy! proc)
    (swap! state assoc :caddy-proc nil)
    (println "[caddy] 已停止旧 Caddy 进程")))

(defn- enabled-entries?
  ([]
   (enabled-entries? (:entries @state)))
  ([entries]
   (boolean (some :enabled (vals entries)))))

(defn- require-caddy-available! [available]
  (when-not available
    (throw (ex-info "存在启用的域名代理规则，但 Caddy 不可用"
                    {:type ::caddy-unavailable}))))

(defn- start-installed-caddy! []
  (stop-caddy!)
  (let [proc (process/spawn [caddy-path "run" "--config" caddyfile-path
                             "--adapter" "caddyfile"]
                            {:out :inherit :err :inherit})]
    (swap! state assoc :caddy-proc proc)
    (println (format "[caddy] Caddy 进程已拉起 pid=%d" (process/pid proc)))))

(defn start-caddy! []
  (let [has-enabled (enabled-entries?)
        available (available?)]
    (when has-enabled
      (require-caddy-available! available))
    (when available
      (write-caddyfile!)
      (start-installed-caddy!))))

(defn- reload-installed-caddy! [entries]
  (if-not (enabled-entries? entries)
    (do
      (println "[caddy] 无启用条目，跳过 Caddy 启动/重载")
      (stop-caddy!))
    (do
      (require-caddy-available! (available?))
      (let [reload-result (try
                            (process/run [caddy-path "reload" "--config" caddyfile-path
                                          "--adapter" "caddyfile"])
                            (catch Exception error
                              error))]
        (if (and (map? reload-result) (zero? (:exit reload-result)))
          (println "[caddy] 配置已重载完成")
          (do
            (println "[caddy] reload 失败，尝试启动已安装配置："
                     (if (map? reload-result)
                       (str/trim (or (:err reload-result) (:out reload-result) ""))
                       (.getMessage ^Exception reload-result)))
            (start-installed-caddy!)))))))

(defn reload-caddy!
  ([]
   (reload-caddy! (:entries @state)))
  ([entries]
   (when (enabled-entries? entries)
     (require-caddy-available! (available?)))
   (write-caddyfile! entries)
   (reload-installed-caddy! entries)))

;; --- 规则管理（CRUD）---

(defn- caddyfile-snapshot []
  (let [file (io/file caddyfile-path)]
    {:exists? (.exists file)
     :content (when (.exists file) (slurp file))}))

(defn- restore-caddyfile! [{:keys [exists? content]}]
  (let [file (io/file caddyfile-path)]
    (if exists?
      (spit file content)
      (Files/deleteIfExists (.toPath file)))))

(defn- compensate-mutation! [caddyfile-before entries-before persisted?]
  (restore-caddyfile! caddyfile-before)
  (reload-installed-caddy! entries-before)
  (when persisted?
    (save-entries! entries-before))
  (swap! state assoc :entries entries-before))

(defn- durable-mutation! [candidate-fn]
  (locking mutation-lock
    (let [entries-before (:entries @state)
          entries-after (candidate-fn entries-before)
          caddyfile-before (caddyfile-snapshot)
          installed? (volatile! false)
          persisted? (volatile! false)]
      (try
        (write-caddyfile! entries-after)
        (vreset! installed? true)
        (reload-installed-caddy! entries-after)
        (save-entries! entries-after)
        (vreset! persisted? true)
        (swap! state assoc :entries entries-after)
        entries-after
        (catch Exception failure
          (if-not @installed?
            (throw failure)
            (do
              (try
                (compensate-mutation! caddyfile-before entries-before @persisted?)
                (catch Exception compensation-failure
                  (throw (ex-info "Caddy mutation 失败且补偿失败"
                                  {:type ::mutation-compensation-failed
                                   :failure-type (or (:type (ex-data failure))
                                                     ::mutation-failed)
                                   :compensation-type (or (:type (ex-data compensation-failure))
                                                          ::compensation-failed)}
                                  compensation-failure))))
              (throw failure))))))))

(defn- require-rule-access! [entry actor]
  (authorization/require-resource-access!
   actor (:userId entry) legacy-user
   (:no-permission-proxy (:validation i18n/strings))))

(defn- canonical-caddy-actor [actor-or-username]
  (authorization/require-actor!
   (if (string? actor-or-username)
     (authorization/actor actor-or-username false)
     actor-or-username)))

(defn- add-rule-for-owner! [domain worker target remark username]
  (let [d (str/lower-case (str/trim (or domain "")))
        w (str/lower-case (str/trim (or worker "")))]
    (when (str/blank? d) (throw (Exception. (:domain-empty (:validation i18n/strings)))))
    (when-not (some #(= d %) (:caddy-domains config))
      (throw (Exception. ((:domain-not-allowed (:validation i18n/strings)) d))))
    (when (str/blank? w) (throw (Exception. (:worker-empty (:validation i18n/strings)))))
    (when-not (re-matches #"^[a-z0-9]([a-z0-9-]*[a-z0-9])?$" w)
      (throw (Exception. (:worker-format (:validation i18n/strings)))))
    (let [validated-target (validate-target! target)
          k-str (build-key d w)
          k-kw (keyword k-str)]
      (durable-mutation!
       (fn [entries]
         (when (get entries k-kw)
           (throw (Exception. ((:proxy-exists (:validation i18n/strings)) {:worker w :domain d}))))
         (assoc entries k-kw
                {:target validated-target
                 :enabled true
                 :remark (str/trim (or remark ""))
                 :userId username})))
      (println (format "[caddy] 已添加代理规则 %s.%s → %s" w d target)))))

(defn add-rule!
  "添加管理员批准的任意目标代理规则。"
  [domain worker target remark actor]
  (let [{:keys [username admin?]} (canonical-caddy-actor actor)]
    (when-not admin?
      (throw (ex-info "仅管理员可添加任意目标代理规则"
                      {:type ::admin-required :username username})))
    (add-rule-for-owner! domain worker target remark username)))

(defn add-managed-rule!
  "为系统已授权的 Docker/SmolVM 资源添加内部代理规则。"
  [domain worker target remark owner]
  (when (str/blank? owner)
    (throw (ex-info "受管代理规则必须指定资源所有者" {:type ::owner-required})))
  (add-rule-for-owner! domain worker target remark owner))

(defn remove-rule!
  ([domain worker actor]
   (let [actor (canonical-caddy-actor actor)
         d (str/lower-case (str/trim (or domain "")))
         w (str/lower-case (str/trim (or worker "")))
         k-str (build-key d w)
         k-kw (keyword k-str)]
     (durable-mutation!
      (fn [entries]
        (let [entry (get entries k-kw)]
          (when-not entry
            (throw (Exception. ((:proxy-not-found (:validation i18n/strings)) {:worker w :domain d}))))
          (require-rule-access! entry actor)
          (dissoc entries k-kw))))
     (println (format "[caddy] 已移除代理规则 %s.%s" w d))))
  ([domain worker username is-admin]
   (remove-rule! domain worker (authorization/actor username is-admin))))

(defn toggle-rule!
  ([domain worker actor]
   (let [actor (canonical-caddy-actor actor)
         d (str/lower-case (str/trim (or domain "")))
         w (str/lower-case (str/trim (or worker "")))
         k-str (build-key d w)
         k-kw (keyword k-str)
         new-entries (durable-mutation!
                      (fn [entries]
                        (let [entry (get entries k-kw)]
                          (when-not entry
                            (throw (Exception. ((:proxy-not-found (:validation i18n/strings)) {:worker w :domain d}))))
                          (require-rule-access! entry actor)
                          (update-in entries [k-kw :enabled] not))))
         new-val (get-in new-entries [k-kw :enabled])]
     (println (format "[caddy] 代理 %s.%s %s" w d (if new-val "已启用" "已停用")))
     new-val))
  ([domain worker username is-admin]
   (toggle-rule! domain worker (authorization/actor username is-admin))))

(defn update-remark!
  ([domain worker remark actor]
   (let [actor (canonical-caddy-actor actor)
         d (str/lower-case (str/trim (or domain "")))
         w (str/lower-case (str/trim (or worker "")))
         k-str (build-key d w)
         k-kw (keyword k-str)]
     (durable-mutation!
      (fn [entries]
        (let [entry (get entries k-kw)]
          (when-not entry
            (throw (Exception. ((:proxy-not-found (:validation i18n/strings)) {:worker w :domain d}))))
          (require-rule-access! entry actor)
          (assoc-in entries [k-kw :remark] (str/trim (or remark ""))))))))
  ([domain worker remark username is-admin]
   (update-remark! domain worker remark (authorization/actor username is-admin))))

;; --- 查询 ---

(defn count-by-user [user-id]
  (count (filter (fn [[_ entry]]
                   (= (:userId entry legacy-user) user-id))
                 (:entries @state))))

(defn get-entries
  ([actor]
   (let [actor (canonical-caddy-actor actor)
         all-entries (->> (:entries @state)
                          (sort-by (fn [[k _]] (name k)))
                          (map (fn [[k entry]]
                                 (let [parsed (parse-key (name k))
                                       worker (:worker parsed (name k))
                                       domain (:domain parsed "")]
                                   {:key (name k)
                                    :domain domain
                                    :worker worker
                                    :fullDomain (if (seq domain) (str worker "." domain) worker)
                                    :target (:target entry)
                                    :enabled (:enabled entry)
                                    :remark (:remark entry "")
                                    :userId (:userId entry legacy-user)}))))]
     (filter #(authorization/authorized? actor (:userId %) legacy-user) all-entries)))
  ([username is-admin]
   (get-entries (authorization/actor username is-admin))))

;; --- 生命周期钩子 ---

(defn sync-caddy! []
  (load-entries!)
  (cond
    (:ip-mode config)
    (println "[caddy] IP 直连模式，跳过 Caddy 启动")

    (not (enabled-entries?))
    (println "[caddy] 无启用代理规则，跳过 Caddy 启动")

    :else
    (try
      (start-caddy!)
      (catch Exception e
        (throw (ex-info "Caddy 启动恢复失败"
                        {:type ::startup-recovery-failed
                         :failures [{:type (or (:type (ex-data e)) ::caddy-start-failed)
                                     :message (.getMessage e)}]}
                        e))))))

(defn shutdown! []
  (println "[caddy-shutdown] 停止 Caddy 反向代理...")
  (stop-caddy!))
