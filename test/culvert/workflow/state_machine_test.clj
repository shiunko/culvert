(ns culvert.workflow.state-machine-test
  (:require [clojure.test :refer [deftest is testing]]
            [culvert.workflow.state-machine :as state-machine]))

(defn- captured-error [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error error)))

(deftest legal-transitions-are-pure
  (let [resource {:id "resource-1" :state "requested"}
        transitioned (state-machine/transition resource :provisioning)]
    (is (= {:id "resource-1" :state "requested"} resource))
    (is (= {:id "resource-1" :state "provisioning"} transitioned))
    (is (state-machine/allowed-transition? "running" "stopped"))
    (is (= "deleted" (state-machine/transition-state :deleting :deleted)))))

(deftest illegal-transitions-have-structured-errors
  (testing "terminal resources cannot be restarted"
    (let [error (captured-error #(state-machine/transition-state "deleted" "running"))]
      (is (= ::state-machine/invalid-transition (:type (ex-data error))))
      (is (= {:from "deleted" :to "running" :allowed #{}}
             (select-keys (ex-data error) [:from :to :allowed])))))
  (testing "unknown states are distinguished"
    (let [error (captured-error #(state-machine/transition-state "missing" "running"))]
      (is (= ::state-machine/invalid-state (:type (ex-data error))))
      (is (= :from (:role (ex-data error)))))))
