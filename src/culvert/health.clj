(ns culvert.health
  "Process liveness and application readiness state exposed to HTTP handlers."
  (:require [cheshire.core :as json]))

(defonce readiness (atom {:ready? false :status "starting"}))

(defn mark-ready! []
  (reset! readiness {:ready? true :status "ready"}))

(defn mark-not-ready!
  ([] (mark-not-ready! "stopping"))
  ([status]
   (reset! readiness {:ready? false :status status})))

(defn live-response []
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string {:status "live"})})

(defn ready-response []
  (let [{:keys [ready? status]} @readiness]
    {:status (if ready? 200 503)
     :headers {"Content-Type" "application/json"}
     :body (json/generate-string {:status status})}))
