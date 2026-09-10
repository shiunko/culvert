(ns culvert.docker-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
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
                            :ttyd-procs {}
                            :log-ttyd-procs
                            {"a" {:pid 1 :port 17777 :proc :fake}}})
      (with-redefs [config/config (assoc config/config :docker-mutations-enabled true)
                    caddy/add-rule! (fn [& _] (swap! effects conj :caddy))
                    docker/save-entries! (fn [] (swap! effects conj :save))]
        (is (thrown? Exception (docker/expose-port! "a" "8080" "" bob)))
        (is (thrown? Exception (docker/unexpose-port! "a" "8080" bob)))
        (is (thrown? Exception (docker/toggle-port! "a" "8080" bob)))
        (is (thrown? Exception (docker/expose-port! "a" "8080" "")))
        (is (thrown? Exception (docker/unexpose-port! "a" "8080")))
        (is (thrown? Exception (docker/toggle-port! "a" "8080")))
        (is (thrown? Exception (docker/start-log-ttyd! "a" bob)))
        (is (thrown? Exception (docker/start-log-ttyd! "a" nil)))
        (is (thrown? Exception (docker/stop-log-ttyd! "a" bob)))
        (is (thrown? Exception (docker/stop-log-ttyd! "a"))))
      (is (empty? @effects))
      (is (= #{17777} (set (keep :port (vals (:log-ttyd-procs @docker/state))))))
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
                              :ttyd-procs {}
                              :log-ttyd-procs {}})
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
                            (fn [] (docker/toggle-port! "a" "8080" actor))
                            (fn [] (docker/start-log-ttyd! "a" actor))
                            (fn [] (docker/stop-log-ttyd! "a" actor))]]
            (invoke! mutation)))
        (is (empty? @effects))
        (is (= {:containers {"a" container}
                :used-ports #{}
                :ttyd-procs {}
                :log-ttyd-procs {}}
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
                    :used-ports (:used-ports original)
                    :ttyd-procs (:ttyd-procs original)
                    :log-ttyd-procs (:log-ttyd-procs original)}
                   @docker/state))))
        (finally
          (reset! docker/state original))))))
