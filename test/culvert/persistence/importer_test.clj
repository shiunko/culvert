(ns culvert.persistence.importer-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def jvm? (nil? (System/getProperty "babashka.version")))

(defn- jvm-call [symbol & arguments]
  (apply (requiring-resolve symbol) arguments))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "culvert-import-test"
                                      (make-array FileAttribute 0))))

(defn- delete-tree! [file]
  (when (.exists file)
    (doseq [child (reverse (file-seq file))]
      (.delete child))))

(defn- call-with-temp-dir [f]
  (let [directory (temp-dir)]
    (try (f directory) (finally (delete-tree! directory)))))

(defn- write-json! [directory name value]
  (let [file (io/file directory name)]
    (spit file (json/generate-string value))
    (.getAbsolutePath file)))

(defn- valid-sources [directory]
  {:users (write-json! directory "users.json"
                       {:users {:alice {:password "hash" :role "user"
                                        :authVersion 1 :quotas {}
                                        :createdAt "2026-01-01T00:00:00Z"}}})
   :resources {:container {:path (write-json! directory "containers.json"
                                              {:containers {:demo {:userId "alice"
                                                                   :image "alpine"}}})
                           :root-key :containers}}
   :proxies (write-json! directory "caddy.json"
                         {"example.com@@app" {:userId "alice"
                                              :target "http://127.0.0.1:8080"
                                              :enabled true}})})

(deftest malformed-json-fails-without-writing
  (if-not jvm?
    (is true "SQLite JDBC importer tests are JVM-only")
    (call-with-temp-dir
     (fn [directory]
       (let [broken (io/file directory "broken.json")
             database (io/file directory "state.sqlite")]
         (spit broken "{not-json")
         (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed"
                               (jvm-call 'culvert.persistence.importer/import!
                                         (jvm-call 'culvert.persistence.sqlite/datasource database)
                                         {:users (.getAbsolutePath broken) :resources {}}
                                         {:dry-run? false})))
         (is (not (.exists database))))))))

(deftest dry-run-validates-without-opening-database
  (if-not jvm?
    (is true "SQLite JDBC importer tests are JVM-only")
    (call-with-temp-dir
     (fn [directory]
       (let [database (io/file directory "state.sqlite")
             result (jvm-call 'culvert.persistence.importer/import!
                              (jvm-call 'culvert.persistence.sqlite/datasource database)
                              (valid-sources directory)
                              {:dry-run? true})]
         (is (= {:users 1 :resources 1 :proxy-rules 1} (:counts result)))
         (is (:dry-run? result))
         (is (not (.exists database))))))))

(deftest owner-validation-is-strict
  (if-not jvm?
    (is true "SQLite JDBC importer tests are JVM-only")
    (call-with-temp-dir
     (fn [directory]
       (let [sources (valid-sources directory)
             bad-resource (write-json! directory "bad-resource.json"
                                       {:demo {:userId "missing"}})]
         (is (thrown-with-msg? clojure.lang.ExceptionInfo #"owners absent"
                               (jvm-call 'culvert.persistence.importer/build-plan
                                         (assoc-in sources [:resources :container]
                                                   bad-resource)))))))))

(deftest import-writes-only-to-sqlite-and-verifies-counts
  (if-not jvm?
    (is true "SQLite JDBC importer tests are JVM-only")
    (call-with-temp-dir
     (fn [directory]
       (let [database (io/file directory "state.sqlite")
             datasource (jvm-call 'culvert.persistence.sqlite/datasource database)
             sources (valid-sources directory)
             result (jvm-call 'culvert.persistence.importer/import!
                              datasource sources {:dry-run? false})]
         (is (= {:users 1 :resources 1 :proxy-rules 1 :dry-run? false} result))
         (doseq [table ["users" "resources" "proxy_rules"]]
           (is (= 1 (-> (jvm-call 'next.jdbc/execute-one! datasource
                                  [(str "SELECT count(*) AS row_count FROM " table)])
                        first val)))))))))
