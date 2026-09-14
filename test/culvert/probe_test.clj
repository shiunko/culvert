(ns culvert.probe-test
  "TCP 延迟探针的纯函数与连接行为测试。只探测本机临时监听 socket，不访问外部网络。"
  (:require [clojure.test :refer [deftest is testing]]
            [culvert.probe :as probe]))

(deftest parse-target-accepts-common-forms
  (testing "host:port 与带 scheme / 路径的目标都能解析"
    (is (= ["10.0.0.5" 3000] (probe/parse-target "10.0.0.5:3000")))
    (is (= ["example.com" 443] (probe/parse-target "https://example.com:443")))
    (is (= ["127.0.0.1" 8080] (probe/parse-target "http://127.0.0.1:8080/path"))))
  (testing "缺少端口或非字符串返回 nil"
    (is (nil? (probe/parse-target "example.com")))
    (is (nil? (probe/parse-target nil)))
    (is (nil? (probe/parse-target "host:abc")))))

(deftest probe-once-connect-and-refused
  (testing "存在监听 socket 时返回延迟"
    (with-open [server (java.net.ServerSocket. 0)]
      (let [port (.getLocalPort server)
            result (probe/probe-once "127.0.0.1" port 500)]
        (is (true? (:ok? result)))
        (is (integer? (:latency-ms result)))
        (is (not (neg? (:latency-ms result)))))))
  (testing "端口关闭时返回不可达且不抛异常"
    (let [socket (java.net.ServerSocket. 0)
          port (.getLocalPort socket)]
      (.close socket)
      (let [result (probe/probe-once "127.0.0.1" port 500)]
        (is (false? (:ok? result)))
        (is (nil? (:latency-ms result)))))))

(deftest cache-refresh-and-read
  (with-open [server (java.net.ServerSocket. 0)]
    (let [port (.getLocalPort server)]
      (probe/refresh-one! "127.0.0.1" port 500)
      (let [cached (probe/cached "127.0.0.1" port)]
        (is (some? cached))
        (is (true? (:ok? cached)))
        (is (integer? (:checked-at cached))))))
  (testing "未探测过的目标返回 nil"
    (is (nil? (probe/cached "127.0.0.1" 1)))))
