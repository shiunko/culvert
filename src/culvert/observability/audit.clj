(ns culvert.observability.audit
  "Secret-safe audit event construction and delivery to an append function."
  (:require [culvert.observability.event :as event]))

(def required-fields
  #{:action :actor :resource :outcome :request-id :timestamp})

(defn audit-event
  "Builds the standard audit record. Extra fields are accepted and recursively redacted."
  ([action resource outcome]
   (audit-event action resource outcome {}))
  ([action resource outcome fields]
   (event/event "audit"
                (merge {:action action
                        :resource resource
                        :outcome outcome}
                       fields))))

(defn append!
  "Builds an audit event and passes it to sink, a one-argument append function.
  Returns the event after the sink completes."
  ([sink action resource outcome]
   (append! sink action resource outcome {}))
  ([sink action resource outcome fields]
   (let [record (audit-event action resource outcome fields)]
     (sink record)
     record)))

(defn atom-sink
  "Returns an append function that stores audit events in the supplied atom."
  [store]
  (fn [record]
    (swap! store conj (event/redact record))))
