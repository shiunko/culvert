(ns culvert.docker-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [culvert.authorization :as authorization]
            [culvert.caddy :as caddy]
            [culvert.cli :as cli]
            [culvert.config :as config]
            [culvert.docker :as docker]
            [culvert.forward :as forward]
            [culvert.runtime.process :as process]
            [culvert.s3 :as s3]))

;; ============================================================
;; 内存辅助函数测试
;; ============================================================

(deftest test-add-memory
  (testing "零内存相加"
    (is (= (docker/add-memory "0" "0") "0.0m")))

  (testing "兆字节相加"
    (is (= (docker/add-memory "256m" "256m") "512m"))
    (is (= (docker/add-memory "512m" "512m") "1g")))

  (testing "吉字节相加"
    (is (= (docker/add-memory "1g" "1g") "2g"))
    (is (= (docker/add-memory "2g" "0") "2g")))

  (testing "混合单位相加"
    (is (= (docker/add-memory "512m" "512m") "1g"))
    (is (= (docker/add-memory "1g" "512m") "1536m")))

  (testing "无单位数字默认按字节处理"
    (is (some? (docker/add-memory "0" "0")))))

;; ============================================================
;; 容器名称清理测试
;; ============================================================

(deftest test-sanitize-container-name
  (testing "生成基本名称"
    (let [name (docker/sanitize-container-name "alice" "ubuntu:22.04" "abcd1234")]
      (is (str/starts-with? name "alice-"))
      (is (str/ends-with? name "abcd1234"))
      ;; 镜像名中的冒号应替换为连字符
      (is (not (str/includes? name ":"))))))

;; ============================================================
;; 容器计数测试
;; ============================================================

(deftest test-count-by-user
  (testing "空状态的计数返回整数"
    (is (= (docker/count-by-user "nonexistent-user") 0))))

;; ============================================================
;; 端口分配测试
;; ============================================================

(deftest test-port-allocation-state
  (testing "used-ports 是集合"
    (is (set? (:used-ports @docker/state)))))

;; ============================================================
;; 日志目标解析与读取
;; ============================================================

(deftest log-target-resolution-and-authorization
  (let [original @docker/state
        alice {:username "alice" :admin? false}
        admin {:username "root" :admin? true}
        container {:id "a" :name "alice-box" :userId "alice" :ports {}
                   :status "running"}]
    (try
      (reset! docker/state {:containers {"a" container}
                            :used-ports #{} :ttyd-procs {}})
      (testing "受管容器：所有者与管理员可读，其他用户被拒"
        (is (= :managed (:source (docker/resolve-log-target "a" alice))))
        (is (= :managed (:source (docker/resolve-log-target "alice-box" alice))))
        (is (= :managed (:source (docker/resolve-log-target "a" admin))))
        (is (= ::authorization/forbidden
               (:type (ex-data (try (docker/resolve-log-target "a" {:username "bob" :admin? false})
                                    nil
                                    (catch Exception e e)))))))
      (testing "未受管宿主机容器：只有管理员可读，普通用户被拒且不触发 CLI 查询"
        (let [inspected (atom 0)]
          (with-redefs [cli/inspect-container-details (fn [_]
                                                        (swap! inspected inc)
                                                        {:id "host-id" :name "host-box"
                                                         :image "nginx" :state "exited"})]
            (is (thrown? Exception (docker/resolve-log-target "host-box" alice)))
            (is (zero? @inspected))
            (is (= {:source :host :id "host-id" :name "host-box" :state "exited"}
                   (docker/resolve-log-target "host-box" admin)))
            (is (= 1 @inspected)))))
      (testing "宿主容器不存在时转为稳定的容器不存在错误"
        (with-redefs [cli/inspect-container-details (fn [_] (throw (Exception. "No such object")))]
          (let [error (try (docker/resolve-log-target "ghost" admin) nil (catch Exception e e))]
            (is (= ::docker/container-not-found (:type (ex-data error)))))))
      (finally
        (reset! docker/state original)))))

(deftest read-logs-uses-merged-cli-output
  (let [original @docker/state
        admin {:username "root" :admin? true}
        argv (atom nil)]
    (try
      (reset! docker/state {:containers {} :used-ports #{} :ttyd-procs {}})
      (with-redefs [cli/inspect-container-details
                    (fn [_] {:id "host-id" :name "host-box" :image "nginx" :state "running"})
                    process/run
                    (fn [a] (reset! argv a)
                      {:exit 0 :out "stdout-line\n" :err "stderr-line\n"})]
        (let [result (docker/read-logs "host-id" admin {:tail 300})]
          (is (= "host-box" (:name result)))
          (is (str/includes? (:text result) "stdout-line"))
          (is (str/includes? (:text result) "stderr-line"))
          (is (= "300" (second (drop-while #(not= "--tail" %) @argv))))))
      (finally
        (reset! docker/state original)))))

;; ============================================================
;; 管理员容器终端（持久 webshell）
;; ============================================================

(def ^:private webshell-config
  {:docker-mutations-enabled true
   :ttyd-enabled true
   :ip-mode true
   :ttyd-session-public true
   :listen-address "0.0.0.0"
   :public-ip "10.0.0.9"
   :container-cli-path "docker"
   :ttyd-path "ttyd"})

(defn- with-webshell-env
  "重置 docker state 并绑定测试用配置与容器环境。
   opts 可覆盖 :spawn 与 :shell-available?。"
  [opts f]
  (let [original @docker/state
        spawn (or (:spawn opts) (fn [_ _] (atom {:pid 4242 :proc :fake-proc})))]
    (try
      (reset! docker/state {:containers {} :webshells {} :webshell-procs {}
                            :used-ports #{} :ttyd-procs {}})
      (with-redefs [config/config (merge config/config webshell-config)
                    cli/inspect-container-details
                    (fn [_] {:id "host-id-1" :name "host-box" :image "nginx" :state "running"})
                    cli/shell-available-in-container? (fn [_ _] ((or (:shell-available? opts)
                                                                     (constantly true))))
                    process/spawn spawn
                    process/pid (fn [_] 4242)
                    process/alive? (fn [_] true)
                    ;; 守护线程会等 ttyd 退出；测试里用永不归零的闩锁保持会话存活。
                    process/wait (fn [_] (.await (java.util.concurrent.CountDownLatch. 1)))
                    docker/save-entries! (fn [] nil)]
        (f))
      (finally
        (reset! docker/state original)))))

(deftest webshell-open-requires-admin
  (testing "非管理员与未认证 actor 在 spawn 之前被拒绝"
    (let [spawned (atom 0)]
      (with-webshell-env
        {:spawn (fn [_ _] (swap! spawned inc) (atom {:pid 1 :proc :p}))}
        (fn []
          (let [error (try (docker/open-webshell! "host-box" {:username "alice" :admin? false})
                           nil (catch Exception e e))]
            (is (= ::authorization/admin-required (:type (ex-data error)))))
          (is (thrown? Exception (docker/open-webshell! "host-box" nil)))
          (is (zero? @spawned))
          (is (empty? (:webshells @docker/state))))))))

(deftest webshell-open-requires-running-container
  (testing "容器未运行时拒绝开启，且不分配端口、不落库"
    (let [spawned (atom 0)]
      (with-webshell-env
        {:spawn (fn [_ _] (swap! spawned inc) (atom {:pid 1 :proc :p}))}
        (fn []
          (with-redefs [cli/inspect-container-details
                        (fn [_] {:id "host-id-1" :name "host-box" :image "nginx" :state "exited"})]
            (let [error (try (docker/open-webshell! "host-box" {:username "root" :admin? true})
                             nil (catch Exception e e))]
              (is (= ::docker/terminal-not-running (:type (ex-data error))))
              (is (zero? @spawned))
              (is (empty? (:webshells @docker/state)))
              (is (empty? (:used-ports @docker/state))))))))))

(deftest webshell-open-requires-shell
  (testing "容器内没有可用 shell 时给出稳定错误，并回收已分配端口"
    (with-webshell-env
      {:shell-available? (constantly false)}
      (fn []
        (let [error (try (docker/open-webshell! "host-box" {:username "root" :admin? true})
                         nil (catch Exception e e))]
          (is (= ::docker/terminal-shell-unavailable (:type (ex-data error))))
          (is (empty? (:webshells @docker/state)))
          (is (empty? (:used-ports @docker/state))))))))

(deftest webshell-open-spawns-ttyd-and-keeps-records-json-safe
  (testing "开启终端：ttyd 以 argv 向量执行 docker exec，记录不携带进程句柄"
    (let [argv (atom nil)
          saved (atom 0)]
      (with-webshell-env
        {:spawn (fn [a _] (reset! argv a) (atom {:pid 4242 :proc :fake-proc}))}
        (fn []
          (with-redefs [docker/save-entries! (fn [] (swap! saved inc))]
            (let [result (docker/open-webshell! "host-box" {:username "root" :admin? true})]
              (is (= "host-box" (:name result)))
              (is (= "running" (:status result)))
              (is (= "http://10.0.0.9:17680" (:url result)))
              (is (= 1 @saved))
              (is (vector? @argv))
              (is (= ["exec" "-it" "host-box" "/bin/bash"]
                     (subvec @argv (- (count @argv) 4))))
              (is (some #{"-W"} @argv))
              (is (= "0.0.0.0" (second (drop-while #(not= "-i" %) @argv))))
              (let [record (get-in @docker/state [:webshells "host-box"])]
                (is (= "running" (:status record)))
                (is (= "root" (:createdBy record)))
                (is (nil? (:proc record)))
               ;; 记录会写入 JSON，必须只包含数据
                (is (string? (json/generate-string (:webshells @docker/state))))))))))))

(deftest webshell-open-reuses-live-session
  (testing "同一容器已有存活会话时幂等返回，不重复 spawn"
    (let [spawned (atom 0)]
      (with-webshell-env
        {:spawn (fn [_ _] (swap! spawned inc) (atom {:pid 700 :proc :live}))}
        (fn []
          (let [admin {:username "root" :admin? true}
                _ (docker/open-webshell! "host-box" admin)
                again (docker/open-webshell! "host-box" admin)]
            (is (= 1 @spawned))
            (is (= "running" (:status again)))))))))

(deftest webshell-open-disabled-without-exposure-flag
  (testing "IP 模式未开 ttydSessionPublic 时拒绝开启，不产生副作用"
    (let [spawned (atom 0)]
      (with-webshell-env
        {:spawn (fn [_ _] (swap! spawned inc) (atom {:pid 1 :proc :p}))}
        (fn []
          (with-redefs [config/config (assoc config/config :ttyd-session-public false)]
            (let [error (try (docker/open-webshell! "host-box" {:username "root" :admin? true})
                             nil (catch Exception e e))]
              (is (= ::docker/terminal-exposure-disabled (:type (ex-data error))))
              (is (zero? @spawned))
              (is (empty? (:used-ports @docker/state))))))))))

(deftest webshell-close-stops-process-and-drops-record
  (testing "显式关闭：销毁进程、释放端口并丢弃记录"
    (let [destroyed (atom [])]
      (with-webshell-env
        nil
        (fn []
          (docker/open-webshell! "host-box" {:username "root" :admin? true})
          (with-redefs [process/destroy! (fn [proc] (swap! destroyed conj proc))]
            (is (= {:status "ok"} (docker/close-webshell! "host-box" {:username "root" :admin? true})))
            (is (= 1 (count @destroyed)))
            (is (empty? (:webshells @docker/state)))
            (is (empty? (:used-ports @docker/state)))))))))

(deftest webshell-credentials-require-admin-and-existing-session
  (testing "凭据按需读取，未开启或非管理员均被拒绝"
    (with-webshell-env
      nil
      (fn []
        (let [admin {:username "root" :admin? true}]
          (is (thrown? Exception (docker/reveal-webshell-credentials "host-box" admin)))
          (docker/open-webshell! "host-box" admin)
          (let [creds (docker/reveal-webshell-credentials "host-box" admin)]
            (is (string? (:user creds)))
            (is (string? (:pass creds))))
          (let [error (try (docker/reveal-webshell-credentials "host-box" {:username "alice" :admin? false})
                           nil (catch Exception e e))]
            (is (= ::authorization/admin-required (:type (ex-data error))))))))))

(deftest webshell-restore-follows-container-existence
  (testing "启动恢复：容器已删除则丢记录，未运行则标 stopped，运行中则重建 ttyd"
    (let [spawned (atom 0)
          record {:name "gone-box" :containerId "id-1" :image "nginx" :shell "/bin/sh"
                  :user "dev_a" :pass "p" :port 17690 :ttydPid nil :caddyRuleId nil
                  :status "stopped" :createdBy "root" :createdAt "2026-01-01T00:00:00Z"}]
      (with-webshell-env
        {:spawn (fn [_ _] (swap! spawned inc) (atom {:pid 9 :proc :p9}))}
        (fn []
          (swap! docker/state assoc :webshells {"gone-box" record
                                                "stopped-box" (assoc record :name "stopped-box"
                                                                     :port 17691)
                                                "live-box" (assoc record :name "live-box"
                                                                  :port 17692)})
          (swap! docker/state update :used-ports into [17690 17691 17692])
          (with-redefs [cli/available? (constantly true)
                        cli/container-exists? (fn [name] (not= name "gone-box"))
                        cli/inspect-container-details
                        (fn [name] {:id (str "id-" name)
                                    :name name
                                    :image "nginx"
                                    :state (if (= name "live-box") "running" "exited")})]
            (docker/restore-webshells!)
            (is (= 1 @spawned))
            (is (not (contains? (:webshells @docker/state) "gone-box")))
            (is (= "stopped" (get-in @docker/state [:webshells "stopped-box" :status])))
            (is (= "running" (get-in @docker/state [:webshells "live-box" :status])))
           ;; 已删除与已停止的记录都不再占用端口，只有重建的会话占一个
            (is (= #{(get-in @docker/state [:webshells "live-box" :port])}
                   (:used-ports @docker/state)))))))))

(deftest owner-shell-url-requires-live-owner-ttyd
  (testing "管理员开终端时自动登记的容器：所有者 ttyd 未运行，不暴露 :shellPort 链接"
    (let [argvs (atom [])]
      (with-webshell-env
        {:spawn (fn [a _] (swap! argvs conj a) (atom {:pid 4242 :proc :fake-proc}))}
        (fn []
          (let [admin {:username "root" :admin? true}]
            (docker/auto-register-for-terminal! "host-box" admin)
            (let [webshell (docker/open-webshell! "host-box" admin)
                  owner (first (docker/get-containers "root" true))]
              ;; 所有者 shell 先占用端口池首个端口，管理员终端拿到下一个
              (is (= 17680 (:shellPort owner)))
              (is (= "http://10.0.0.9:17681" (:url webshell)))
              (is (nil? (:shellUrl owner))))

            (testing "所有者 ttyd 启动后链接指向 :shellPort，且与管理员终端同样绑定 listen-address"
              (let [record (val (first (:containers @docker/state)))]
                (docker/start-ttyd! record)
                (is (= "0.0.0.0" (second (drop-while #(not= "-i" %) (last @argvs)))))
                (is (= "http://10.0.0.9:17680"
                       (:shellUrl (first (docker/get-containers "root" true)))))))

            (testing "IP 模式关闭 ttydSessionPublic：所有者 ttyd 只绑 loopback，不给出外部链接"
              (with-redefs [config/config (assoc config/config :ttyd-session-public false)]
                (docker/start-ttyd! (val (first (:containers @docker/state))))
                (is (= "127.0.0.1" (second (drop-while #(not= "-i" %) (last @argvs)))))
                (is (nil? (:shellUrl (first (docker/get-containers "root" true)))))))))))))

;; ============================================================
;; 容器状态结构测试
;; ============================================================

(deftest test-state-structure
  (testing "状态 atom 包含预期键"
    (is (contains? @docker/state :containers))
    (is (contains? @docker/state :used-ports))
    (is (contains? @docker/state :ttyd-procs))
    (is (map? (:containers @docker/state)))
    (is (map? (:ttyd-procs @docker/state)))))

(deftest container-queries-isolate-users-and-legacy-resources
  (let [original @docker/state
        alice {:username "alice" :admin? false}
        bob {:username "bob" :admin? false}
        admin {:username "root" :admin? true}]
    (try
      (swap! docker/state assoc :containers
             {"a" {:id "a" :userId "alice" :createdAt "3" :ports {}}
              "b" {:id "b" :userId "bob" :createdAt "2" :ports {}}
              "l" {:id "l" :userId docker/legacy-user :createdAt "1" :ports {}}})
      (is (= #{"a"} (set (map :id (docker/get-containers alice)))))
      (is (= #{"b"} (set (map :id (docker/get-containers bob)))))
      (is (= #{"a" "b" "l"} (set (map :id (docker/get-containers admin)))))
      (is (nil? (docker/get-container "l" alice)))
      (is (thrown? Exception (docker/get-containers nil)))
      (finally
        (reset! docker/state original)))))

(deftest denied-container-mutations-have-no-side-effects
  (let [original @docker/state
        effects (atom [])
        bob {:username "bob" :admin? false}
        container {:id "a" :name "alice-box" :userId "alice" :ports {}
                   :status "running"}]
    (try
      (reset! docker/state {:containers {"a" container}
                            :used-ports #{}
                            :ttyd-procs {}})
      (with-redefs [config/config (assoc config/config :docker-mutations-enabled true)
                    caddy/add-rule! (fn [& _] (swap! effects conj :caddy))
                    docker/save-entries! (fn [] (swap! effects conj :save))]
        (is (thrown? Exception (docker/expose-port! "a" "8080" "" bob)))
        (is (thrown? Exception (docker/unexpose-port! "a" "8080" bob)))
        (is (thrown? Exception (docker/toggle-port! "a" "8080" bob)))
        (is (thrown? Exception (docker/expose-port! "a" "8080" "")))
        (is (thrown? Exception (docker/unexpose-port! "a" "8080")))
        (is (thrown? Exception (docker/toggle-port! "a" "8080")))
        (is (thrown? Exception (docker/read-logs "a" bob)))
        (is (thrown? Exception (docker/read-logs "a" nil))))
      (is (empty? @effects))
      (is (= container (get-in @docker/state [:containers "a"])))
      (finally
        (reset! docker/state original)))))

(deftest disabled-container-mutations-have-no-side-effects
  (testing "默认禁用时所有用户 mutation 入口立即失败且不产生副作用"
    (let [original @docker/state
          effects (atom [])
          actor {:username "alice" :admin? false}
          container {:id "a" :name "alice-box" :userId "alice" :ports {}
                     :status "stopped"}
          invoke! (fn [f]
                    (let [error (try (f) nil (catch Exception e e))]
                      (is (= ::docker/mutations-disabled (:type (ex-data error))))))]
      (try
        (reset! docker/state {:containers {"a" container}
                              :used-ports #{}
                              :ttyd-procs {}})
        (with-redefs [config/config (dissoc config/config :docker-mutations-enabled)
                      cli/pull-image (fn [& _] (swap! effects conj :pull))
                      cli/run-container (fn [& _] (swap! effects conj :run))
                      cli/kill-container (fn [& _] (swap! effects conj :kill))
                      cli/start-container (fn [& _] (swap! effects conj :start))
                      cli/get-container-info (fn [& _] (swap! effects conj :inspect))
                      process/spawn (fn [& _] (swap! effects conj :spawn))
                      caddy/add-managed-rule! (fn [& _] (swap! effects conj :caddy))
                      docker/save-entries! (fn [] (swap! effects conj :save))]
          (doseq [mutation [(fn [] (docker/create-container! "alice" "image" "1" "1g" nil false))
                            (fn [] (docker/kill-container! "a" actor))
                            (fn [] (docker/assign-container! "host-box" "alice"))
                            (fn [] (docker/start-container! "a" actor))
                            (fn [] (docker/expose-port! "a" "8080" "" actor))
                            (fn [] (docker/unexpose-port! "a" "8080" actor))
                            (fn [] (docker/toggle-port! "a" "8080" actor))]]
            (invoke! mutation)))
        (is (empty? @effects))
        (is (= {:containers {"a" container}
                :used-ports #{}
                :ttyd-procs {}}
               @docker/state))
        (finally
          (reset! docker/state original))))))

(deftest disabled-container-sync-is-safe-or-fails-with-migration-guidance
  (testing "禁用时空库不探测 CLI，非空库要求迁移或清空且无副作用"
    (let [original @docker/state
          loaded (atom {})
          effects (atom [])]
      (try
        (with-redefs [config/config (dissoc config/config :docker-mutations-enabled)
                      docker/load-entries! #(swap! docker/state assoc :containers @loaded)
                      cli/available? (fn [] (swap! effects conj :cli) true)
                      s3/sync-mounts! (fn [& _] (swap! effects conj :s3))
                      docker/save-entries! (fn [] (swap! effects conj :save))]
          (is (nil? (docker/sync-containers!)))
          (is (empty? @effects))
          (reset! loaded {"c1" {:id "c1" :name "box" :ports {}}})
          (let [error (try (docker/sync-containers!) nil (catch Exception e e))]
            (is (= ::docker/startup-recovery-failed (:type (ex-data error))))
            (is (= :feature-disabled (-> error ex-data :failures first :stage)))
            (is (re-find #"迁移或清空" (-> error ex-data :failures first :message)))
            (is (empty? @effects))))
        (finally
          (reset! docker/state original))))))

(deftest strict-container-running-query-propagates-errors
  (testing "严格查询传播 inspect 异常，兼容查询仍返回 false"
    (with-redefs [cli/inspect-container (fn [& _]
                                          (throw (Exception. "inspect 失败")))]
      (is (thrown-with-msg? Exception #"inspect 失败"
                            (cli/container-running-strict? "box")))
      (is (false? (cli/container-running? "box"))))))

(deftest startup-recovery-requires-cli-for-nonempty-state
  (testing "空库可跳过缺失的 CLI，非空库以结构化失败终止"
    (let [original @docker/state
          loaded (atom {})]
      (try
        (with-redefs [config/config (assoc config/config :docker-mutations-enabled true)
                      docker/load-entries! #(swap! docker/state assoc :containers @loaded)
                      cli/available? (constantly false)]
          (is (nil? (docker/sync-containers!)))
          (reset! loaded {"c1" {:id "c1" :name "box" :ports {}}})
          (let [error (try
                        (docker/sync-containers!)
                        nil
                        (catch Exception e e))]
            (is (= ::docker/startup-recovery-failed (:type (ex-data error))))
            (is (seq (:failures (ex-data error))))))
        (finally
          (reset! docker/state original))))))

(deftest ttyd-disabled-container-remains-running
  (testing "禁用 ttyd 时不启动 ttyd、不创建 Shell Caddy 规则，并清除旧 PID"
    (let [original @docker/state
          effects (atom [])
          container {:id "c1" :name "box" :userId "alice"
                     :ttydPid 42 :ports {}}]
      (try
        (with-redefs [config/config (assoc config/config
                                           :docker-mutations-enabled true
                                           :ttyd-enabled false)
                      docker/load-entries! #(swap! docker/state assoc :containers {"c1" container})
                      docker/save-entries! #(swap! effects conj :save)
                      cli/available? (constantly true)
                      cli/container-running-strict? (constantly true)
                      docker/start-ttyd! (fn [_] (swap! effects conj :ttyd))
                      caddy/add-managed-rule! (fn [& _] (swap! effects conj :caddy))
                      s3/sync-mounts! (fn [_] nil)]
          (docker/sync-containers!)
          (is (= [:save] @effects))
          (is (= "running" (get-in @docker/state [:containers "c1" :status])))
          (is (nil? (get-in @docker/state [:containers "c1" :ttydPid])))
          (is (= "running" (docker/refresh-status! "c1"))))
        (finally
          (reset! docker/state original))))))

(deftest ttyd-enabled-recovery-failure-is-fatal
  (testing "启用 ttyd 的运行容器恢复失败时启动失败且不保存"
    (let [original @docker/state
          effects (atom [])
          container {:id "c1" :name "box" :userId "alice"
                     :ttydPid nil :ports {}}]
      (try
        (with-redefs [config/config (assoc config/config
                                           :docker-mutations-enabled true
                                           :ttyd-enabled true)
                      docker/load-entries! #(swap! docker/state assoc :containers {"c1" container})
                      docker/save-entries! #(swap! effects conj :save)
                      cli/available? (constantly true)
                      cli/container-running-strict? (constantly true)
                      docker/ttyd-alive? (constantly false)
                      docker/start-ttyd! (fn [_] (throw (Exception. "ttyd 失败")))
                      s3/sync-mounts! (fn [_] nil)]
          (let [error (try
                        (docker/sync-containers!)
                        nil
                        (catch Exception e e))]
            (is (= ::docker/startup-recovery-failed (:type (ex-data error))))
            (is (= [:container] (mapv :stage (:failures (ex-data error)))))
            (is (empty? @effects))))
        (finally
          (reset! docker/state original))))))

(deftest startup-recovery-rolls-back-new-processes
  (testing "后续端口恢复失败时逆序清理本次 socat 与 ttyd，且不保存"
    (let [original @docker/state
          effects (atom [])
          container {:id "c1" :name "box" :userId "alice"
                     :ttydPid nil
                     :ports (array-map "8080" {:enabled true :hostPort 18080}
                                       "9090" {:enabled true :hostPort 19090})}]
      (try
        (with-redefs [config/config (assoc config/config
                                           :docker-mutations-enabled true
                                           :ip-mode true
                                           :ttyd-enabled true)
                      docker/load-entries! #(swap! docker/state assoc :containers {"c1" container})
                      docker/save-entries! #(swap! effects conj :save)
                      cli/available? (constantly true)
                      cli/container-running-strict? (constantly true)
                      cli/can-access-container-ip? (constantly false)
                      docker/ttyd-alive? (constantly false)
                      docker/start-ttyd! (fn [_]
                                           (swap! docker/state assoc-in [:ttyd-procs "c1"]
                                                  {:pid 7 :proc :ttyd-proc})
                                           (swap! effects conj :start-ttyd)
                                           7)
                      forward/start-socat! (fn [_ host-port _ _]
                                             (swap! effects conj [:start-socat host-port])
                                             (when (= host-port 19090)
                                               (throw (Exception. "socat 失败"))))
                      forward/stop-socat! (fn [host-port _]
                                            (swap! effects conj [:stop-socat host-port]))
                      process/destroy! (fn [proc]
                                         (swap! effects conj [:destroy proc])
                                         (throw (Exception. "销毁 ttyd 失败")))
                      s3/sync-mounts! (fn [_] nil)]
          (let [error (try
                        (docker/sync-containers!)
                        nil
                        (catch Exception e e))]
            (is (= ::docker/startup-recovery-failed (:type (ex-data error))))
            (is (= [:container :rollback]
                   (mapv :stage (:failures (ex-data error)))))
            (is (= [:start-ttyd
                    [:start-socat 18080]
                    [:start-socat 19090]
                    [:stop-socat 18080]
                    [:destroy :ttyd-proc]]
                   @effects))
            (is (= {:containers {"c1" container}
                    :webshells {}
                    :webshell-procs {}
                    :used-ports (:used-ports original)
                    :ttyd-procs (:ttyd-procs original)}
                   @docker/state))))
        (finally
          (reset! docker/state original))))))
