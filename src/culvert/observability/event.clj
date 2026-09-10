(ns culvert.observability.event
  "Construction and JSON encoding of structured, secret-safe events."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [culvert.observability.context :as context]))

(def redacted-value "[REDACTED]")

(def ^:dynamic *clock*
  #(str (java.time.Instant/now)))

(def ^:private sensitive-key-pattern
  #"(^|[^a-z])(password|token|secret|auth|authorization|cookie)([^a-z]|$)")

(defn- key-text [key]
  (cond
    (keyword? key) (name key)
    (symbol? key) (name key)
    (string? key) key
    :else (str key)))

(defn sensitive-key?
  "True for field names representing passwords, tokens, secrets, auth, or cookies."
  [key]
  (let [text (-> (key-text key)
                 (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
                 str/lower-case)]
    (boolean (re-find sensitive-key-pattern text))))

(defn redact
  "Recursively replaces values under sensitive keys without mutating input."
  [value]
  (cond
    (map? value)
    (into (empty value)
          (map (fn [[key nested]]
                 [key (if (sensitive-key? key)
                        redacted-value
                        (redact nested))]))
          value)

    (vector? value) (mapv redact value)
    (set? value) (set (map redact value))
    (sequential? value) (doall (map redact value))
    :else value))

(defn event
  "Builds a structured event with timestamp and current request context.
  Caller fields may override defaults except that the final value is always redacted."
  ([fields]
   (event nil fields))
  ([event-name fields]
   (redact
    (cond-> (merge {:timestamp (*clock*)
                    :request-id (context/request-id)
                    :actor (context/actor)}
                   fields)
      event-name (assoc :event event-name)))))

(defn event->json
  "Encodes an event as one compact JSON object after recursive redaction."
  [event]
  (json/generate-string (redact event)))

(defn json-event
  "Builds and JSON-encodes a structured event."
  ([fields]
   (event->json (event fields)))
  ([event-name fields]
   (event->json (event event-name fields))))
