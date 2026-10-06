(ns culvert.cli-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [culvert.cli :as cli]
            [culvert.config :as config]
            [culvert.runtime.process :as process]))

;; ============================================================
;; CLI Availability Tests
;; ============================================================

(deftest test-cli-availability
  (testing "available? returns boolean"
    (let [result (cli/available?)]
      (is (instance? Boolean result))))

  (testing "get-version returns a string"
    (let [ver (cli/get-version)]
      (is (string? ver))
      (is (not (str/blank? ver)))))

  (testing "rootless? returns boolean"
    (let [result (cli/rootless?)]
      (is (instance? Boolean result)))))

;; ============================================================
;; Container Name & Command Tests
;; ============================================================

(deftest test-get-ttyd-exec-args
  (testing "ttyd exec args format"
    (let [args (cli/get-ttyd-exec-args "my-container" "/bin/bash")]
      (is (vector? args))
      (is (= (first args) (:container-cli-path config/config)))
      (is (some #(= % "exec") args))
      (is (some #(= % "my-container") args))
      (is (some #(= % "/bin/bash") args)))))

(deftest test-can-access-container-ip
  (testing "can-access-container-ip? returns boolean"
    (let [result (cli/can-access-container-ip?)]
      (is (instance? Boolean result)))))

;; ============================================================
;; 容器日志读取
;; ============================================================

(deftest container-logs-merge-both-streams
  (testing "两路输出均非空时都被保留（容器日志分流 stdout/stderr，只取一路会丢内容）"
    (with-redefs [process/run
                  (fn [_] {:exit 0 :out "out-line\n" :err "err-line\n"})]
      (let [text (cli/get-container-logs "box")]
        (is (str/includes? text "out-line"))
        (is (str/includes? text "err-line")))))
  (testing "无输出时给出占位文案"
    (with-redefs [process/run (fn [_] {:exit 0 :out "" :err ""})]
      (is (= "(无日志输出)" (cli/get-container-logs "box")))))
  (testing "非零退出与进程启动异常均抛 ::logs-failed"
    (with-redefs [process/run
                  (fn [_] {:exit 1 :out "" :err "No such container: box"})]
      (let [error (try (cli/get-container-logs "box") nil (catch Exception e e))]
        (is (= ::cli/logs-failed (:type (ex-data error))))
        (is (str/includes? (.getMessage error) "No such container"))))
    (with-redefs [process/run (fn [_] (throw (Exception. "CLI 不存在")))]
      (let [error (try (cli/get-container-logs "box") nil (catch Exception e e))]
        (is (= ::cli/logs-failed (:type (ex-data error)))))))
  (testing "读取参数按 argv 向量传递，且带 tail 与时间戳"
    (let [argv (atom nil)]
      (with-redefs [process/run (fn [a] (reset! argv a) {:exit 0 :out "x" :err ""})]
        (cli/get-container-logs "box" {:tail 500})
        (is (vector? @argv))
        (is (some #{"--timestamps"} @argv))
        (is (= "500" (second (drop-while #(not= "--tail" %) @argv))))
        (is (= "box" (last @argv)))))))

(deftest inspect-container-details-is-strict
  (testing "严格读取容器 id/名称/状态，失败抛 ::cli/inspect-failed"
    (with-redefs [process/run
                  (fn [_] {:exit 0
                           :out "abc123|/box|nginx:latest|running\n"
                           :err ""})]
      (is (= {:id "abc123" :name "box" :image "nginx:latest" :state "running"}
             (cli/inspect-container-details "box"))))
    (with-redefs [process/run
                  (fn [_] {:exit 1 :out "" :err "No such object: box"})]
      (let [error (try (cli/inspect-container-details "box") nil (catch Exception e e))]
        (is (= ::cli/inspect-failed (:type (ex-data error))))))))
