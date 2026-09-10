(ns culvert.observability.context
  "Request-scoped correlation and actor context for JVM Clojure and Babashka."
  (:require [clojure.string :as str]))

(def request-id-header "X-Request-ID")
(def max-request-id-length 128)

(def ^:dynamic *request-id* nil)
(def ^:dynamic *actor* nil)

(def ^:private safe-request-id-pattern
  (re-pattern (str "^[A-Za-z0-9._:-]{1," max-request-id-length "}$")))

(defn valid-request-id?
  "True when value is a bounded request ID containing only safe ASCII characters."
  [value]
  (and (string? value)
       (boolean (re-matches safe-request-id-pattern value))))

(defn generate-request-id
  "Generates a request ID valid under `valid-request-id?`."
  []
  (str (java.util.UUID/randomUUID)))

(defn ensure-request-id
  "Returns candidate when valid, otherwise generates a fresh request ID."
  [candidate]
  (if (valid-request-id? candidate)
    candidate
    (generate-request-id)))

(defn request-id [] *request-id*)
(defn actor [] *actor*)

(defmacro with-request-context
  "Dynamically binds request ID and actor for body. Invalid IDs are replaced."
  [context & body]
  `(let [context# ~context]
     (binding [*request-id* (ensure-request-id (:request-id context#))
               *actor* (:actor context#)]
       ~@body)))

(defn request-header
  "Reads a Ring request header without assuming header-name casing."
  [request header-name]
  (let [headers (:headers request)
        lower-name (str/lower-case header-name)]
    (or (get headers header-name)
        (get headers lower-name)
        (some (fn [[key value]]
                (when (= lower-name (str/lower-case (name key))) value))
              headers))))

(defn wrap-request-context
  "Ring middleware that binds request context and returns the effective request ID.

  `actor-fn` receives the request; by default it uses `:actor`, then `:identity`."
  ([handler]
   (wrap-request-context handler #(or (:actor %) (:identity %))))
  ([handler actor-fn]
   (fn [request]
     (let [request-id (ensure-request-id (request-header request request-id-header))
           actor (actor-fn request)]
       (binding [*request-id* request-id
                 *actor* actor]
         (let [response (handler (assoc request
                                        :request-id request-id
                                        :actor actor))]
           (assoc-in response [:headers request-id-header] request-id)))))))
