(ns culvert.forward
  (:require [clojure.string :as str]
            [culvert.config :refer [config]]
            [culvert.db :as db]
            [culvert.i18n :as i18n]
            [culvert.log :as log]
            [culvert.runtime.process :as process]))

;; ============================================================
;; 端口转发管理器（socat / iptables）
;; ============================================================

(defonce state (atom {:port-db {}
                      :kill-db {}})) ; 键 -> {:pid pid :proc proc}

(def ^:private mutation-lock (Object.))

(def legacy-user (or (:auth-user config) "admin"))

;; --- 端口校验与解析 ---

(defn validate-port [value label]
  (try
    (let [n (Integer/parseInt (str/trim (str value)))]
      (if (and (>= n 1) (<= n 65535))
        nil
        ((:port-must-be-int (:validation i18n/strings)) label)))
    (catch Exception _
      ((:port-must-be-int (:validation i18n/strings)) label))))

(defn parse-port-range [port-str]
  (let [s (str/trim (str port-str))]
    (if (.contains s "-")
      (let [parts (str/split s #"-")]
        (if-not (= (count parts) 2)
          (throw (Exception. (:port-range-format-invalid (:validation i18n/strings))))
          (let [from-port (Integer/parseInt (nth parts 0))
                to-range-port (Integer/parseInt (nth parts 1))]
            (when (or (< from-port 1) (> from-port 65535) (< to-range-port 1) (> to-range-port 65535))
              (throw (Exception. (:port-must-be-in-range (:validation i18n/strings)))))
            (when (>= from-port to-range-port)
              (throw (Exception. (:port-start-must-be-less (:validation i18n/strings)))))
            {:isRange true :fromPort from-port :toRangePort to-range-port})))
      (let [port (Integer/parseInt s)]
        (when (or (< port 1) (> port 65535))
          (throw (Exception. (:port-must-be-in-range (:validation i18n/strings)))))
        {:isRange false :fromPort port :toRangePort port}))))

(defn validate-port-input [value label]
  (let [s (str/trim (str (or value "")))]
    (if (str/blank? s)
      ((:field-cannot-be-empty (:validation i18n/strings)) label)
      (if (.contains s "-")
        (let [parts (str/split s #"-")]
          (if-not (= (count parts) 2)
            ((:field-format-invalid (:validation i18n/strings)) label)
            (if-let [from-err (validate-port (nth parts 0) (str label "起始端口"))]
              from-err
              (if-let [to-err (validate-port (nth parts 1) (str label "结束端口"))]
                to-err
                (if (>= (Integer/parseInt (nth parts 0)) (Integer/parseInt (nth parts 1)))
                  (str label (:port-start-must-be-less (:validation i18n/strings)))
                  nil)))))
        (validate-port s label)))))

;; --- 键工具 ---

(defn build-port-key [port protocol method]
  (let [port-str (str port)]
    (if (= method "iptables")
      (str port-str "_" protocol "_ipt")
      (str port-str "_" protocol))))

(defn parse-port-key [key-str]
  (let [parts (str/split key-str #"_")
        is-ipt (and (>= (count parts) 3) (= (last parts) "ipt"))]
    (if is-ipt
      (let [proto (nth parts (- (count parts) 2))
            port-str (str/join "_" (drop-last 2 parts))]
        (if (.contains port-str "-")
          (let [[from to] (str/split port-str #"-")]
            {:port port-str
             :fromPort (Integer/parseInt from)
             :toRangePort (Integer/parseInt to)
             :protocol proto
             :method "iptables"
             :isRange true})
          {:port port-str
           :protocol proto
           :method "iptables"
           :isRange false}))
      (let [proto (last parts)
            port-str (str/join "_" (drop-last parts))]
        (if (.contains port-str "-")
          (let [[from to] (str/split port-str #"-")]
            {:port port-str
             :fromPort (Integer/parseInt from)
             :toRangePort (Integer/parseInt to)
             :protocol proto
             :method "socat"
             :isRange true})
          {:port port-str
           :protocol proto
           :method "socat"
           :isRange false})))))

(defn parse-port-entry [key-str val]
  (let [parsed (parse-port-key key-str)
        raw-val (if (string? val) val (str (:ip val) ":" (:toPort val)))
        enabled (if (map? val) (not= (:enabled val) false) true)
        remark (if (map? val) (or (:remark val) "") "")
        user-id (if (map? val) (or (:userId val) legacy-user) legacy-user)
        [ip to-port] (str/split raw-val #":")]
    (assoc parsed
           :ip ip
           :toPort to-port
           :enabled enabled
           :remark remark
           :userId user-id)))

;; --- 所有权辅助函数 ---

(defn- check-ownership [port-db key-str user-id is-admin]
  (when-not is-admin
    (when-let [entry (get port-db (keyword key-str))]
      (let [parsed (parse-port-entry key-str entry)
            owner (:userId parsed)]
        (when (or (str/blank? user-id)
                  (not= owner user-id))
          (throw (Exception. (:no-permission-forward (:validation i18n/strings)))))))))

;; --- 数据库操作 ---

(defn load-entries! []
  (locking mutation-lock
    (let [data (db/read-json (:db-path config) {})
          migrated (reduce (fn [acc [k val]]
                             (let [entry-updated (if (map? val)
                                                   (assoc val :userId (or (:userId val) legacy-user))
                                                   {:ip (first (str/split val #":"))
                                                    :toPort (Integer/parseInt (second (str/split val #":")))
                                                    :enabled true
                                                    :remark ""
                                                    :userId legacy-user})]
                               (assoc acc k entry-updated)))
                           {}
                           data)]
      (when-not (= data migrated)
        (db/write-json (:db-path config) migrated))
      (swap! state assoc :port-db migrated)
      (log/info (format "已加载 %d 条端口转发规则" (count migrated))))))

(defn save-entries! []
  (locking mutation-lock
    (db/write-json (:db-path config) (:port-db @state))))

;; --- socat 生命周期控制 ---

(defn stop-socat! [port protocol]
  (let [k-str (build-port-key port protocol "socat")
        k-kw (keyword k-str)
        entry (get-in @state [:kill-db k-kw])]
    (when entry
      (process/destroy! (:proc entry))
      (swap! state update :kill-db dissoc k-kw) ; 标记为手动停止
      (log/info (format "已停止 %s/%s (pid %d)" port protocol (:pid entry))))))

(defn start-socat! [protocol port ip to-port]
  (let [socat-proto (if (= protocol "tcp") "TCP" "UDP")
        k-str (build-port-key port protocol "socat")
        k-kw (keyword k-str)
        max-restarts (:socat-max-restarts config)
        base-delay (:socat-restart-base-delay-ms config)]

    (letfn [(do-start [restart-count]
              (let [cmd [(:socat-path config)
                         (format "%s-LISTEN:%s,bind=0.0.0.0,reuseaddr,fork" socat-proto (str port))
                         (format "%s:%s:%s,reuseaddr" socat-proto ip (str to-port))]
                    proc (process/spawn cmd {:out :inherit :err :inherit})
                    pid (process/pid proc)]
                (swap! state assoc-in [:kill-db k-kw] {:pid pid :proc proc})
                (log/info (format "已启动 %s/%s → %s:%s (pid %d)" protocol port ip to-port pid))

                (future
                  (let [exit-code (process/wait proc)]
                    (log/warn (format "已退出，pid %s，退出码 %s" pid exit-code))
                    ;; 仅当此进程仍登记为活动转发规则时自动重启
                    (when (= (get-in @state [:kill-db k-kw :pid]) pid)
                      (swap! state update :kill-db dissoc k-kw)
                      (if (< restart-count max-restarts)
                        (let [delay (* base-delay (long (Math/pow 2 restart-count)))]
                          (log/info (format "%dms 后重启 %s/%s (%d/%d)"
                                            delay protocol port (inc restart-count) max-restarts))
                          (Thread/sleep delay)
                          ;; 确认尚未注册替代进程
                          (when-not (get-in @state [:kill-db k-kw])
                            (do-start (inc restart-count))))
                        (log/error (format "%s/%s 已达最大重试次数 %d，放弃重启"
                                           protocol port max-restarts))))))))]
      (do-start 0))))

;; --- iptables CLI 包装 ---

(defn- run-iptables [args]
  (process/run (into [(:iptables-path config)] (map str) args)
               {:check? true :timeout-ms 10000}))

(defn- iptables-rule-args [action protocol from-port to-ip to-port]
  [;; PREROUTING：外部客户端流量
   ["-t" "nat" action "PREROUTING" "-p" protocol "-d" (:public-ip config)
    "--dport" (str from-port) "-j" "DNAT" "--to-destination" (str to-ip ":" to-port)]
   ;; OUTPUT：本机流量
   ["-t" "nat" action "OUTPUT" "-p" protocol "-d" (:public-ip config)
    "--dport" (str from-port) "-j" "DNAT" "--to-destination" (str to-ip ":" to-port)]])

(defn- throw-iptables-compensation-failed [operation]
  (throw (ex-info "iptables 操作补偿失败"
                  {:type ::iptables-compensation-failed
                   :phase :rollback
                   :failures [{:phase :rollback
                               :operation operation}]})))

(defn- run-iptables-pair! [operation protocol from-port to-ip to-port]
  (let [action (if (= operation :add) "-A" "-D")
        rollback-action (if (= operation :add) "-D" "-A")
        [first-rule second-rule] (iptables-rule-args action protocol from-port to-ip to-port)
        [rollback-first] (iptables-rule-args rollback-action protocol from-port to-ip to-port)]
    (run-iptables first-rule)
    (try
      (run-iptables second-rule)
      (catch Exception cause
        (try
          (run-iptables rollback-first)
          (catch Exception _
            (throw-iptables-compensation-failed operation)))
        (throw cause)))))

(defn iptables-add! [protocol from-port to-ip to-port]
  (run-iptables-pair! :add protocol from-port to-ip to-port)
  (log/info (format "已添加 %s/%s → %s:%s" protocol from-port to-ip to-port)))

(defn iptables-remove! [protocol from-port to-ip to-port]
  (run-iptables-pair! :remove protocol from-port to-ip to-port)
  (log/info (format "已移除 %s/%s → %s:%s" protocol from-port to-ip to-port)))

;; --- 高层增删改操作 ---

(defn- port-effects [port-str protocol method ip start-to-port]
  (let [{:keys [fromPort toRangePort]} (parse-port-range port-str)]
    (mapv (fn [port]
            {:port port
             :protocol protocol
             :method method
             :ip ip
             :target-port (+ start-to-port (- port fromPort))})
          (range fromPort (inc toRangePort)))))

(defn- apply-port! [{:keys [port protocol method ip target-port]}]
  (if (= method "socat")
    (start-socat! protocol port ip target-port)
    (iptables-add! protocol port ip target-port)))

(defn- remove-port! [{:keys [port protocol method ip target-port]}]
  (if (= method "socat")
    (stop-socat! port protocol)
    (iptables-remove! protocol port ip target-port)))

(defn- mutation-failure [phase {:keys [port protocol method]}]
  (cond-> {:phase phase}
    port (assoc :port port)
    protocol (assoc :protocol protocol)
    method (assoc :method method)))

(defn- rollback-effects! [rollback! completed]
  (reduce (fn [failures effect]
            (try
              (rollback! effect)
              failures
              (catch Exception _
                (conj failures (mutation-failure :rollback effect)))))
          []
          (reverse completed)))

(defn- throw-mutation-compensation-failed [failures]
  (throw (ex-info "端口转发变更补偿失败"
                  {:type ::mutation-compensation-failed
                   :phase :rollback
                   :failures failures})))

(defn- run-effects! [effects apply! rollback!]
  (loop [remaining effects
         completed []]
    (if-let [effect (first remaining)]
      (let [cause (try
                    (apply! effect)
                    nil
                    (catch Exception error
                      error))]
        (if cause
          (let [failures (rollback-effects! rollback! completed)]
            (if (seq failures)
              (throw-mutation-compensation-failed failures)
              (throw cause)))
          (recur (next remaining) (conj completed effect))))
      completed)))

(defn- persist-candidate! [candidate completed rollback!]
  (try
    (db/write-json (:db-path config) candidate)
    (catch Exception cause
      (let [failures (rollback-effects! rollback! completed)]
        (if (seq failures)
          (throw-mutation-compensation-failed failures)
          (throw cause)))))
  (swap! state assoc :port-db candidate))

(defn add-forward! [port-str protocol ip to-port method remark user-id]
  (locking mutation-lock
    (let [k-str (build-port-key port-str protocol method)
          k-kw (keyword k-str)
          port-db (:port-db @state)]
      (when (get port-db k-kw)
        (throw (Exception. (:port-already-forwarded (:validation i18n/strings)))))
      (let [{:keys [fromPort toRangePort]} (parse-port-range port-str)
            port-range (:port-forward-range config)
            start-to-port (Integer/parseInt (str to-port))]
        (when (or (< fromPort (:start port-range)) (> toRangePort (:end port-range)))
          (throw (Exception. ((:port-range-outside (:validation i18n/strings))
                              {:start (:start port-range) :end (:end port-range)}))))
        (let [entry {:ip ip
                     :toPort start-to-port
                     :enabled true
                     :remark (str/trim (or remark ""))
                     :userId (or user-id legacy-user)}
              candidate (assoc port-db k-kw entry)
              effects (port-effects port-str protocol method ip start-to-port)
              completed (run-effects! effects apply-port! remove-port!)]
          (persist-candidate! candidate completed remove-port!)
          (log/info (format "已添加规则: %s/%s" port-str protocol)))))))

(defn remove-forward! [port-str protocol method user-id & [is-admin]]
  (locking mutation-lock
    (let [k-str (build-port-key port-str protocol method)
          k-kw (keyword k-str)
          port-db (:port-db @state)
          entry (get port-db k-kw)]
      (when-not entry
        (throw (Exception. (:port-not-forwarded (:validation i18n/strings)))))
      (check-ownership port-db k-str user-id is-admin)
      (let [parsed (parse-port-entry k-str entry)
            start-to-port (Integer/parseInt (str (:toPort parsed)))
            effects (if (:enabled parsed)
                      (port-effects port-str protocol method (:ip parsed) start-to-port)
                      [])
            completed (run-effects! effects remove-port! apply-port!)
            candidate (dissoc port-db k-kw)]
        (persist-candidate! candidate completed apply-port!)
        (log/info (format "已删除规则: %s/%s" port-str protocol))))))

(defn toggle-forward! [port-str protocol method user-id & [is-admin]]
  (locking mutation-lock
    (let [k-str (build-port-key port-str protocol method)
          k-kw (keyword k-str)
          port-db (:port-db @state)
          entry (get port-db k-kw)]
      (when-not entry
        (throw (Exception. (:port-not-forwarded (:validation i18n/strings)))))
      (check-ownership port-db k-str user-id is-admin)
      (let [parsed (parse-port-entry k-str entry)
            start-to-port (Integer/parseInt (str (:toPort parsed)))
            effects (port-effects port-str protocol method (:ip parsed) start-to-port)
            disabling? (:enabled parsed)
            apply! (if disabling? remove-port! apply-port!)
            rollback! (if disabling? apply-port! remove-port!)
            completed (run-effects! effects apply! rollback!)
            new-enabled (not disabling?)
            candidate (assoc-in port-db [k-kw :enabled] new-enabled)]
        (persist-candidate! candidate completed rollback!)
        (log/info (format "规则 %s/%s %s" port-str protocol (if new-enabled "已启用" "已停用")))
        new-enabled))))

(defn update-remark! [port-str protocol method remark user-id & [is-admin]]
  (locking mutation-lock
    (let [k-str (build-port-key port-str protocol method)
          k-kw (keyword k-str)
          port-db (:port-db @state)]
      (when-not (get port-db k-kw)
        (throw (Exception. (:port-not-forwarded (:validation i18n/strings)))))
      (check-ownership port-db k-str user-id is-admin)
      (let [candidate (assoc-in port-db [k-kw :remark] (str/trim (or remark "")))]
        (db/write-json (:db-path config) candidate)
        (swap! state assoc :port-db candidate)))))

;; --- Queries ---

(defn socat-alive? [port-or-range protocol]
  (let [port-str (str port-or-range)]
    (if (.contains port-str "-")
      (let [[from to] (str/split port-str #"-")
            from-p (Integer/parseInt from)
            to-p (Integer/parseInt to)]
        (every? (fn [p]
                  (let [k-str (build-port-key p protocol "socat")
                        k-kw (keyword k-str)
                        entry (get-in @state [:kill-db k-kw])]
                    (and entry (process/alive? (:proc entry)))))
                (range from-p (inc to-p))))
      (let [k-str (build-port-key port-str protocol "socat")
            k-kw (keyword k-str)
            entry (get-in @state [:kill-db k-kw])]
        (and entry (process/alive? (:proc entry)))))))

(defn count-by-user [user-id]
  (count (filter (fn [[k _]]
                   (= (keyword user-id) (keyword (or (:userId (parse-port-entry (name k) (get-in @state [:port-db k]))) legacy-user))))
                 (:port-db @state))))

(defn get-entries [user-id is-admin]
  (let [all-entries (->> (:port-db @state)
                         (map (fn [[k val]]
                                (let [parsed (parse-port-entry (name k) val)
                                      alive (if (= (:method parsed) "socat")
                                              (socat-alive? (:port parsed) (:protocol parsed))
                                              nil)]
                                  (assoc parsed
                                         :alive alive
                                         :toPortDisplay (if (:isRange parsed)
                                                          (let [start-to (Integer/parseInt (str (:toPort parsed)))
                                                                end-to (+ start-to (- (:toRangePort parsed) (:fromPort parsed)))]
                                                            (str start-to "-" end-to))
                                                          (str (:toPort parsed))))))))]
    (if is-admin
      all-entries
      (filter (fn [entry]
                (and (not (str/blank? user-id))
                     (= (:userId entry) user-id)))
              all-entries))))

;; --- 生命周期钩子 ---

(defn- recovery-ports [entry]
  (let [{:keys [fromPort toRangePort]} (parse-port-range (:port entry))
        start-to (Integer/parseInt (str (:toPort entry)))]
    (mapv (fn [port]
            {:port port
             :protocol (:protocol entry)
             :method (:method entry)
             :ip (:ip entry)
             :target-port (+ start-to (- port fromPort))})
          (range fromPort (inc toRangePort)))))

(defn- apply-recovery-port! [{:keys [port protocol method ip target-port]}]
  (if (= method "iptables")
    (iptables-add! protocol port ip target-port)
    (start-socat! protocol port ip target-port)))

(defn- remove-recovery-port! [{:keys [port protocol method ip target-port]}]
  (if (= method "iptables")
    (iptables-remove! protocol port ip target-port)
    (stop-socat! port protocol)))

(defn- recovery-failure [phase {:keys [port protocol method]}]
  {:phase phase
   :port port
   :protocol protocol
   :method method})

(defn- rollback-recovery! [started]
  (reduce (fn [failures recovery-port]
            (try
              (remove-recovery-port! recovery-port)
              failures
              (catch Exception _
                (conj failures (recovery-failure :rollback recovery-port)))))
          []
          (reverse started)))

(defn sync-forwards! []
  (load-entries!)
  (let [enabled-entries (keep (fn [[k val]]
                                (let [entry (parse-port-entry (name k) val)]
                                  (when (:enabled entry)
                                    entry)))
                              (:port-db @state))
        recovery-items (vec (mapcat recovery-ports enabled-entries))]
    ;; 启动前清理旧规则保持尽力而为，后续恢复仍由失败关闭语义保证。
    (doseq [recovery-port recovery-items]
      (try
        (remove-recovery-port! recovery-port)
        (catch Exception _)))

    (loop [remaining (seq recovery-items)
           started []]
      (when-let [recovery-port (first remaining)]
        (let [applied? (try
                         (apply-recovery-port! recovery-port)
                         true
                         (catch Exception _
                           false))]
          (if applied?
            (recur (next remaining) (conj started recovery-port))
            (let [failures (into [(recovery-failure :apply recovery-port)]
                                 (rollback-recovery! started))]
              (throw (ex-info "端口转发启动恢复失败"
                              {:type ::startup-recovery-failed
                               :failures failures})))))))))

(defn shutdown! []
  (log/info "正在清理端口转发进程...")
  (doseq [[k val] (:port-db @state)]
    (let [entry (parse-port-entry (name k) val)]
      (when (:enabled entry)
        (try
          (let [{:keys [fromPort toRangePort]} (parse-port-range (:port entry))
                start-to (Integer/parseInt (str (:toPort entry)))]
            (doseq [p (range fromPort (inc toRangePort))]
              (let [target-port (+ start-to (- p fromPort))]
                (if (= (:method entry) "iptables")
                  (iptables-remove! (:protocol entry) p (:ip entry) target-port)
                  (stop-socat! p (:protocol entry))))))
          (catch Exception _))))))
