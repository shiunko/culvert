(ns culvert.cli-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [culvert.cli :as cli]
            [culvert.config :as config]))

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
