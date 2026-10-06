(ns culvert.web-ws-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [culvert.web.sse :as sse]))

(use-fixtures :each
  (fn [test-fn]
    (sse/stop-stats-broadcaster!)
    (try
      (test-fn)
      (finally
        (sse/stop-stats-broadcaster!)))))

(deftest stats-broadcaster-has-an-explicit-restartable-lifecycle
  (is (false? (sse/stats-broadcaster-running?)))
  (let [first-component (sse/start-stats-broadcaster!)]
    (is (sse/stats-broadcaster-running?))
    (is (identical? first-component (sse/start-stats-broadcaster!)))
    (sse/stop-stats-broadcaster!)
    (is (false? (sse/stats-broadcaster-running?)))
    (let [second-component (sse/start-stats-broadcaster!)]
      (is (sse/stats-broadcaster-running?))
      (is (not (identical? first-component second-component)))))
  (sse/stop-stats-broadcaster!)
  (is (false? (sse/stats-broadcaster-running?))))
