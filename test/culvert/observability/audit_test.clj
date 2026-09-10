(ns culvert.observability.audit-test
  (:require [clojure.test :refer [deftest is]]
            [culvert.observability.audit :as audit]
            [culvert.observability.context :as context]
            [culvert.observability.event :as event]))

(deftest audit-events-have-standard-shape-and-no-secrets
  (binding [event/*clock* (constantly "2026-09-03T01:02:03Z")]
    (context/with-request-context {:request-id "audit-request" :actor "admin"}
      (let [record (audit/audit-event :proxy/update
                                      {:type :proxy :id "proxy-1"}
                                      :success
                                      {:metadata {:password "raw"
                                                  :authorization "Bearer hidden"}})]
        (is (= audit/required-fields
               (set (filter audit/required-fields (keys record)))))
        (is (= "audit" (:event record)))
        (is (= :proxy/update (:action record)))
        (is (= "admin" (:actor record)))
        (is (= "audit-request" (:request-id record)))
        (is (= "2026-09-03T01:02:03Z" (:timestamp record)))
        (is (= "[REDACTED]" (get-in record [:metadata :password])))
        (is (= "[REDACTED]" (get-in record [:metadata :authorization])))))))

(deftest append-function-receives-safe-event
  (let [records (atom [])
        sink (audit/atom-sink records)]
    (context/with-request-context {:request-id "request-append" :actor {:id "operator"}}
      (let [record (audit/append! sink :container/delete "container-1" :denied
                                  {:token "must-not-escape"})]
        (is (= record (first @records)))
        (is (= "[REDACTED]" (:token record)))
        (is (= :container/delete (:action record)))
        (is (= :denied (:outcome record)))))))
