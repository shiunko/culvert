(ns culvert.web-ws-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [culvert.web.ws :as ws]))

(use-fixtures :each
  (fn [test-fn]
    (ws/stop-stats-broadcaster!)
    (try
      (test-fn)
      (finally
        (ws/stop-stats-broadcaster!)))))

(deftest stats-broadcaster-has-an-explicit-restartable-lifecycle
  (is (false? (ws/stats-broadcaster-running?)))
  (let [first-component (ws/start-stats-broadcaster!)]
    (is (ws/stats-broadcaster-running?))
    (is (identical? first-component (ws/start-stats-broadcaster!)))
    (ws/stop-stats-broadcaster!)
    (is (false? (ws/stats-broadcaster-running?)))
    (let [second-component (ws/start-stats-broadcaster!)]
      (is (ws/stats-broadcaster-running?))
      (is (not (identical? first-component second-component)))))
  (ws/stop-stats-broadcaster!)
  (is (false? (ws/stats-broadcaster-running?))))
