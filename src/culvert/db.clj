(ns culvert.db
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [culvert.log :as log])
  (:import [java.nio.file Files Paths StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(defn- to-path [file-or-str]
  (if (string? file-or-str)
    (Paths/get file-or-str (into-array String []))
    (.toPath file-or-str)))

(defn read-json
  "读取 JSON；仅文件不存在时返回默认值。已有文件损坏时保持关闭，避免后续写入以空库覆盖。"
  [file-path default-val]
  (let [f (io/file file-path)]
    (if (.exists f)
      (try
        (json/parse-string-strict (slurp f) true)
        (catch Exception error
          (throw (ex-info (format "无法读取/解析数据库 %s" file-path)
                          {:type ::corrupt-json
                           :file (.getAbsolutePath f)}
                          error))))
      default-val)))

(defn write-json
  "将完整 JSON 写入同目录临时文件，再以原子替换发布。失败时保留原目标文件。"
  [file-path data]
  (let [target-file (io/file file-path)
        dir (.getParentFile target-file)]
    (when (and dir (not (.exists dir)) (not (.mkdirs dir)))
      (throw (ex-info (format "无法创建数据库目录 %s" dir)
                      {:type ::write-failed :file (.getAbsolutePath target-file) :phase :prepare})))
    (let [temp-path (try
                      (Files/createTempFile (to-path dir) "pfdb" ".tmp" (into-array FileAttribute []))
                      (catch Exception error
                        (throw (ex-info (format "无法创建数据库临时文件 %s" file-path)
                                        {:type ::write-failed
                                         :file (.getAbsolutePath target-file)
                                         :phase :prepare}
                                        error))))]
      (try
        (let [encoded (try
                        (json/generate-string data {:pretty true})
                        (catch Exception error
                          (throw (ex-info (format "无法编码数据库 %s" file-path)
                                          {:type ::write-failed
                                           :file (.getAbsolutePath target-file)
                                           :phase :encode}
                                          error))))]
          (try
            (spit (.toFile temp-path) encoded)
            (catch Exception error
              (throw (ex-info (format "无法写入数据库临时文件 %s" file-path)
                              {:type ::write-failed
                               :file (.getAbsolutePath target-file)
                               :phase :write-temp}
                              error))))
          (try
            (Files/move temp-path (to-path target-file)
                        (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING
                                                        StandardCopyOption/ATOMIC_MOVE]))
            (catch Exception error
              (throw (ex-info (format "无法原子替换数据库 %s" file-path)
                              {:type ::write-failed
                               :file (.getAbsolutePath target-file)
                               :phase :replace}
                              error)))))
        (catch Exception error
          (log/error (format "写入数据库 %s 失败: %s" file-path (.getMessage error)))
          (try
            (Files/deleteIfExists temp-path)
            (catch Exception _))
          (throw error))))))
