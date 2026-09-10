(ns culvert.smolvm-test
  "SmolVM 管理器纯单元测试，无需安装 SmolVM 二进制文件。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [culvert.caddy :as caddy]
            [culvert.config :as app-config]
            [culvert.runtime.process :as process]
            [culvert.smolvm.cli :as smolvm-cli]
            [culvert.smolvm.manager :as smolvm]))

;; ============================================================
;; 名称清理
;; ============================================================

(deftest test-sanitize-machine-name
  (testing "生成基本名称"
    (let [name (smolvm/sanitize-machine-name "admin" "alpine:3.21" "abc123")]
      (is (some? name))
      (is (str/starts-with? name "vm-admin-alpine-3-21-"))
      (is (str/ends-with? name "abc123"))))

  (testing "清理特殊字符"
    (let [name (smolvm/sanitize-machine-name "user@test.com" "ubuntu:22.04" "xyz")]
      (is (not (str/includes? name "@")))
      (is (not (str/includes? name ".")))))

  (testing "截断过长名称"
    (let [long-user (apply str (repeat 50 "a"))
          name (smolvm/sanitize-machine-name long-user "alpine" "id")]
      ;; 用户名部分应截断为 32 个字符
      (is (< (count name) 100)))))

;; ============================================================
;; 端口分配（直接使用测试 atom 验证状态）
;; ============================================================

(deftest test-allocate-release-port
  (testing "端口分配与释放周期"
    ;; 保存原状态
    (let [original @smolvm/state]
      (try
        ;; 重置状态以隔离测试
        (swap! smolvm/state assoc :used-ports #{})
        (let [port1 (smolvm/allocate-port)
              port2 (smolvm/allocate-port)]
          (is (not= port1 port2))
          (is (contains? (:used-ports @smolvm/state) port1))
          (is (contains? (:used-ports @smolvm/state) port2))

          ;; 释放 port1
          (smolvm/release-port port1)
          (is (not (contains? (:used-ports @smolvm/state) port1)))
          (is (contains? (:used-ports @smolvm/state) port2)))
        (finally
          ;; 恢复原状态
          (reset! smolvm/state original))))))

;; ============================================================
;; 内存辅助函数
;; ============================================================

(deftest test-parse-mem-bytes
  (testing "正确解析内存字符串"
    ;; 直接验证公开的解析函数
    (let [parse-mem smolvm/parse-mem-bytes]
      (is (= 0.0 (parse-mem "0")))
      (is (> (parse-mem "512m") 0))
      (is (> (parse-mem "1g") (parse-mem "512m")))
      (is (= (* 1024.0 1024.0 1024.0) (parse-mem "1g"))))))

;; ============================================================
;; 按用户计数
;; ============================================================

(deftest test-count-by-user
  (testing "统计指定用户的 VM 数量"
    (let [original @smolvm/state]
      (try
        (swap! smolvm/state assoc :machines
               {"id1" {:userId "alice" :name "vm1"}
                "id2" {:userId "bob" :name "vm2"}
                "id3" {:userId "alice" :name "vm3"}})
        (is (= 2 (smolvm/count-by-user "alice")))
        (is (= 1 (smolvm/count-by-user "bob")))
        (is (= 0 (smolvm/count-by-user "charlie")))
        (finally
          (reset! smolvm/state original))))))

;; ============================================================
;; 公共 VM 输出脱敏
;; ============================================================

(deftest test-to-public-machine
  (testing "公共输出中的密码已脱敏"
    (let [original @smolvm/state]
      (try
        (let [machine {:id "test-id"
                       :name "vm-test"
                       :image "alpine:3.21"
                       :userId "admin"
                       :netEnabled true
                       :shellToken "abc123"
                       :shellUser "dev_test"
                       :shellPass "secret123"
                       :shellPort 30001
                       :shell "/bin/sh"
                       :status "running"
                       :cpuLimit "4"
                       :memLimit "1024"
                       :gpu false
                       :sshAgent false
                       :ports {}}
              _ (swap! smolvm/state assoc-in [:machines "test-id"] machine)
              public (smolvm/to-public-machine machine)]
          (is (= "●●●●●●●●●●●●" (:shellPassMasked public)))
          (is (nil? (:shellPass public)))
          (is (= "dev_test" (:shellUser public)))
          (is (= "alpine:3.21" (:image public))))
        (finally
          (reset! smolvm/state original))))))

(deftest machine-queries-isolate-users-and-legacy-resources
  (let [original @smolvm/state
        alice {:username "alice" :admin? false}
        bob {:username "bob" :admin? false}
        admin {:username "root" :admin? true}]
    (try
      (swap! smolvm/state assoc :machines
             {"a" {:id "a" :userId "alice" :createdAt "3" :ports {}}
              "b" {:id "b" :userId "bob" :createdAt "2" :ports {}}
              "l" {:id "l" :userId smolvm/legacy-user :createdAt "1" :ports {}}})
      (is (= #{"a"} (set (map :id (smolvm/get-machines alice)))))
      (is (= #{"b"} (set (map :id (smolvm/get-machines bob)))))
      (is (= #{"a" "b" "l"} (set (map :id (smolvm/get-machines admin)))))
      (is (nil? (smolvm/get-machine "l" alice)))
      (is (thrown? Exception (smolvm/get-machines nil)))
      (finally
        (reset! smolvm/state original)))))

(deftest denied-machine-mutations-have-no-side-effects
  (let [original @smolvm/state
        effects (atom [])
        bob {:username "bob" :admin? false}
        machine {:id "a" :name "alice-vm" :userId "alice" :ports {}
                 :status "running"}]
    (try
      (reset! smolvm/state {:machines {"a" machine}
                            :used-ports #{}
                            :ttyd-procs {}})
      (with-redefs [app-config/config (assoc app-config/config :smolvm-mutations-enabled true)
                    smolvm/allocate-port (fn [] (swap! effects conj :allocate) 17777)
                    smolvm-cli/stop-machine! (fn [& _] (swap! effects conj :stop))
                    smolvm-cli/update-machine! (fn [& _] (swap! effects conj :update))
                    smolvm-cli/start-machine! (fn [& _] (swap! effects conj :start))
                    smolvm/save-entries! (fn [] (swap! effects conj :save))]
        (is (thrown? Exception (smolvm/expose-port! "a" "8080" "" bob)))
        (is (thrown? Exception (smolvm/unexpose-port! "a" "8080" bob)))
        (is (thrown? Exception (smolvm/toggle-port! "a" "8080" bob)))
        (is (thrown? Exception (smolvm/expose-port! "a" "8080" "")))
        (is (thrown? Exception (smolvm/unexpose-port! "a" "8080")))
        (is (thrown? Exception (smolvm/toggle-port! "a" "8080"))))
      (is (empty? @effects))
      (is (= machine (get-in @smolvm/state [:machines "a"])))
      (finally
        (reset! smolvm/state original)))))

(deftest disabled-machine-mutations-have-no-side-effects
  (testing "默认禁用时所有用户 mutation 入口立即失败且不产生副作用"
    (let [original @smolvm/state
          effects (atom [])
          actor {:username "alice" :admin? false}
          machine {:id "a" :name "alice-vm" :userId "alice" :ports {}
                   :status "stopped"}
          invoke! (fn [f]
                    (let [error (try (f) nil (catch Exception e e))]
                      (is (= ::smolvm/mutations-disabled (:type (ex-data error))))))]
      (try
        (reset! smolvm/state {:machines {"a" machine}
                              :used-ports #{}
                              :ttyd-procs {}})
        (with-redefs [app-config/config (dissoc app-config/config :smolvm-mutations-enabled)
                      smolvm/allocate-port (fn [] (swap! effects conj :allocate) 17777)
                      smolvm-cli/create-machine! (fn [& _] (swap! effects conj :create))
                      smolvm-cli/delete-machine! (fn [& _] (swap! effects conj :delete))
                      smolvm-cli/start-machine! (fn [& _] (swap! effects conj :start))
                      smolvm-cli/stop-machine! (fn [& _] (swap! effects conj :stop))
                      smolvm-cli/update-machine! (fn [& _] (swap! effects conj :update))
                      smolvm-cli/pack-machine! (fn [& _] (swap! effects conj :pack))
                      caddy/add-managed-rule! (fn [& _] (swap! effects conj :caddy))
                      smolvm/save-entries! (fn [] (swap! effects conj :save))]
          (doseq [mutation [(fn [] (smolvm/create-machine! "alice" "image" "1" "512" {}))
                            (fn [] (smolvm/kill-machine! "a" actor))
                            (fn [] (smolvm/start-machine! "a" actor))
                            (fn [] (smolvm/stop-machine! "a" actor))
                            (fn [] (smolvm/expose-port! "a" "8080" "" actor))
                            (fn [] (smolvm/unexpose-port! "a" "8080" actor))
                            (fn [] (smolvm/toggle-port! "a" "8080" actor))
                            (fn [] (smolvm/pack-machine! "a" "/tmp" actor))]]
            (invoke! mutation)))
        (is (empty? @effects))
        (is (= {:machines {"a" machine}
                :used-ports #{}
                :ttyd-procs {}}
               @smolvm/state))
        (finally
          (reset! smolvm/state original))))))

(deftest disabled-machine-sync-is-safe-or-fails-with-migration-guidance
  (testing "禁用时空库不探测 CLI，非空库要求迁移或清空且无副作用"
    (let [original @smolvm/state
          loaded (atom {})
          effects (atom [])]
      (try
        (with-redefs [app-config/config (dissoc app-config/config :smolvm-mutations-enabled)
                      smolvm/load-entries! #(swap! smolvm/state assoc :machines @loaded)
                      smolvm-cli/available? (fn [] (swap! effects conj :cli) true)
                      smolvm-cli/machine-running-strict? (fn [& _] (swap! effects conj :status) true)
                      smolvm/save-entries! (fn [] (swap! effects conj :save))]
          (is (nil? (smolvm/sync-machines!)))
          (is (empty? @effects))
          (reset! loaded {"a" {:id "a" :name "vm-a" :ports {}}})
          (let [error (try (smolvm/sync-machines!) nil (catch Exception e e))]
            (is (= ::smolvm/startup-recovery-failed (:type (ex-data error))))
            (is (= :feature-disabled (-> error ex-data :failures first :stage)))
            (is (re-find #"迁移或清空" (-> error ex-data :failures first :message)))
            (is (empty? @effects))))
        (finally
          (reset! smolvm/state original))))))

(deftest machine-running-query-propagates-errors
  (testing "严格状态查询传播 CLI 异常"
    (with-redefs [process/run (fn [_]
                                {:exit 1 :out "" :err "状态查询失败"})]
      (let [error (try
                    (smolvm-cli/machine-running-strict? "vm-a")
                    nil
                    (catch Exception e e))]
        (is (re-find #"状态查询失败" (.getMessage error)))))))

(deftest sync-machines-cli-availability-is-fail-closed
  (let [original @smolvm/state
        saved? (atom false)]
    (try
      (testing "空库在 CLI 缺失时跳过且不保存"
        (with-redefs [app-config/config (assoc app-config/config :smolvm-mutations-enabled true)
                      smolvm/load-entries! #(reset! smolvm/state {:machines {} :used-ports #{} :ttyd-procs {}})
                      smolvm-cli/available? (constantly false)
                      smolvm/save-entries! #(reset! saved? true)]
          (is (nil? (smolvm/sync-machines!)))
          (is (false? @saved?))))
      (testing "非空库在 CLI 缺失时返回结构化失败"
        (with-redefs [app-config/config (assoc app-config/config :smolvm-mutations-enabled true)
                      smolvm/load-entries! #(reset! smolvm/state {:machines {"a" {:id "a" :name "vm-a"}}
                                                                  :used-ports #{}
                                                                  :ttyd-procs {}})
                      smolvm-cli/available? (constantly false)
                      smolvm/save-entries! #(reset! saved? true)]
          (let [error (try
                        (smolvm/sync-machines!)
                        nil
                        (catch Exception e e))]
            (is (= :culvert.smolvm.manager/startup-recovery-failed
                   (:type (ex-data error))))
            (is (= :cli-availability (-> error ex-data :failures first :stage)))
            (is (false? @saved?)))))
      (finally
        (reset! smolvm/state original)))))

(deftest sync-machines-checks-every-record-and-does-not-save-on-failure
  (let [original @smolvm/state
        checked (atom [])
        saved? (atom false)
        machines {"a" {:id "a" :name "vm-a" :ports {}}
                  "b" {:id "b" :name "vm-b" :ports {}}}]
    (try
      (with-redefs [app-config/config (assoc app-config/config :smolvm-mutations-enabled true)
                    smolvm/load-entries! #(reset! smolvm/state {:machines machines :used-ports #{} :ttyd-procs {}})
                    smolvm-cli/available? (constantly true)
                    smolvm-cli/machine-running-strict? (fn [name]
                                                         (swap! checked conj name)
                                                         (if (= name "vm-a")
                                                           (throw (Exception. "查询失败"))
                                                           false))
                    smolvm/save-entries! #(reset! saved? true)]
        (let [error (try
                      (smolvm/sync-machines!)
                      nil
                      (catch Exception e e))]
          (is (= ["vm-a" "vm-b"] @checked))
          (is (= :machine-status (-> error ex-data :failures first :stage)))
          (is (= "stopped" (get-in @smolvm/state [:machines "b" :status])))
          (is (false? @saved?))))
      (finally
        (reset! smolvm/state original)))))

(deftest ttyd-disabled-machine-remains-running-without-shell-recovery
  (let [original @smolvm/state
        original-caddy @caddy/state
        effects (atom [])
        machine {:id "a" :name "vm-a" :ttydPid 123
                 :caddyRuleId nil :ports {} :status "running"}]
    (try
      (with-redefs [app-config/config (assoc app-config/config
                                             :smolvm-mutations-enabled true
                                             :ttyd-enabled false)
                    smolvm/load-entries! #(reset! smolvm/state {:machines {"a" machine}
                                                                :used-ports #{}
                                                                :ttyd-procs {}})
                    smolvm-cli/available? (constantly true)
                    smolvm-cli/machine-running-strict? (constantly true)
                    smolvm/start-ttyd! (fn [_] (swap! effects conj :ttyd))
                    caddy/add-managed-rule! (fn [& _] (swap! effects conj :caddy))
                    smolvm/save-entries! #(swap! effects conj :save)]
        (smolvm/sync-machines!)
        (is (= [:save] @effects))
        (is (= "running" (get-in @smolvm/state [:machines "a" :status])))
        (is (nil? (get-in @smolvm/state [:machines "a" :ttydPid])))
        (is (nil? (get-in @smolvm/state [:machines "a" :caddyRuleId]))))
      (finally
        (reset! caddy/state original-caddy)
        (reset! smolvm/state original)))))

(deftest refresh-status-respects-disabled-ttyd
  (let [original @smolvm/state
        machine {:id "a" :name "vm-a" :ttydPid 123}]
    (try
      (reset! smolvm/state {:machines {"a" machine} :used-ports #{} :ttyd-procs {}})
      (with-redefs [app-config/config (assoc app-config/config :ttyd-enabled false)
                    smolvm-cli/machine-running-strict? (constantly true)]
        (is (= "running" (smolvm/refresh-status! "a")))
        (is (nil? (get-in @smolvm/state [:machines "a" :ttydPid]))))
      (finally
        (reset! smolvm/state original)))))

(deftest sync-machines-propagates-ttyd-and-port-recovery-failures
  (let [original @smolvm/state
        original-caddy @caddy/state
        saved? (atom false)]
    (try
      (testing "启用 ttyd 的 VM 恢复失败会阻止启动"
        (let [machine {:id "a" :name "vm-a" :ports {}}]
          (with-redefs [app-config/config (assoc app-config/config
                                                 :smolvm-mutations-enabled true
                                                 :ttyd-enabled true)
                        smolvm/load-entries! #(reset! smolvm/state {:machines {"a" machine}
                                                                    :used-ports #{}
                                                                    :ttyd-procs {}})
                        smolvm-cli/available? (constantly true)
                        smolvm-cli/machine-running-strict? (constantly true)
                        smolvm/start-ttyd! (fn [_] (throw (Exception. "ttyd 恢复失败")))
                        smolvm/save-entries! #(reset! saved? true)]
            (let [error (try (smolvm/sync-machines!) nil (catch Exception e e))]
              (is (= :culvert.smolvm.manager/startup-recovery-failed
                     (:type (ex-data error))))
              (is (= "error" (get-in @smolvm/state [:machines "a" :status])))
              (is (false? @saved?))))))
      (testing "运行 VM 的启用端口规则恢复异常不会被吞掉"
        (let [machine {:id "a" :name "vm-a"
                       :ports {"8080" {:enabled true :smolvmPort 18080}}}]
          (with-redefs [app-config/config (assoc app-config/config
                                                 :smolvm-mutations-enabled true
                                                 :ip-mode false
                                                 :ttyd-enabled false)
                        smolvm/load-entries! #(reset! smolvm/state {:machines {"a" machine}
                                                                    :used-ports #{}
                                                                    :ttyd-procs {}})
                        smolvm-cli/available? (constantly true)
                        smolvm-cli/machine-running-strict? (constantly true)
                        caddy/add-managed-rule! (fn [& _] (throw (Exception. "端口规则恢复失败")))
                        smolvm/save-entries! #(reset! saved? true)]
            (let [error (try (smolvm/sync-machines!) nil (catch Exception e e))]
              (is (re-find #"端口规则恢复失败" (-> error ex-data :failures first :message)))
              (is (false? @saved?))))))
      (finally
        (reset! caddy/state original-caddy)
        (reset! smolvm/state original)))))

(deftest sync-machines-rolls-back-new-ttyd-and-reports-rollback-failure
  (let [original @smolvm/state
        original-caddy @caddy/state
        effects (atom [])
        machine {:id "a" :name "vm-a" :ttydPid nil
                 :caddyRuleId nil :ports {}}]
    (try
      (with-redefs [app-config/config (assoc app-config/config
                                             :smolvm-mutations-enabled true
                                             :ip-mode false
                                             :ttyd-enabled true)
                    smolvm/load-entries! #(reset! smolvm/state {:machines {"a" machine}
                                                                :used-ports #{}
                                                                :ttyd-procs {}})
                    smolvm-cli/available? (constantly true)
                    smolvm-cli/machine-running-strict? (constantly true)
                    smolvm/ttyd-alive? (constantly false)
                    smolvm/start-ttyd! (fn [_] (swap! effects conj :start-ttyd) 456)
                    caddy/add-managed-rule! (fn [& _] (throw (Exception. "shell 规则恢复失败")))
                    smolvm/stop-ttyd! (fn [_ strict?]
                                        (swap! effects conj [:stop-ttyd strict?])
                                        (throw (Exception. "ttyd 回滚失败")))
                    smolvm/save-entries! #(swap! effects conj :save)]
        (let [error (try (smolvm/sync-machines!) nil (catch Exception e e))
              failures (:failures (ex-data error))]
          (is (= [:start-ttyd [:stop-ttyd true]] @effects))
          (is (= [:recovery :ttyd-rollback] (mapv :stage failures)))
          (is (= "error" (get-in @smolvm/state [:machines "a" :status])))))
      (finally
        (reset! caddy/state original-caddy)
        (reset! smolvm/state original)))))
