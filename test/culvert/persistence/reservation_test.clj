(ns culvert.persistence.reservation-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent CountDownLatch TimeUnit]))

(def jvm? (nil? (System/getProperty "babashka.version")))

(defn- jvm-call [symbol & arguments]
  (apply (requiring-resolve symbol) arguments))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "culvert-reservation-test"
                                      (make-array FileAttribute 0))))

(defn- delete-tree! [file]
  (when (.exists file)
    (doseq [child (reverse (file-seq file))]
      (.delete child))))

(defn- execute! [connectable statement]
  (jvm-call 'next.jdbc/execute! connectable statement))

(defn- execute-one! [connectable statement]
  (jvm-call 'next.jdbc/execute-one! connectable statement))

(defn- insert-user! [connectable username]
  (execute-one! connectable
                ["INSERT INTO users(username, password_hash, role, auth_version, quotas_json, created_at) VALUES (?, 'hash', 'user', 1, '{}', '2026-01-01T00:00:00Z')"
                 username]))

(defn- call-with-database [f]
  (let [directory (temp-dir)
        database (io/file directory "state.sqlite")
        datasource (jvm-call 'culvert.persistence.sqlite/datasource database)]
    (try
      (jvm-call 'culvert.persistence.sqlite/migrate! datasource)
      (insert-user! datasource "alice")
      (f datasource directory)
      (finally (delete-tree! directory)))))

(defn- reserve! [datasource request]
  (jvm-call 'culvert.persistence.reservation/reserve!
            datasource {:username "alice"} request))

(deftest migrates-v1-reservations-with-default-bind-address
  (if-not jvm?
    (is true "SQLite JDBC tests are JVM-only")
    (let [directory (temp-dir)
          database (io/file directory "state.sqlite")
          datasource (jvm-call 'culvert.persistence.sqlite/datasource database)]
      (try
        (let [migrations (var-get (requiring-resolve 'culvert.persistence.schema/migrations))
              v1 (first migrations)]
          (execute! datasource
                    ["CREATE TABLE migrations (version INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, applied_at TEXT NOT NULL)"])
          (doseq [statement (:statements v1)]
            (execute! datasource [statement]))
          (execute-one! datasource
                        ["INSERT INTO migrations(version, name, applied_at) VALUES (1, ?, '2026-01-01T00:00:00Z')"
                         (:name v1)])
          (insert-user! datasource "alice")
          (execute-one! datasource
                        ["INSERT INTO port_reservations(id, owner, protocol, port, status, expires_at, created_at) VALUES ('old', 'alice', 'tcp', 4100, 'reserved', 2000000000, '2026-01-01T00:00:00Z')"])
          (is (= [1 2] (jvm-call 'culvert.persistence.sqlite/migrate! datasource)))
          (is (= "0.0.0.0"
                 (:bind-address
                  (first (jvm-call 'culvert.persistence.reservation/list-active
                                   datasource {:now 1900000000})))))
          (is (= ["bind_address" "protocol" "port"]
                 (->> (execute! datasource ["PRAGMA index_info('sqlite_autoindex_port_reservations_2')"])
                      (mapv :name)))))
        (finally (delete-tree! directory))))))

(deftest reservation-lifecycle-and-bind-address-uniqueness
  (if-not jvm?
    (is true "SQLite JDBC tests are JVM-only")
    (call-with-database
     (fn [datasource _]
       (let [now (quot (System/currentTimeMillis) 1000)
             request {:protocol :tcp :port 4200 :ttl-seconds 60 :now now}
             first-reservation (first (reserve! datasource request))]
         (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already reserved"
                               (reserve! datasource
                                         (assoc request :bind-address "127.0.0.1"))))
         (is (= ["0.0.0.0"]
                (mapv :bind-address
                      (jvm-call 'culvert.persistence.reservation/list-active
                                datasource {:now (inc now)}))))
         (is (= "committed"
                (:status (jvm-call 'culvert.persistence.reservation/commit!
                                   datasource (:id first-reservation) nil))))
         (is (true? (jvm-call 'culvert.persistence.reservation/release!
                              datasource (:id first-reservation))))
         (is (= 4200 (:port (first (reserve! datasource request))))))))))

(deftest range-reservation-is-all-or-nothing
  (if-not jvm?
    (is true "SQLite JDBC tests are JVM-only")
    (call-with-database
     (fn [datasource _]
       (reserve! datasource {:protocol "udp" :port 4301 :ttl-seconds 60 :now 1000})
       (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"already reserved"
            (reserve! datasource
                      {:protocol "udp" :port-range [4300 4302]
                       :ttl-seconds 60 :now 1000})))
       (is (= [4301]
              (mapv :port
                    (jvm-call 'culvert.persistence.reservation/list-active
                              datasource {:now 1001}))))))))

(deftest expired-reservations-are-reclaimed
  (if-not jvm?
    (is true "SQLite JDBC tests are JVM-only")
    (call-with-database
     (fn [datasource _]
       (reserve! datasource {:protocol :tcp :ports [4400 4401]
                             :ttl-seconds 5 :now 1000})
       (is (= 2 (jvm-call 'culvert.persistence.reservation/expire! datasource 1005)))
       (is (empty? (jvm-call 'culvert.persistence.reservation/list-active
                             datasource {:now 1005})))
       (is (= [4400 4401]
              (mapv :port
                    (reserve! datasource {:protocol :tcp :port-range [4400 4401]
                                          :ttl-seconds 5 :now 1005}))))))))

(deftest concurrent-reservation-allows-one-winner
  (if-not jvm?
    (is true "SQLite JDBC tests are JVM-only")
    (call-with-database
     (fn [datasource _]
       (let [ready (CountDownLatch. 2)
             start (CountDownLatch. 1)
             attempt (fn []
                       (.countDown ready)
                       (.await start)
                       (try
                         {:ok (reserve! datasource
                                        {:bind-address "127.0.0.1"
                                         :protocol :tcp :port 4500
                                         :ttl-seconds 60})}
                         (catch Exception exception
                           {:error exception})))
             workers [(future (attempt)) (future (attempt))]]
         (is (.await ready 5 TimeUnit/SECONDS))
         (.countDown start)
         (let [results (mapv #(deref % 10000 {:timeout true}) workers)]
           (is (= 1 (count (filter :ok results))))
           (is (= 1 (count (filter :error results))))
           (is (not-any? :timeout results))
           (is (= 1 (count (jvm-call 'culvert.persistence.reservation/list-active
                                     datasource))))))))))
