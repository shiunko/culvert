(ns culvert.runtime.process-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [culvert.runtime.process :as process]))

(defn- process-alive? [process-pid]
  (let [candidate (java.lang.ProcessHandle/of (long process-pid))]
    (and (.isPresent candidate)
         (.isAlive (.get candidate)))))

(defn- wait-for-content [file timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (let [content (when (.exists file) (str/trim (slurp file)))]
        (cond
          (seq content) content
          (>= (System/nanoTime) deadline) nil
          :else (do (Thread/sleep 10) (recur)))))))

(deftest run-returns-structured-result
  (let [command ["sh" "-c" "printf hello; printf warning >&2"]
        result (process/run command)]
    (is (= 0 (:exit result)))
    (is (= "hello" (:out result)))
    (is (= "warning" (:err result)))
    (is (= command (:command result)))
    (is (nat-int? (:duration-ms result)))))

(deftest run-has-explicit-non-zero-policy
  (testing "默认把非零退出作为结构化结果返回"
    (let [result (process/run ["sh" "-c" "printf out; printf err >&2; exit 7"])]
      (is (= 7 (:exit result)))
      (is (= "out" (:out result)))
      (is (= "err" (:err result)))
      (is (vector? (:command result)))
      (is (nat-int? (:duration-ms result)))))
  (testing "检查模式抛出包含完整结果的异常"
    (let [error (try
                  (process/run ["sh" "-c" "printf out; printf err >&2; exit 9"]
                               {:check? true})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? error))
      (is (= 9 (:exit (ex-data error))))
      (is (= "out" (:out (ex-data error))))
      (is (= "err" (:err (ex-data error)))))))

(deftest run-enforces-timeouts
  (let [started-at (System/nanoTime)
        error (try
                (process/run ["sh" "-c" "trap '' TERM; while :; do sleep 1; done"]
                             {:timeout-ms 25
                              :grace-timeout-ms 50
                              :force-timeout-ms 300})
                nil
                (catch clojure.lang.ExceptionInfo error error))
        elapsed-ms (quot (- (System/nanoTime) started-at) 1000000)]
    (is (= ::process/timeout (:type (ex-data error))))
    (is (= ["sh" "-c" "trap '' TERM; while :; do sleep 1; done"]
           (:command (ex-data error))))
    (is (= 25 (:timeout-ms (ex-data error))))
    (is (< elapsed-ms 1500))))

(deftest commands-require-an-argv-vector
  (doseq [argv [nil [] '("printf" "unsafe") "printf unsafe"]]
    (is (thrown? IllegalArgumentException (process/run argv)))))

(deftest spawn-supports-streaming-and-lifecycle
  (testing "流式输出与正常退出"
    (let [handle (process/spawn ["sh" "-c" "printf streamed"] {:out :stream :err :string})]
      (is (pos? (process/pid handle)))
      (is (= "streamed" (slurp (io/reader (:out handle)))))
      (is (= 0 (process/wait handle)))
      (is (false? (process/alive? handle)))))
  (testing "存活进程可以温和停止"
    (let [handle (process/spawn ["sh" "-c" "sleep 30"] {:out :inherit :err :inherit})]
      (try
        (is (process/alive? handle))
        (let [result (process/destroy! handle)]
          (is (= (process/pid handle) (:pid result)))
          (is (empty? (:remaining-pids result))))
        (is (integer? (process/wait handle)))
        (is (false? (process/alive? handle)))
        (finally
          (when (process/alive? handle)
            (process/destroy! handle)))))))

(deftest destroy-is-idempotent
  (testing "nil 句柄可重复停止"
    (doseq [_ (range 2)]
      (is (= [] (:pids (process/destroy! nil))))
      (is (= [] (:remaining-pids (process/destroy! nil))))))
  (testing "已退出句柄可重复停止"
    (let [handle (process/spawn ["sh" "-c" "exit 0"])]
      (is (= 0 (process/wait handle)))
      (doseq [_ (range 2)]
        (let [result (process/destroy! handle)]
          (is (= (process/pid handle) (:pid result)))
          (is (empty? (:pids result)))
          (is (empty? (:remaining-pids result))))))))

(deftest destroy-forces-a-process-that-ignores-term
  (let [ready-file (java.io.File/createTempFile "culvert-ready-" ".flag")
        command ["sh" "-c"
                 (str "trap '' TERM; printf ready > " (.getAbsolutePath ready-file)
                      "; while :; do sleep 1; done")]
        handle (process/spawn command {:out :inherit :err :inherit})
        root-pid (process/pid handle)]
    (try
      (is (= "ready" (wait-for-content ready-file 1000)))
      (let [started-at (System/nanoTime)
            result (process/destroy! handle {:grace-timeout-ms 75
                                             :force-timeout-ms 300})
            elapsed-ms (quot (- (System/nanoTime) started-at) 1000000)]
        (is (some #{root-pid} (:forced-pids result)))
        (is (empty? (:remaining-pids result)))
        (is (false? (process/alive? handle)))
        (is (< elapsed-ms 1500)))
      (finally
        (when (process/alive? handle)
          (process/destroy! handle {:grace-timeout-ms 0
                                    :force-timeout-ms 300}))
        (.delete ready-file)))))

(deftest destroy-terminates-the-process-tree
  (let [pid-file (java.io.File/createTempFile "culvert-child-" ".pid")
        command ["sh" "-c"
                 (str "trap '' TERM; "
                      "sh -c 'trap \"\" TERM; while :; do sleep 1; done' & "
                      "printf '%s' \"$!\" > " (.getAbsolutePath pid-file) "; wait")]
        handle (process/spawn command {:out :inherit :err :inherit})]
    (try
      (let [child-text (wait-for-content pid-file 1000)]
        (is (some? child-text))
        (when child-text
          (let [child-pid (parse-long child-text)
                result (process/destroy! handle {:grace-timeout-ms 75
                                                 :force-timeout-ms 500})]
            (is (some #{child-pid} (:pids result)))
            (is (empty? (:remaining-pids result)))
            (is (false? (process-alive? child-pid)))
            (is (false? (process/alive? handle))))))
      (finally
        (when (process/alive? handle)
          (process/destroy! handle {:grace-timeout-ms 0
                                    :force-timeout-ms 500}))
        (.delete pid-file)))))
