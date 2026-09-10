(ns culvert.health-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [culvert.health :as health]
            [culvert.web.handlers :as handlers]))

(use-fixtures :each
  (fn [test-fn]
    (health/mark-not-ready! "starting")
    (try (test-fn)
         (finally (health/mark-not-ready! "starting")))))

(deftest liveness-is-independent-of-readiness
  (is (= 200 (:status (health/live-response))))
  (is (= {:status "live"} (json/parse-string (:body (health/live-response)) true)))
  (is (= 503 (:status (health/ready-response))))
  (health/mark-ready!)
  (is (= 200 (:status (health/ready-response))))
  (health/mark-not-ready! "stopping")
  (is (= {:status "stopping"}
         (json/parse-string (:body (health/ready-response)) true))))

(deftest health-routes-do-not-require-authentication
  (is (= 200 (:status (handlers/route-dispatcher {:request-method :get :uri "/health"}))))
  (is (= 200 (:status (handlers/route-dispatcher {:request-method :get :uri "/health/live"}))))
  (is (= 503 (:status (handlers/route-dispatcher {:request-method :get :uri "/health/ready"})))))
