(ns culvert.observability.activity-test
  "活动记录环形缓冲：脱敏、顺序、容量与边界。"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [culvert.observability.activity :as activity]))

(use-fixtures :each
  (fn [f]
    (activity/clear!)
    (f)
    (activity/clear!)))

(deftest record-shapes-and-redacts
  (let [e (activity/record! {:actor "alice"
                             :action :demo
                             :resource {:kind "forward" :id "8080/tcp"}
                             :outcome :success
                             :severity :warning
                             :details {:password "x" :token "y" :note "ok"}})]
    (is (string? (:id e)))
    (is (= "alice" (:actor e)))
    (is (= :warning (:severity e)))
    (is (string? (:timestamp e)))
    (is (= "[REDACTED]" (get-in e [:details :password])))
    (is (= "[REDACTED]" (get-in e [:details :token])))
    (is (= "ok" (get-in e [:details :note])))))

(deftest severity-normalization
  (is (= :info (:severity (activity/record! {:severity :bogus}))))
  (is (= :error (:severity (activity/record! {:severity :error})))))

(deftest recent-order-and-boundary
  (dotimes [i 5]
    (activity/record! {:action :demo :summary (str i)}))
  (testing "最新在前"
    (is (= ["4" "3" "2"] (map :summary (activity/recent 3)))))
  (testing "n 边界"
    (is (= 5 (count (activity/recent))))
    (is (empty? (activity/recent 0))))
  (testing "n 超过容量时按容量截断"
    (is (= 5 (count (activity/recent 999))))))

(deftest buffer-capacity-is-bounded
  (dotimes [i (+ activity/capacity 20)]
    (activity/record! {:action :demo :summary (str i)}))
  (is (= activity/capacity (count (activity/recent))))
  (is (= (str (+ activity/capacity 19)) (:summary (first (activity/recent 1))))))
