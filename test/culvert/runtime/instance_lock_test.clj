(ns culvert.runtime.instance-lock-test
  (:require [clojure.test :refer [deftest is]]
            [culvert.runtime.instance-lock :as instance-lock]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "culvert-lock-test-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [root]
  (doseq [file (reverse (file-seq root))]
    (.delete file)))

(deftest data-directory-has-a-single-writer
  (let [directory (temp-dir)]
    (try
      (let [first-lock (instance-lock/acquire! directory)]
        (try
          (is (some? first-lock))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Another culvert instance"
                                (instance-lock/acquire! directory)))
          (finally (instance-lock/release! first-lock))))
      (let [second-lock (instance-lock/acquire! directory)]
        (is (some? second-lock))
        (instance-lock/release! second-lock))
      (finally (delete-tree! directory)))))
