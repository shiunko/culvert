(ns culvert.caddy-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [culvert.authorization :as authorization]
            [culvert.caddy :as caddy]
            [culvert.config :as config]
            [culvert.db :as db]
            [culvert.runtime.process :as process])
  (:import [java.net InetAddress]))

(defn- set-entries! [entries]
  (reset! caddy/state {:entries entries :caddy-proc nil}))

(deftest test-key-helpers
  (testing "构建并解析 Caddy 键"
    (let [k (caddy/build-key "example.org" "worker1")
          parsed (caddy/parse-key k)]
      (is (= k "example.org@@worker1"))
      (is (= (:domain parsed) "example.org"))
      (is (= (:worker parsed) "worker1")))))

(defn- test-resolver [host]
  [(InetAddress/getByName
    (case host
      "example.com" "93.184.216.34"
      "localhost" "127.0.0.1"
      "metadata-alias.test" "169.254.169.254"
      host))])

(deftest test-target-validation
  (with-redefs [caddy/resolve-host-addresses test-resolver]
    (testing "接受 HTTP(S)，包括现有容器代理使用的回环和私网目标"
      (doseq [target ["http://example.com"
                      "https://example.com:8443"
                      "http://127.0.0.1:8080"
                      "http://localhost:3000"
                      "http://10.0.0.2:8080"
                      "http://172.16.1.2:8080"
                      "http://192.168.99.2:8080"
                      "http://[::1]:8080"]]
        (is (= target (caddy/validate-target! target)) target)))

    (testing "拒绝格式错误、特殊用途和替代数字格式的目标"
      (doseq [target [nil
                      ""
                      " http://example.com"
                      "http://example.com/path with-space"
                      "http://example.com/\nheader"
                      "http://example.com/%0aheader"
                      "ftp://example.com"
                      "http:/missing-host"
                      "http://user:pass@example.com"
                      "http://example.com:0"
                      "http://example.com:65536"
                      "http://example.com:not-a-port"
                      "http://169.254.169.254/latest/meta-data"
                      "http://[fe80::1]"
                      "http://[::]"
                      "http://[ff02::1]"
                      "http://[::ffff:169.254.169.254]"
                      "http://2852039166"
                      "http://0177.0.0.1"
                      "http://100.100.100.200/latest/meta-data"
                      "http://192.0.0.192/opc/v2/instance"
                      "http://metadata.google.internal/computeMetadata/v1"
                      "http://metadata-alias.test/latest/meta-data"]]
        (is (thrown-with-msg? Exception #"目标地址" (caddy/validate-target! target))
            (str "应拒绝目标：" (pr-str target)))))))

(deftest test-address-policy
  (testing "拒绝未指定、链路本地、组播和元数据地址"
    (doseq [address ["0.0.0.0" "169.254.1.1" "224.0.0.1" "::" "fe80::1" "ff02::1"
                     "100.100.100.200" "192.0.0.192" "fd00:ec2::254"]]
      (is (caddy/unsafe-target-address? (InetAddress/getByName address)) address)))
  (testing "保留内部代理兼容性"
    (doseq [address ["127.0.0.1" "10.0.0.2" "172.16.1.2" "192.168.1.2" "::1"]]
      (is (false? (boolean (caddy/unsafe-target-address? (InetAddress/getByName address)))) address))))

(deftest test-caddyfile-generation
  (testing "初始状态无需读取真实配置即可生成空规则说明"
    (set-entries! {})
    (is (str/includes? (caddy/generate-caddyfile) "暂无启用条目")))

  (testing "生成启用条目并保留现有回环地址兼容性"
    (set-entries! {(keyword (caddy/build-key "example.org" "worker1"))
                   {:target "http://127.0.0.1:8080" :enabled true}})
    (let [content (caddy/generate-caddyfile)]
      (is (str/includes? content "reverse_proxy http://127.0.0.1:8080"))
      (is (str/includes? content "worker1.example.org"))))

  (testing "显式候选条目不依赖当前 atom"
    (set-entries! {})
    (let [candidate {(keyword (caddy/build-key "example.org" "candidate"))
                     {:target "http://127.0.0.1:8081" :enabled true}}
          content (caddy/generate-caddyfile candidate)]
      (is (str/includes? content "candidate.example.org"))
      (is (str/includes? content "reverse_proxy http://127.0.0.1:8081"))))

  (testing "生成配置前再次校验持久化条目"
    (set-entries! {(keyword (caddy/build-key "example.org" "worker1"))
                   {:target "http://169.254.169.254/latest/meta-data" :enabled true}})
    (is (thrown-with-msg? Exception #"目标地址" (caddy/generate-caddyfile)))))

(deftest test-caddy-unavailable-is-fail-closed
  (let [enabled-entry {(keyword (caddy/build-key "example.org" "worker1"))
                       {:target "http://127.0.0.1:8080" :enabled true}}
        writes (atom 0)]
    (set-entries! enabled-entry)
    (with-redefs [caddy/available? (constantly false)
                  caddy/write-caddyfile! (fn [& _] (swap! writes inc))]
      (testing "启动和重载启用规则时不会因 Caddy 不可用而静默成功"
        (doseq [operation [caddy/start-caddy! caddy/reload-caddy!]]
          (let [error (try
                        (operation)
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
            (is (= :culvert.caddy/caddy-unavailable
                   (:type (ex-data error))))))
        (is (zero? @writes))))))

(deftest test-sync-caddy-startup-recovery
  (let [enabled-entry {(keyword (caddy/build-key "example.org" "worker1"))
                       {:target "http://127.0.0.1:8080" :enabled true}}
        starts (atom 0)]
    (testing "IP 模式即使存在启用规则也跳过 Caddy"
      (with-redefs [config/config (assoc config/config :ip-mode true)
                    caddy/load-entries! #(set-entries! enabled-entry)
                    caddy/start-caddy! #(swap! starts inc)]
        (caddy/sync-caddy!)
        (is (zero? @starts))))

    (testing "域名模式没有启用规则时跳过 Caddy"
      (with-redefs [config/config (assoc config/config :ip-mode false)
                    caddy/load-entries! #(set-entries! {})
                    caddy/start-caddy! #(swap! starts inc)]
        (caddy/sync-caddy!)
        (is (zero? @starts))))

    (testing "域名模式存在启用规则且 Caddy 不可用时返回稳定恢复失败"
      (with-redefs [config/config (assoc config/config :ip-mode false)
                    caddy/load-entries! #(set-entries! enabled-entry)
                    caddy/available? (constantly false)]
        (let [error (try
                      (caddy/sync-caddy!)
                      nil
                      (catch clojure.lang.ExceptionInfo e e))
              data (ex-data error)]
          (is (= :culvert.caddy/startup-recovery-failed (:type data)))
          (is (vector? (:failures data)))
          (is (= [:culvert.caddy/caddy-unavailable]
                 (mapv :type (:failures data)))))))))

(deftest test-add-rule-validates-before-side-effects
  (set-entries! {})
  (let [saved? (atom false)
        reloaded? (atom false)]
    (with-redefs [config/config (assoc config/config :caddy-domains ["example.org"])
                  caddy/save-entries! (fn [& _] (reset! saved? true))
                  caddy/write-caddyfile! (fn [& _] (reset! reloaded? true))
                  caddy/available? (constantly true)
                  process/run (constantly {:exit 0 :out "" :err ""})]
      (is (thrown-with-msg? Exception #"目标地址"
                            (caddy/add-rule! "example.org"
                                             "worker1"
                                             "http://169.254.169.254/latest/meta-data"
                                             "test"
                                             (authorization/actor "admin" true))))
      (is (empty? (:entries @caddy/state)))
      (is (false? @saved?))
      (is (false? @reloaded?)))))

(deftest test-add-rule-requires-owner-and-never-overwrites
  (with-redefs [config/config (assoc config/config :caddy-domains ["example.org"])
                caddy/resolve-host-addresses test-resolver
                caddy/save-entries! (fn [& _])
                caddy/write-caddyfile! (fn [& _])
                caddy/available? (constantly true)
                process/run (constantly {:exit 0 :out "" :err ""})]
    (let [key (keyword (caddy/build-key "example.org" "worker1"))]
      (set-entries! {})
      (is (thrown? Exception
                   (caddy/add-rule! "example.org" "worker1" "http://example.com" "" nil)))
      (is (thrown? Exception
                   (caddy/add-rule! "example.org" "worker1" "http://example.com" "" "")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"仅管理员"
                            (caddy/add-rule! "example.org" "worker1" "http://example.com" ""
                                             (authorization/actor "alice" false))))
      (caddy/add-managed-rule! "example.org" "worker1" "http://example.com" "" "alice")
      (is (= "alice" (get-in @caddy/state [:entries key :userId])))
      (swap! caddy/state assoc-in [:entries key :enabled] false)
      (is (thrown? Exception
                   (caddy/add-managed-rule! "example.org" "worker1" "http://example.com" "" "bob")))
      (is (= "alice" (get-in @caddy/state [:entries key :userId]))))))

(deftest test-rule-access-is-fail-closed
  (let [alice (authorization/actor "alice" false)
        bob (authorization/actor "bob" false)
        admin (authorization/actor "root" true)
        key (keyword (caddy/build-key "example.org" "worker1"))
        entry {:target "http://127.0.0.1:8080" :enabled true :userId "alice"}]
    (with-redefs [caddy/save-entries! (fn [& _])
                  caddy/write-caddyfile! (fn [& _])
                  caddy/available? (constantly true)
                  process/run (constantly {:exit 0 :out "" :err ""})]
      (testing "查询要求有效操作者且仅公开显式所有权"
        (set-entries! {key entry
                       (keyword (caddy/build-key "example.org" "legacy")) {:userId caddy/legacy-user}
                       (keyword (caddy/build-key "example.org" "nil-owner")) {:userId nil}
                       (keyword (caddy/build-key "example.org" "empty-owner")) {:userId ""}})
        (is (thrown? Exception (caddy/get-entries nil)))
        (is (= ["example.org@@worker1"] (mapv :key (caddy/get-entries alice))))
        (is (= ["example.org@@worker1"] (mapv :key (caddy/get-entries "alice"))))
        (is (= 4 (count (caddy/get-entries admin)))))

      (testing "变更操作拒绝非管理员访问缺失、他人和旧版所有者资源"
        (doseq [owner [nil "" caddy/legacy-user "bob"]]
          (set-entries! {key (assoc entry :userId owner)})
          (is (thrown? Exception (caddy/remove-rule! "example.org" "worker1" alice)))
          (is (thrown? Exception (caddy/toggle-rule! "example.org" "worker1" alice)))
          (is (thrown? Exception (caddy/update-remark! "example.org" "worker1" "x" alice))))
        (set-entries! {key entry})
        (is (thrown? Exception (caddy/remove-rule! "example.org" "worker1" bob)))
        (is (thrown? Exception (caddy/remove-rule! "example.org" "worker1" nil true))))

      (testing "所有者和管理员可以执行变更操作"
        (set-entries! {key entry})
        (is (false? (caddy/toggle-rule! "example.org" "worker1" "alice")))
        (caddy/update-remark! "example.org" "worker1" "updated" alice)
        (is (= "updated" (get-in @caddy/state [:entries key :remark])))
        (set-entries! {key (assoc entry :userId nil)})
        (caddy/remove-rule! "example.org" "worker1" admin)
        (is (empty? (:entries @caddy/state)))))))

(deftest test-write-caddyfile-validates-candidate
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "culvert-caddy-test"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        caddyfile (io/file dir "Caddyfile")]
    (try
      (set-entries! {(keyword (caddy/build-key "example.org" "worker1"))
                     {:target "http://127.0.0.1:8080" :enabled true}})
      (spit caddyfile "old-valid-config")

      (testing "校验成功后原子安装候选配置"
        (let [validated-content (atom nil)]
          (with-redefs [caddy/caddyfile-path (.getAbsolutePath caddyfile)
                        caddy/caddy-path "/fake/caddy"
                        process/run (fn [argv]
                                      (is (= ["/fake/caddy" "validate" "--config"]
                                             (vec (take 3 argv))))
                                      (is (= ["--adapter" "caddyfile"]
                                             (vec (take-last 2 argv))))
                                      (reset! validated-content (slurp (nth argv 3)))
                                      {:exit 0 :out "" :err ""})]
            (caddy/write-caddyfile!)
            (is (str/includes? @validated-content "reverse_proxy http://127.0.0.1:8080"))
            (is (= @validated-content (slurp caddyfile))))))

      (testing "校验失败时保留原有有效配置"
        (spit caddyfile "old-valid-config")
        (with-redefs [caddy/caddyfile-path (.getAbsolutePath caddyfile)
                      caddy/caddy-path "/fake/caddy"
                      process/run (fn [_]
                                    {:exit 1 :out "" :err "invalid config"})]
          (is (thrown-with-msg? Exception #"Caddy 配置校验失败"
                                (caddy/write-caddyfile!)))
          (is (= "old-valid-config" (slurp caddyfile)))))
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

(defn- temp-caddyfile []
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "culvert-caddy-mutation-test"
                      (make-array java.nio.file.attribute.FileAttribute 0)))]
    {:dir dir :file (io/file dir "Caddyfile")}))

(defn- delete-tree! [dir]
  (doseq [file (reverse (file-seq dir))]
    (.delete file)))

(deftest test-load-migration-persists-before-publishing
  (let [old-key :worker1
        old-state {(keyword (caddy/build-key "example.org" "existing"))
                   {:target "http://127.0.0.1:8080" :enabled true :userId "alice"}}]
    (set-entries! old-state)
    (with-redefs [config/config (assoc config/config :caddy-domain "example.org")
                  db/read-json (fn [_ _]
                                 {old-key {:target "http://127.0.0.1:8081"
                                           :enabled true}})
                  db/write-json (fn [& _]
                                  (throw (ex-info "数据库写入失败" {:type ::db-failed})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"数据库写入失败"
                            (caddy/load-entries!)))
      (is (= old-state (:entries @caddy/state))))))

(deftest test-mutation-failures-keep-old-state
  (let [{:keys [dir file]} (temp-caddyfile)
        key (keyword (caddy/build-key "example.org" "worker1"))
        old-entries {key {:target "http://127.0.0.1:8080"
                          :enabled true
                          :remark "旧说明"
                          :userId "alice"}}
        actor (authorization/actor "alice" false)]
    (try
      (spit file "旧 Caddyfile")
      (set-entries! old-entries)
      (testing "候选校验失败时不改 Caddyfile、JSON 和 atom"
        (let [saved (atom [])]
          (with-redefs [caddy/caddyfile-path (.getAbsolutePath file)
                        caddy/caddy-path "/fake/caddy"
                        caddy/available? (constantly true)
                        caddy/save-entries! #(swap! saved conj %)
                        process/run (fn [argv]
                                      (if (= "validate" (second argv))
                                        {:exit 1 :out "" :err "无效配置"}
                                        {:exit 0 :out "" :err ""}))]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"配置校验失败"
                                  (caddy/toggle-rule! "example.org" "worker1" actor)))
            (is (= "旧 Caddyfile" (slurp file)))
            (is (empty? @saved))
            (is (= old-entries (:entries @caddy/state))))))

      (testing "reload 失败时恢复旧 Caddyfile 并重载旧配置"
        (spit file "旧 Caddyfile")
        (set-entries! old-entries)
        (let [reload-count (atom 0)
              saved (atom [])]
          (with-redefs [caddy/caddyfile-path (.getAbsolutePath file)
                        caddy/caddy-path "/fake/caddy"
                        caddy/available? (constantly true)
                        caddy/save-entries! #(swap! saved conj %)
                        process/run (fn [argv]
                                      (case (second argv)
                                        "validate" {:exit 0 :out "" :err ""}
                                        "reload" (if (= 1 (swap! reload-count inc))
                                                   {:exit 1 :out "" :err "重载失败"}
                                                   {:exit 0 :out "" :err ""})))
                        process/spawn (fn [& _]
                                        (throw (ex-info "Caddy 启动失败"
                                                        {:type ::caddy-start-failed})))]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Caddy 启动失败"
                                  (caddy/update-remark! "example.org" "worker1" "新说明" actor)))
            (is (= 2 @reload-count))
            (is (= "旧 Caddyfile" (slurp file)))
            (is (empty? @saved))
            (is (= old-entries (:entries @caddy/state))))))

      (testing "JSON 写入失败时恢复旧 Caddyfile、重载旧配置并保留 atom"
        (spit file "旧 Caddyfile")
        (set-entries! old-entries)
        (let [reload-count (atom 0)]
          (with-redefs [caddy/caddyfile-path (.getAbsolutePath file)
                        caddy/caddy-path "/fake/caddy"
                        caddy/available? (constantly true)
                        caddy/save-entries! (fn [_]
                                              (throw (ex-info "数据库写入失败" {:type ::db-failed})))
                        process/run (fn [argv]
                                      (case (second argv)
                                        "validate" {:exit 0 :out "" :err ""}
                                        "reload" (do
                                                   (swap! reload-count inc)
                                                   {:exit 0 :out "" :err ""})))]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"数据库写入失败"
                                  (caddy/update-remark! "example.org" "worker1" "新说明" actor)))
            (is (= 2 @reload-count))
            (is (= "旧 Caddyfile" (slurp file)))
            (is (= old-entries (:entries @caddy/state))))))
      (finally
        (delete-tree! dir)))))

(deftest test-mutation-compensation-failure-is-stable
  (let [{:keys [dir file]} (temp-caddyfile)
        key (keyword (caddy/build-key "example.org" "worker1"))
        old-entries {key {:target "http://127.0.0.1:8080"
                          :enabled true
                          :userId "alice"}}
        reload-count (atom 0)]
    (try
      (spit file "旧 Caddyfile")
      (set-entries! old-entries)
      (with-redefs [caddy/caddyfile-path (.getAbsolutePath file)
                    caddy/caddy-path "/fake/caddy"
                    caddy/available? (constantly true)
                    caddy/save-entries! (fn [_]
                                          (throw (ex-info "数据库写入失败" {:type ::db-failed})))
                    process/run (fn [argv]
                                  (case (second argv)
                                    "validate" {:exit 0 :out "" :err ""}
                                    "reload" (if (= 1 (swap! reload-count inc))
                                               {:exit 0 :out "" :err ""}
                                               {:exit 1 :out "" :err "补偿重载失败"})))
                    process/spawn (fn [& _]
                                    (throw (ex-info "补偿重载失败"
                                                    {:type ::caddy/config-reload-failed})))]
        (let [error (try
                      (caddy/update-remark! "example.org" "worker1" "新说明"
                                            (authorization/actor "alice" false))
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= :culvert.caddy/mutation-compensation-failed
                 (:type (ex-data error))))
          (is (= :culvert.caddy-test/db-failed
                 (:failure-type (ex-data error))))
          (is (= :culvert.caddy/config-reload-failed
                 (:compensation-type (ex-data error))))
          (is (= "旧 Caddyfile" (slurp file)))
          (is (= old-entries (:entries @caddy/state)))))
      (finally
        (delete-tree! dir)))))

(deftest test-concurrent-mutations-use-latest-snapshot
  (let [saved (atom [])
        run-add! (fn [worker owner]
                   (caddy/add-managed-rule! "example.org" worker
                                            "http://127.0.0.1:8080" "" owner))]
    (with-redefs [config/config (assoc config/config :caddy-domains ["example.org"])
                  caddy/write-caddyfile! (fn [_] (Thread/sleep 10))
                  caddy/available? (constantly true)
                  process/run (constantly {:exit 0 :out "" :err ""})
                  caddy/save-entries! #(swap! saved conj %)]
      (testing "不同键并发变更不丢更新"
        (set-entries! {})
        (let [results (mapv deref [(future (run-add! "worker1" "alice"))
                                   (future (run-add! "worker2" "bob"))])]
          (is (every? nil? results))
          (is (= #{"example.org@@worker1" "example.org@@worker2"}
                 (set (map name (keys (:entries @caddy/state))))))
          (is (= 2 (count (last @saved))))))

      (testing "同键并发添加只有一个成功"
        (set-entries! {})
        (reset! saved [])
        (let [attempt #(try
                         (run-add! "worker1" %)
                         :ok
                         (catch Exception _ :conflict))
              results (mapv deref [(future (attempt "alice"))
                                   (future (attempt "bob"))])]
          (is (= #{:ok :conflict} (set results)))
          (is (= 1 (count (:entries @caddy/state))))
          (is (= 1 (count @saved))))))))
