(ns culvert.persistence.sqlite-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [culvert.persistence.repository :as repository])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def jvm? (nil? (System/getProperty "babashka.version")))

(defn- jvm-call [symbol & arguments]
  (apply (requiring-resolve symbol) arguments))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "culvert-sqlite-test"
                                      (make-array FileAttribute 0))))

(defn- delete-tree! [file]
  (when (.exists file)
    (doseq [child (reverse (file-seq file))]
      (.delete child))))

(defn- call-with-database [f]
  (let [directory (temp-dir)
        database-file (io/file directory "state.sqlite")
        datasource (jvm-call 'culvert.persistence.sqlite/datasource database-file)]
    (try
      (f datasource directory)
      (finally (delete-tree! directory)))))

(defn- execute-one! [connectable statement]
  (jvm-call 'next.jdbc/execute-one! connectable statement))

(defn- insert-user! [connectable username]
  (execute-one! connectable
                ["INSERT INTO users(username, password_hash, role, auth_version, quotas_json, created_at) VALUES (?, 'hash', 'user', 1, '{}', '2026-01-01T00:00:00Z')"
                 username]))

(def resource
  {:id "container:demo" :owner "alice" :resource-type "container"
   :resource-key "demo" :state "running" :data {:image "alpine"}})

(deftest migrations-are-idempotent
  (if-not jvm?
    (is true "SQLite JDBC tests are JVM-only")
    (call-with-database
     (fn [datasource _directory]
       (is (= [1 2] (jvm-call 'culvert.persistence.sqlite/migrate! datasource)))
       (is (= [1 2] (jvm-call 'culvert.persistence.sqlite/migrate! datasource)))
       (is (= [1 2] (jvm-call 'culvert.persistence.sqlite/applied-migrations datasource)))
       (is (= 7
              (-> (execute-one! datasource
                                ["SELECT count(*) AS table_count FROM sqlite_master WHERE type = 'table' AND name IN ('migrations', 'users', 'sessions', 'resources', 'port_reservations', 'proxy_rules', 'resource_events')"])
                  first val)))))))

(deftest transaction-rolls-back
  (if-not jvm?
    (is true "SQLite JDBC tests are JVM-only")
    (call-with-database
     (fn [datasource _directory]
       (jvm-call 'culvert.persistence.sqlite/migrate! datasource)
       (is (thrown? Exception
                    (jvm-call 'culvert.persistence.sqlite/with-transaction datasource
                              (fn [transaction]
                                (insert-user! transaction "alice")
                                (throw (ex-info "rollback" {}))))))
       (is (zero? (-> (execute-one! datasource ["SELECT count(*) AS row_count FROM users"])
                      first val)))))))

(deftest sqlite-resource-crud-and-unique-constraint
  (if-not jvm?
    (is true "SQLite JDBC tests are JVM-only")
    (call-with-database
     (fn [datasource _directory]
       (jvm-call 'culvert.persistence.sqlite/migrate! datasource)
       (insert-user! datasource "alice")
       (let [repo (jvm-call 'culvert.persistence.sqlite/sqlite-repository datasource)]
         (is (= "demo" (:resource-key (repository/create-resource! repo resource))))
         (is (= resource (select-keys (repository/get-resource repo (:id resource))
                                      [:id :owner :resource-type :resource-key :state :data])))
         (is (= 1 (count (repository/list-resources repo {:owner "alice"}))))
         (is (= "stopping" (:state (repository/update-resource! repo (:id resource)
                                                                {:state "stopping"}))))
         (is (thrown? Exception
                      (repository/create-resource! repo (assoc resource :id "container:duplicate"))))
         (is (true? (repository/delete-resource! repo (:id resource)))))))))

(deftest backup-and-integrity-check
  (if-not jvm?
    (is true "SQLite JDBC tests are JVM-only")
    (call-with-database
     (fn [datasource directory]
       (jvm-call 'culvert.persistence.sqlite/migrate! datasource)
       (insert-user! datasource "alice")
       (is (= {:ok? true :messages ["ok"]}
              (jvm-call 'culvert.persistence.sqlite/integrity-check datasource)))
       (let [backup (io/file directory "backup/state.sqlite")]
         (is (= (.getAbsolutePath backup)
                (jvm-call 'culvert.persistence.sqlite/backup! datasource backup)))
         (is (.isFile backup))
         (is (= 1 (-> (execute-one! (jvm-call 'culvert.persistence.sqlite/datasource backup)
                                    ["SELECT count(*) AS row_count FROM users"])
                      first val))))))))
