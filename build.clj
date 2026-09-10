(ns build
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b])
  (:import [java.security MessageDigest]
           [java.time Instant]))

(def lib 'culvert/culvert)
(def version "4.0.0")
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))
(def expected-java-version "26.0.2")
(def expected-runtime-version "26.0.2+10")

(defn- verify-jdk! []
  (let [vendor (System/getProperty "java.vendor")
        java-version (System/getProperty "java.version")
        runtime-version (System/getProperty "java.runtime.version")]
    (when-not (and (= expected-java-version java-version)
                   (= expected-runtime-version runtime-version)
                   (or (str/includes? vendor "Adoptium")
                       (str/includes? vendor "Temurin")))
      (throw (ex-info "Build requires Eclipse Temurin 26.0.2+10"
                      {:vendor vendor
                       :java-version java-version
                       :runtime-version runtime-version})))
    {:vendor vendor :java-version java-version :runtime-version runtime-version}))

(defn- git-revision []
  (or (not-empty (System/getenv "GITHUB_SHA"))
      (try
        (let [process (.start (ProcessBuilder. ["git" "rev-parse" "HEAD"]))
              revision (str/trim (slurp (.getInputStream process)))]
          (when (and (zero? (.waitFor process)) (not-empty revision)) revision))
        (catch Exception _ nil))
      "unknown"))

(defn- write-build-info! [jdk]
  (spit (io/file class-dir "culvert-build.edn")
        (pr-str {:version version
                 :revision (git-revision)
                 :built-at (str (Instant/now))
                 :jdk jdk})))

(defn- sha256 [file]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [input (io/input-stream file)]
      (let [buffer (byte-array 8192)]
        (loop []
          (let [read (.read input buffer)]
            (when (pos? read)
              (.update digest buffer 0 read)
              (recur))))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest digest)))))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (let [jdk (verify-jdk!)]
    (clean nil)
    (b/copy-dir {:src-dirs (filterv #(.exists (io/file %)) ["src" "resources"])
                 :target-dir class-dir})
    (when (.exists (io/file "public"))
      (b/copy-dir {:src-dirs ["public"]
                   :target-dir (str class-dir "/public")}))
    (write-build-info! jdk)
    (b/compile-clj {:basis @basis
                    :src-dirs ["src"]
                    :class-dir class-dir
                    :ns-compile '[culvert.main]})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis @basis
             :main 'culvert.main})
    (let [checksum (sha256 uber-file)
          checksum-file (str uber-file ".sha256")]
      (spit checksum-file (str checksum "  " (.getName (io/file uber-file)) "\n"))
      (println "Created" uber-file)
      (println "SHA-256" checksum))))
