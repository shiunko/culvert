(ns culvert.observability.host-test
  "主机指标：纯解析函数与缓存行为。"
  (:require [clojure.test :refer [deftest is testing]]
            [culvert.observability.host :as host]))

(deftest parse-meminfo-sample
  (let [text (str "MemTotal:       16384000 kB\n"
                  "MemFree:         1000000 kB\n"
                  "MemAvailable:    8192000 kB\n"
                  "Buffers:          200000 kB\n")]
    (is (= {:total-kb 16384000 :available-kb 8192000 :used-kb 8192000 :used-pct 50}
           (host/parse-meminfo text)))))

(deftest parse-meminfo-fallbacks
  (is (nil? (host/parse-meminfo "no meminfo here")))
  (is (nil? (host/parse-meminfo nil)))
  (testing "MemAvailable 缺失时退回 MemFree"
    (is (= 300 (:used-kb (host/parse-meminfo "MemTotal: 1000 kB\nMemFree: 700 kB")))))
  (testing "used-pct 四舍五入"
    (is (= 62 (:used-pct (host/parse-meminfo "MemTotal: 32000000 kB\nMemAvailable: 12224000 kB"))))))

(deftest parse-loadavg-sample
  (is (= 0.52 (host/parse-loadavg "0.52 0.58 0.59 1/1000 12345")))
  (is (nil? (host/parse-loadavg nil)))
  (is (nil? (host/parse-loadavg "abc def"))))

(deftest snapshot-caches-within-ttl
  (host/clear-cache!)
  (let [first (host/snapshot)]
    (is (map? first))
    (testing "TTL 内返回同一缓存对象"
      (is (= (:checked-at first) (:checked-at (host/snapshot))))))
  (testing "refresh! 必定重算"
    (is (map? (host/refresh!)))))
