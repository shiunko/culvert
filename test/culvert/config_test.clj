(ns culvert.config-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.test :refer [deftest is testing]]
            [culvert.config :as config]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "culvert-config-test-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [root]
  (doseq [file (reverse (file-seq root))]
    (.delete file)))

(deftest config-compatibility
  (testing "兼容配置值仍是普通映射"
    (is (map? config/config))
    (is (contains? config/config :port))
    (is (= "127.0.0.1" (:listen-address config/config)))
    (is (contains? config/config :public-ip))
    (is (contains? config/config :container-cli))
    (is (contains? config/config :db-path))
    (is (contains? config/config :s3))
    (is (false? (:docker-mutations-enabled config/config)))
    (is (false? (:smolvm-mutations-enabled config/config)))
    (is (false? (get-in config/config [:s3 :enabled])))
    (is (false? (:ttyd-enabled config/config)))
    (is (contains? config/config :ttyd-max-clients)))

  (testing "默认配额值仍然可用"
    (let [quota (:default-quota config/config)]
      (is (integer? (:portForwards quota)))
      (is (integer? (:reverseProxies quota)))
      (is (integer? (:containers quota))))))

(deftest resolves-config-and-data-directories-independently
  (let [root (temp-dir)
        config-dir (io/file root "configuration")
        data-dir (io/file root "state")]
    (try
      (let [paths (config/resolve-paths
                   {"CULVERT_CONFIG_DIR" (.getPath config-dir)
                    "CULVERT_DATA_DIR" (.getPath data-dir)})
            state-keys [:caddyfile-path :db-path :caddy-db-path :docker-db-path
                        :users-db-path :smolvm-db-path]]
        (is (= (.getAbsolutePath config-dir) (:config-dir paths)))
        (is (= (.getAbsolutePath data-dir) (:data-dir paths)))
        (is (true? (:enforce-sensitive-permissions paths)))
        (is (= (.getAbsolutePath (io/file config-dir "config.json"))
               (:config-file paths)))
        (doseq [key state-keys]
          (is (= (.getCanonicalPath data-dir)
                 (.getCanonicalPath (.getParentFile (io/file (key paths))))))))
      (finally (delete-tree! root)))))

(deftest default-development-paths-do-not-enforce-production-permissions
  (is (false? (:enforce-sensitive-permissions (config/resolve-paths {})))))

(deftest reads-and-validates-config-in-isolation
  (let [root (temp-dir)
        file (io/file root "config.json")]
    (try
      (spit file (json/generate-string {:port "10443"
                                        :publicIp "192.0.2.10"
                                        :authUser "operator"
                                        :authPass "a-long-random-password"}))
      (let [raw (config/read-config file)]
        (is (= raw (config/validate-config raw)))
        (is (= 10443 (:port (config/build-config raw (config/resolve-paths {}))))))

      (doseq [raw [{:port 10092 :publicIp "127.0.0.1"
                    :authUser "CHANGE_ME" :authPass "safe-password"}
                   {:port 10092 :publicIp "127.0.0.1"
                    :authUser "admin" :authPass "admin123"}
                   {:port 10092 :publicIp "127.0.0.1"
                    :authUser "admin" :authPass "CHANGE_ME_WITH_A_LONG_RANDOM_PASSWORD"}]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"默认凭据"
                              (config/validate-config raw))))
      (finally (delete-tree! root)))))

(deftest mutation-features-require-explicit-enablement
  (let [paths (config/resolve-paths {})
        defaults (config/build-config {:port 10092 :publicIp "127.0.0.1"} paths)
        enabled (config/build-config {:port 10092
                                      :publicIp "127.0.0.1"
                                      :dockerMutationsEnabled true
                                      :smolvmMutationsEnabled true
                                      :s3 {:enabled true}}
                                     paths)]
    (testing "变更功能默认关闭"
      (is (false? (:docker-mutations-enabled defaults)))
      (is (false? (:smolvm-mutations-enabled defaults)))
      (is (false? (get-in defaults [:s3 :enabled])))
      (is (false? (:ttyd-enabled defaults))))
    (testing "显式配置为 true 时开启变更功能"
      (is (true? (:docker-mutations-enabled enabled)))
      (is (true? (:smolvm-mutations-enabled enabled)))
      (is (true? (get-in enabled [:s3 :enabled]))))))

(deftest path-search-does-not-use-a-shell
  (let [root (temp-dir)
        executable (io/file root "fake-tool")]
    (try
      (spit executable "#!/bin/sh\nexit 0\n")
      (.setExecutable executable true false)
      (with-redefs [sh/sh (fn [& _] (throw (ex-info "shell must not run" {})))]
        (is (= (.getAbsolutePath executable)
               (config/find-command "fake-tool" (.getAbsolutePath root))))
        (is (nil? (config/find-command "missing-tool" (.getAbsolutePath root)))))
      (finally (delete-tree! root)))))

(deftest capabilities-are-detected-only-when-requested
  (let [root (temp-dir)
        executable (io/file root "fake-tool")
        application-config {:socat-path "fake-tool"
                            :iptables-path "missing"
                            :caddy-path "missing"
                            :container-cli-path "missing"
                            :ttyd-path "missing"
                            :smolvm-path "missing"}]
    (try
      (spit executable "#!/bin/sh\nexit 0\n")
      (.setExecutable executable true false)
      (with-redefs [sh/sh (fn [& _] (throw (ex-info "version probe should not run" {})))]
        (let [capabilities (config/detect-capabilities application-config (.getAbsolutePath root))]
          (is (true? (get-in capabilities [:socat-path :available?])))
          (is (= (.getAbsolutePath executable) (get-in capabilities [:socat-path :path])))
          (is (false? (get-in capabilities [:iptables-path :available?])))
          (is (false? (:smolvm-available? capabilities)))))
      (finally (delete-tree! root)))))

(deftest permission-check-is-read-only
  (let [root (temp-dir)
        secret (io/file root "users.json")]
    (try
      (spit secret "{}")
      (let [before (java.nio.file.Files/getPosixFilePermissions
                    (.toPath secret) (make-array java.nio.file.LinkOption 0))]
        (java.nio.file.Files/setPosixFilePermissions
         (.toPath secret)
         (java.nio.file.attribute.PosixFilePermissions/fromString "rw-r--r--"))
        (let [issues (config/check-sensitive-permissions [secret])
              after (java.nio.file.Files/getPosixFilePermissions
                     (.toPath secret) (make-array java.nio.file.LinkOption 0))]
          (is (= :overly-permissive (:type (first issues))))
          (is (= (java.nio.file.attribute.PosixFilePermissions/fromString "rw-r--r--") after)))
        (java.nio.file.Files/setPosixFilePermissions (.toPath secret) before))
      (finally (delete-tree! root)))))

(deftest unsafe-sensitive-permissions-fail-closed
  (let [root (temp-dir)
        secret (io/file root "users.json")
        application-config {:enforce-sensitive-permissions true
                            :users-db-path (.getAbsolutePath secret)}]
    (try
      (spit secret "{}")
      (let [before (java.nio.file.Files/getPosixFilePermissions
                    (.toPath secret) (make-array java.nio.file.LinkOption 0))]
        (java.nio.file.Files/setPosixFilePermissions
         (.toPath secret)
         (java.nio.file.attribute.PosixFilePermissions/fromString "rw-r--r--"))
        (let [error (try
                      (config/validate-sensitive-permissions! application-config)
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= ::config/unsafe-sensitive-permissions (:type (ex-data error))))
          (is (= :overly-permissive
                 (get-in (ex-data error) [:issues 0 :type]))))
        (java.nio.file.Files/setPosixFilePermissions (.toPath secret) before))
      (finally (delete-tree! root)))))
