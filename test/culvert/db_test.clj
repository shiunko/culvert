(ns culvert.db-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [culvert.db :as db]))

(deftest test-json-read-write
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "culvert-db-test-"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
        test-file (io/file directory "state.json")
        test-data {:foo "bar" :baz [1 2 3]}]
    (try
      (testing "Read non-existent file returns default"
        (is (= (db/read-json (.getAbsolutePath test-file) {:default true}) {:default true})))

      (testing "Write and read data atomically"
        (db/write-json (.getAbsolutePath test-file) test-data)
        (is (.exists test-file))
        (is (= (db/read-json (.getAbsolutePath test-file) {}) test-data)))

      (testing "Write failures expose stable context"
        (let [directory-target (.getAbsolutePath directory)
              error (try
                      (db/write-json directory-target {:value true})
                      nil
                      (catch clojure.lang.ExceptionInfo error error))]
          (is (= ::db/write-failed (:type (ex-data error))))
          (is (= :replace (:phase (ex-data error))))
          (is (= directory-target (:file (ex-data error))))))

      (testing "Existing malformed JSON fails closed"
        (spit test-file "{not-json")
        (let [error (try
                      (db/read-json (.getAbsolutePath test-file) {})
                      nil
                      (catch clojure.lang.ExceptionInfo error error))]
          (is (= ::db/corrupt-json (:type (ex-data error))))
          (is (= "{not-json" (slurp test-file)))))
      (finally
        (doseq [file (reverse (file-seq directory))]
          (.delete file))))))
