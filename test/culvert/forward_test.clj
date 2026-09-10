(ns culvert.forward-test
  (:require [clojure.test :refer [deftest is testing]]
            [culvert.db :as db]
            [culvert.forward :as forward]
            [culvert.runtime.process :as process]))

(deftest test-port-validation
  (testing "单端口校验"
    (is (nil? (forward/validate-port-input "8080" "端口")))
    (is (some? (forward/validate-port-input "invalid" "端口")))
    (is (some? (forward/validate-port-input "70000" "端口")))
    (is (some? (forward/validate-port-input "" "端口"))))

  (testing "端口范围校验"
    (is (nil? (forward/validate-port-input "8080-8085" "端口")))
    (is (some? (forward/validate-port-input "8080-8079" "端口"))) ; 起始端口必须小于结束端口
    (is (some? (forward/validate-port-input "8080-invalid" "端口")))))

(deftest test-port-key-helpers
  (testing "构建并解析端口键"
    (let [k-socat (forward/build-port-key "8080" "tcp" "socat")
          k-ipt (forward/build-port-key "8080" "udp" "iptables")
          parsed-socat (forward/parse-port-key k-socat)
          parsed-ipt (forward/parse-port-key k-ipt)]
      (is (= k-socat "8080_tcp"))
      (is (= k-ipt "8080_udp_ipt"))
      (is (= (:port parsed-socat) "8080"))
      (is (= (:protocol parsed-socat) "tcp"))
      (is (= (:method parsed-socat) "socat"))
      (is (= (:port parsed-ipt) "8080"))
      (is (= (:protocol parsed-ipt) "udp"))
      (is (= (:method parsed-ipt) "iptables")))))

(deftest legacy-forwards-are-admin-only
  (let [legacy-entry {:ip "127.0.0.1"
                      :toPort 8080
                      :enabled false
                      :remark "legacy"
                      :userId forward/legacy-user}]
    (reset! forward/state {:port-db {:8080_tcp legacy-entry} :kill-db {}})
    (is (empty? (forward/get-entries "alice" false)))
    (is (= 1 (count (forward/get-entries "admin" true))))
    (with-redefs [db/write-json (fn [& _])]
      (is (thrown? Exception
                   (forward/update-remark! "8080" "tcp" "socat" "changed" "alice" false)))
      (forward/update-remark! "8080" "tcp" "socat" "changed" "admin" true)
      (is (= "changed" (get-in @forward/state [:port-db :8080_tcp :remark]))))))

(defn- capture-error [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      error)))

(defn- entry [ip to-port]
  {:ip ip
   :toPort to-port
   :enabled true
   :remark ""
   :userId "alice"})

(defn- await-result [future-result]
  (deref future-result 5000 ::timeout))

(deftest load-migration-writes-before-publishing
  (let [initial {:port-db {:9000_tcp (entry "10.0.0.1" 9000)} :kill-db {}}
        legacy {:8080_tcp "10.0.0.2:8080"}]
    (reset! forward/state initial)
    (with-redefs [db/read-json (fn [& _] legacy)
                  db/write-json (fn [& _]
                                  (throw (ex-info "写入失败" {})))]
      (is (thrown? Exception (forward/load-entries!)))
      (is (= initial @forward/state)))))

(deftest update-remark-writes-before-publishing
  (let [initial {:port-db {:8080_tcp (entry "10.0.0.2" 8080)} :kill-db {}}]
    (reset! forward/state initial)
    (with-redefs [db/write-json (fn [& _]
                                  (throw (ex-info "写入失败" {})))]
      (is (thrown? Exception
                   (forward/update-remark! "8080" "tcp" "socat" "新备注" "alice")))
      (is (= initial @forward/state)))))

(deftest concurrent-adds-do-not-lose-updates
  (reset! forward/state {:port-db {} :kill-db {}})
  (let [ready (promise)
        writes (atom [])]
    (with-redefs [forward/iptables-add! (fn [& _])
                  forward/iptables-remove! (fn [& _])
                  db/write-json (fn [_ candidate]
                                  (swap! writes conj candidate)
                                  (deliver ready true))]
      (let [first-add (future (forward/add-forward! "14100" "tcp" "10.0.0.1" 15100 "iptables" "" "alice"))
            _ (deref ready 5000 ::timeout)
            second-add (future (forward/add-forward! "14101" "tcp" "10.0.0.2" 15101 "iptables" "" "alice"))]
        (is (nil? (await-result first-add)))
        (is (nil? (await-result second-add)))
        (is (= #{:14100_tcp_ipt :14101_tcp_ipt}
               (set (keys (:port-db @forward/state)))))
        (is (= [#{:14100_tcp_ipt} #{:14100_tcp_ipt :14101_tcp_ipt}]
               (mapv (comp set keys) @writes)))))))

(deftest concurrent-adds-of-same-key-have-one-winner
  (reset! forward/state {:port-db {} :kill-db {}})
  (let [start (promise)
        add-one #(future
                   (deref start)
                   (try
                     (forward/add-forward! "14200" "tcp" "10.0.0.3" 15200 "iptables" "" "alice")
                     :ok
                     (catch Exception _
                       :failed)))]
    (with-redefs [forward/iptables-add! (fn [& _])
                  forward/iptables-remove! (fn [& _])
                  db/write-json (fn [& _])]
      (let [first-add (add-one)
            second-add (add-one)]
        (deliver start true)
        (is (= {:ok 1 :failed 1}
               (frequencies [(await-result first-add) (await-result second-add)])))
        (is (= 1 (count (:port-db @forward/state))))))))

(deftest add-db-failure-rolls-back-effects-and-memory
  (let [initial {:port-db {} :kill-db {}}
        events (atom [])]
    (reset! forward/state initial)
    (with-redefs [forward/iptables-add! (fn [_ port _ _]
                                          (swap! events conj [:add port]))
                  forward/iptables-remove! (fn [_ port _ _]
                                             (swap! events conj [:remove port]))
                  db/write-json (fn [& _]
                                  (throw (ex-info "写入失败" {})))]
      (is (thrown? Exception
                   (forward/add-forward! "14300-14301" "tcp" "10.0.0.4" 15300 "iptables" "" "alice")))
      (is (= [[:add 14300] [:add 14301] [:remove 14301] [:remove 14300]] @events))
      (is (= initial @forward/state)))))

(deftest remove-and-toggle-db-failures-restore-effects-and-memory
  (doseq [[operation expected-events]
          [[#(forward/remove-forward! "14600" "tcp" "iptables" "alice")
            [[:remove 14600] [:add 14600]]]
           [#(forward/toggle-forward! "14600" "tcp" "iptables" "alice")
            [[:remove 14600] [:add 14600]]]]]
    (let [initial {:port-db {:14600_tcp_ipt (entry "10.0.0.9" 15600)} :kill-db {}}
          events (atom [])]
      (reset! forward/state initial)
      (with-redefs [forward/iptables-add! (fn [_ port _ _]
                                            (swap! events conj [:add port]))
                    forward/iptables-remove! (fn [_ port _ _]
                                               (swap! events conj [:remove port]))
                    db/write-json (fn [& _]
                                    (throw (ex-info "写入失败" {})))]
        (is (thrown? Exception (operation)))
        (is (= expected-events @events))
        (is (= initial @forward/state))))))

(deftest range-failure-rolls-back-completed-ports-in-reverse-order
  (let [events (atom [])]
    (reset! forward/state {:port-db {} :kill-db {}})
    (with-redefs [forward/iptables-add! (fn [_ port _ _]
                                          (swap! events conj [:add port])
                                          (when (= port 14402)
                                            (throw (ex-info "添加失败" {}))))
                  forward/iptables-remove! (fn [_ port _ _]
                                             (swap! events conj [:remove port]))
                  db/write-json (fn [& _])]
      (is (thrown? Exception
                   (forward/add-forward! "14400-14402" "tcp" "10.0.0.5" 15400 "iptables" "" "alice")))
      (is (= [[:add 14400] [:add 14401] [:add 14402] [:remove 14401] [:remove 14400]] @events))
      (is (empty? (:port-db @forward/state))))))

(deftest mutation-compensation-failure-is-stable-and-sanitized
  (reset! forward/state {:port-db {} :kill-db {}})
  (with-redefs [forward/iptables-add! (fn [& _])
                forward/iptables-remove! (fn [& _]
                                           (throw (ex-info "敏感目标 10.0.0.6" {})))
                db/write-json (fn [& _]
                                (throw (ex-info "敏感数据库路径" {})))]
    (let [error (capture-error #(forward/add-forward! "14500" "tcp" "10.0.0.6" 15500 "iptables" "" "alice"))
          data (ex-data error)]
      (is (= ::forward/mutation-compensation-failed (:type data)))
      (is (= :rollback (:phase data)))
      (is (= [{:phase :rollback :port 14500 :protocol "tcp" :method "iptables"}]
             (:failures data)))
      (is (= #{:type :phase :failures} (set (keys data))))
      (is (nil? (ex-cause error))))))

(deftest iptables-pairs-compensate-the-first-rule
  (testing "添加第二条规则失败时删除第一条"
    (let [calls (atom [])]
      (with-redefs [process/run (fn [argv _]
                                  (swap! calls conj argv)
                                  (when (= 2 (count @calls))
                                    (throw (ex-info "第二步失败" {}))))]
        (is (thrown? Exception (forward/iptables-add! "tcp" 4600 "10.0.0.7" 5600)))
        (is (= ["-A" "-A" "-D"] (mapv #(nth % 3) @calls))))))
  (testing "删除第二条规则失败时恢复第一条"
    (let [calls (atom [])]
      (with-redefs [process/run (fn [argv _]
                                  (swap! calls conj argv)
                                  (when (= 2 (count @calls))
                                    (throw (ex-info "第二步失败" {}))))]
        (is (thrown? Exception (forward/iptables-remove! "tcp" 4601 "10.0.0.8" 5601)))
        (is (= ["-D" "-D" "-A"] (mapv #(nth % 3) @calls)))))))
(deftest iptables-compensation-failure-is-stable-and-sanitized
  (let [calls (atom 0)]
    (with-redefs [process/run (fn [& _]
                                (let [call (swap! calls inc)]
                                  (when (> call 1)
                                    (throw (ex-info "敏感规则" {})))))]
      (let [error (capture-error #(forward/iptables-add! "tcp" 4602 "10.0.0.9" 5602))
            data (ex-data error)]
        (is (= ::forward/iptables-compensation-failed (:type data)))
        (is (= :rollback (:phase data)))
        (is (= [{:phase :rollback :operation :add}] (:failures data)))
        (is (nil? (ex-cause error)))))))

(deftest sync-forwards-fails-closed
  (let [entry {:ip "10.0.0.8"
               :toPort 9000
               :enabled true
               :remark ""
               :userId "alice"}]
    (with-redefs [forward/load-entries! #(reset! forward/state
                                                 {:port-db {:8080_tcp_ipt entry}
                                                  :kill-db {}})
                  forward/iptables-remove! (fn [& _])
                  forward/iptables-add! (fn [& _]
                                          (throw (ex-info "敏感目标 10.0.0.8:9000" {})))]
      (let [error (capture-error forward/sync-forwards!)
            data (ex-data error)]
        (is (= ::forward/startup-recovery-failed (:type data)))
        (is (= [{:phase :apply
                 :port 8080
                 :protocol "tcp"
                 :method "iptables"}]
               (:failures data)))
        (is (= #{:type :failures} (set (keys data))))))))

(deftest sync-forwards-skips-disabled-rules
  (let [calls (atom [])
        entry {:ip "10.0.0.9"
               :toPort 9001
               :enabled false
               :remark ""
               :userId "alice"}]
    (with-redefs [forward/load-entries! #(reset! forward/state
                                                 {:port-db {:8081_tcp_ipt entry}
                                                  :kill-db {}})
                  forward/iptables-remove! (fn [& args]
                                             (swap! calls conj [:remove args]))
                  forward/iptables-add! (fn [& args]
                                          (swap! calls conj [:add args]))]
      (forward/sync-forwards!)
      (is (empty? @calls)))))

(deftest sync-forwards-rolls-back-started-ports-in-reverse-order
  (let [events (atom [])
        applying? (atom false)
        entry {:ip "10.0.0.10"
               :toPort 9100
               :enabled true
               :remark ""
               :userId "alice"}]
    (with-redefs [forward/load-entries! #(reset! forward/state
                                                 {:port-db {:4100-4102_tcp_ipt entry}
                                                  :kill-db {}})
                  forward/iptables-add! (fn [_ port _ _]
                                          (reset! applying? true)
                                          (swap! events conj [:apply port])
                                          (when (= port 4102)
                                            (throw (ex-info "应用失败" {}))))
                  forward/iptables-remove! (fn [_ port _ _]
                                             (when @applying?
                                               (swap! events conj [:rollback port])
                                               (when (= port 4101)
                                                 (throw (ex-info "回滚失败" {})))))]
      (let [error (capture-error forward/sync-forwards!)
            failures (:failures (ex-data error))]
        (is (= [[:apply 4100]
                [:apply 4101]
                [:apply 4102]
                [:rollback 4101]
                [:rollback 4100]]
               @events))
        (is (= [{:phase :apply
                 :port 4102
                 :protocol "tcp"
                 :method "iptables"}
                {:phase :rollback
                 :port 4101
                 :protocol "tcp"
                 :method "iptables"}]
               failures))))))
