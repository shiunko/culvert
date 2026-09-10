(ns culvert.runner
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]))

(defn- test-file? [file]
  (and (.isFile file)
       (str/ends-with? (.getName file) "_test.clj")))

(defn- file->namespace [file]
  (-> (.getPath file)
      (str/replace "\\" "/")
      (str/replace #"^test/" "")
      (str/replace #"\.clj$" "")
      (str/replace "_" "-")
      (str/replace "/" ".")
      symbol))

(defn discover-test-namespaces []
  (->> (file-seq (io/file "test"))
       (filter test-file?)
       (map file->namespace)
       sort
       vec))

(defn -main [& _]
  (let [test-namespaces (discover-test-namespaces)]
    (doseq [test-ns test-namespaces]
      (require test-ns))
    (let [{:keys [fail error]} (apply t/run-tests test-namespaces)]
      (System/exit (+ fail error)))))
