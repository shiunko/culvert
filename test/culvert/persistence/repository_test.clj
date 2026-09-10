(ns culvert.persistence.repository-test
  (:require [clojure.test :refer [deftest is]]
            [culvert.persistence.repository :as repository]))

(def sample-resource
  {:id "container:demo"
   :owner "alice"
   :resource-type "container"
   :resource-key "demo"
   :state "running"
   :data {:image "alpine"}})

(deftest memory-resource-crud
  (let [repo (repository/memory-repository)]
    (is (= sample-resource (repository/create-resource! repo sample-resource)))
    (is (= sample-resource (repository/get-resource repo (:id sample-resource))))
    (is (= [sample-resource] (repository/list-resources repo {:owner "alice"})))
    (is (= "stopping" (:state (repository/update-resource! repo (:id sample-resource)
                                                           {:state "stopping"}))))
    (is (true? (repository/delete-resource! repo (:id sample-resource))))
    (is (nil? (repository/get-resource repo (:id sample-resource))))))

(deftest memory-resource-unique-key
  (let [repo (repository/memory-repository)]
    (repository/create-resource! repo sample-resource)
    (is (thrown? Exception
                 (repository/create-resource! repo
                                              (assoc sample-resource :id "container:other"))))))
