(ns culvert.workflow.reconciliation-test
  (:require [clojure.test :refer [deftest is testing]]
            [culvert.workflow.reconciliation :as reconciliation]))

(deftest report-is-deterministic-and-idempotent
  (let [desired {"b" {:id "b" :state "running"}
                 "a" {:id "a" :state "stopped"}}
        actual {"a" {:id "a" :state "running" :runtime-detail :ignored}}
        first-report (reconciliation/report desired actual)
        second-report (reconciliation/report desired actual)]
    (is (= first-report second-report))
    (is (= [{:id "b" :desired {:id "b" :state "running"}}]
           (:create first-report)))
    (is (= ["a"] (mapv :id (:update first-report))))
    (is (= {:desired "stopped" :actual "running"}
           (get-in first-report [:update 0 :differences :state])))
    (is (empty? (:delete first-report)))
    (is (= {:create [] :update [] :unknown [] :delete [] :in-sync ["a" "b"]}
           (reconciliation/report desired desired)))))

(deftest unknown-resources-are-never-deleted-by-default
  (let [desired [{:id "known" :state "running"}]
        actual [{:id "unknown" :state "running"}
                {:id "known" :state "running"}]
        report (reconciliation/report desired actual)]
    (is (empty? (:delete report)))
    (is (= [{:id "unknown"
             :actual {:id "unknown" :state "running"}
             :action :retain}]
           (:unknown report))))
  (testing "deletion is an explicit report-only opt-in"
    (let [report (reconciliation/report {} {"unknown" {:id "unknown"}}
                                        {:delete-unknown? true})]
      (is (= :delete (get-in report [:unknown 0 :action])))
      (is (= (:unknown report) (:delete report))))))
