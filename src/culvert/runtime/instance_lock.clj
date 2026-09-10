(ns culvert.runtime.instance-lock
  "Exclusive process lock for a mutable data directory."
  (:require [clojure.java.io :as io])
  (:import [java.io RandomAccessFile]
           [java.util UUID]))

(defn acquire!
  "Acquire an exclusive lock file in data-dir. Throws when another process in
  this JVM or on the host already owns it. Returns a closeable lock handle."
  [data-dir]
  (let [directory (io/file data-dir)
        _ (.mkdirs directory)
        file (io/file directory ".culvert.lock")
        process-id (str (UUID/randomUUID))
        random-access-file (RandomAccessFile. file "rw")
        channel (.getChannel random-access-file)]
    (try
      (let [lock (try
                   (.tryLock channel)
                   (catch Exception _ nil))]
        (when-not lock
          (throw (ex-info "Another culvert instance owns the data directory"
                          {:type ::already-locked
                           :data-dir (.getAbsolutePath directory)
                           :lock-file (.getAbsolutePath file)})))
        (.setLength random-access-file 0)
        (.write random-access-file (.getBytes (str process-id "\n") "UTF-8"))
        {:file file
         :random-access-file random-access-file
         :channel channel
         :lock lock})
      (catch Throwable error
        (.close channel)
        (.close random-access-file)
        (throw error)))))

(defn release! [{:keys [lock channel random-access-file]}]
  (when lock
    (try (.release lock) (catch Exception _)))
  (when channel
    (try (.close channel) (catch Exception _)))
  (when random-access-file
    (try (.close random-access-file) (catch Exception _)))
  nil)
