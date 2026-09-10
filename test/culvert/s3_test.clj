(ns culvert.s3-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [culvert.config :as config]
            [culvert.i18n :as i18n]
            [culvert.runtime.process :as process]
            [culvert.s3 :as s3]))

(defn- isolated-state [test-fn]
  (let [original @s3/state]
    (try
      (reset! s3/state {})
      (test-fn)
      (finally
        (reset! s3/state original)))))

(use-fixtures :each isolated-state)

(defn- test-s3-config [root enabled?]
  {:enabled enabled?
   :fs-type "goofys"
   :fs-path "/bin/true"
   :host-mount-root (.getAbsolutePath root)
   :mount-timeout-sec 1
   :default-options {:dir-mode "0755"
                     :file-mode "0644"
                     :uid "1000"
                     :gid "1000"}})

(deftest s3-availability-test
  (testing "available? 返回布尔值"
    (is (instance? Boolean (s3/available?)))))

(deftest s3-state-test
  (testing "状态 atom 保存 map"
    (is (map? @s3/state))))

(deftest connection-validation-test
  (testing "连接测试拒绝缺失凭据"
    (with-redefs [config/config (assoc config/config :s3 {:enabled true
                                                          :fs-path "/bin/true"})]
      (is (false? (:success (s3/test-connection {:bucket ""
                                                 :accessKey "key"
                                                 :secretKey "secret"}))))
      (is (false? (:success (s3/test-connection {:bucket "bucket"
                                                 :accessKey ""
                                                 :secretKey "secret"}))))
      (is (false? (:success (s3/test-connection {:bucket "bucket"
                                                 :accessKey "key"
                                                 :secretKey ""})))))))

(deftest dynamic-disable-takes-effect-test
  (let [effects (atom [])
        credentials {:bucket "bucket" :accessKey "access" :secretKey "secret"}
        original-config config/config]
    (try
      (alter-var-root #'config/config
                      #(assoc % :s3 {:enabled true :fs-path "/bin/sh"}))
      (is (true? (s3/available?)))
      (alter-var-root #'config/config #(assoc % :s3 {:enabled false}))
      (with-redefs [process/run (fn [& args] (swap! effects conj [:run args]))
                    process/spawn (fn [& args] (swap! effects conj [:spawn args]))]
        (is (= {:mountPoint nil :status "disabled"}
               (s3/mount! "container" credentials)))
        (is (= {:success false
                :message (get-in i18n/strings [:s3 :unavailable])}
               (s3/test-connection credentials)))
        (is (nil? (s3/sync-mounts! {})))
        (is (empty? @effects)))
      (finally
        (alter-var-root #'config/config (constantly original-config))))))

(deftest unknown-mount-is-only-reported-test
  (let [root (io/file (str (System/getProperty "java.io.tmpdir")
                           "/s3-sync-unknown-" (random-uuid)))
        orphan (io/file root "unknown")
        commands (atom [])]
    (try
      (.mkdirs orphan)
      (with-redefs [config/config (assoc config/config :s3 (test-s3-config root true))
                    clojure.core/slurp (fn [_]
                                         (str "goofys " (.getAbsolutePath orphan)
                                              " fuse.goofys rw 0 0\n"))
                    process/run (fn [argv]
                                  (swap! commands conj argv)
                                  {:exit 0 :out "" :err ""})]
        (is (nil? (s3/sync-mounts! {})))
        (is (empty? @commands))
        (is (.exists orphan)))
      (finally
        (.delete orphan)
        (.delete root)))))

(deftest missing-expected-mount-fails-closed-test
  (let [root (io/file (str (System/getProperty "java.io.tmpdir")
                           "/s3-sync-missing-" (random-uuid)))
        secret "不得出现在异常中的密钥"
        containers {"id" {:name "expected"
                          :s3Mount {:enabled true
                                    :status "mounted"
                                    :mountPoint (.getAbsolutePath (io/file root "expected"))
                                    :bucket "bucket"
                                    :accessKey "access"
                                    :secretKey secret}}}]
    (try
      (.mkdirs root)
      (with-redefs [config/config (assoc config/config :s3 (test-s3-config root true))]
        (let [error (try
                      (s3/sync-mounts! containers)
                      nil
                      (catch clojure.lang.ExceptionInfo e e))
              data (ex-data error)]
          (is (= :culvert.s3/startup-recovery-failed (:type data)))
          (is (vector? (:failures data)))
          (is (= "expected" (get-in data [:failures 0 :container-name])))
          (is (not (.contains (pr-str data) secret)))))
      (finally
        (.delete root)))))

(deftest cleanup-failure-retains-state-test
  (let [root (io/file (str (System/getProperty "java.io.tmpdir")
                           "/s3-cleanup-failure-" (random-uuid)))
        mount-dir (io/file root "container")
        child (io/file mount-dir "保留目录以触发删除失败")
        entry {:mountPoint (.getAbsolutePath mount-dir)
               :proc :managed-process
               :status "mounted"}]
    (try
      (.mkdirs mount-dir)
      (spit child "data")
      (reset! s3/state {"container" entry})
      (with-redefs [config/config (assoc config/config :s3 (test-s3-config root true))
                    clojure.core/slurp (fn [_]
                                         (str "goofys " (.getAbsolutePath mount-dir)
                                              " fuse.goofys rw 0 0\n"))
                    process/destroy! (fn [_] (throw (ex-info "停止失败" {})))
                    process/run (constantly {:exit 1 :out "" :err "卸载失败"})]
        (let [error (try
                      (s3/unmount! "container")
                      nil
                      (catch clojure.lang.ExceptionInfo e e))
              data (ex-data error)]
          (is (= :culvert.s3/cleanup-failed (:type data)))
          (is (= [:destroy :unmount :delete]
                 (mapv :stage (:failures data))))
          (is (= entry (get @s3/state "container")))))
      (finally
        (.delete child)
        (.delete mount-dir)
        (.delete root)))))

(deftest get-status-unmounted-test
  (testing "未知容器返回未挂载"
    (is (= {:mountPoint nil :status "unmounted"}
           (s3/get-status "nonexistent-container-xyz")))))

(deftest get-all-mounts-test
  (testing "get-all-mounts 返回序列"
    (is (sequential? (s3/get-all-mounts)))))
