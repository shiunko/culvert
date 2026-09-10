(ns culvert.authorization-test
  (:require [clojure.test :refer [deftest is testing]]
            [culvert.authorization :as authorization]))

(deftest canonical-actor-validation
  (testing "canonical actors are accepted"
    (is (= {:username "alice" :admin? false}
           (authorization/require-actor! {:username "alice" :admin? false})))
    (is (= {:username "root" :admin? true}
           (authorization/actor "root" true))))

  (testing "anonymous or malformed actors are rejected"
    (doseq [actor [nil {} {:username "" :admin? false}
                   {:username "alice"} {:username "alice" :admin? "false"}]]
      (is (thrown? Exception (authorization/require-actor! actor))))))

(deftest resource-authorization
  (let [alice {:username "alice" :admin? false}
        admin {:username "root" :admin? true}]
    (is (authorization/authorized? alice "alice" "legacy"))
    (is (not (authorization/authorized? alice "bob" "legacy")))
    (is (not (authorization/authorized? alice "legacy" "legacy")))
    (is (not (authorization/authorized? alice nil "legacy")))
    (is (authorization/authorized? admin "bob" "legacy"))
    (is (authorization/authorized? admin "legacy" "legacy"))))
