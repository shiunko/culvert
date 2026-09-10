(ns culvert.observability.event-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [culvert.observability.context :as context]
            [culvert.observability.event :as event]))

(deftest sensitive-key-recognition
  (doseq [key [:password "password_hash" :access-token :clientSecret
               "Authorization" "set-cookie" :auth]]
    (is (event/sensitive-key? key) (str key)))
  (doseq [key [:username :author :authentication-mode :resource]]
    (is (not (event/sensitive-key? key)) (str key))))

(deftest recursive-redaction
  (let [input {:password "one"
               :profile {:name "alice"
                         :api-token "two"
                         :items [{:clientSecret "three"}
                                 {"Cookie" "four"}]}
               :safe #{:visible}}
        output (event/redact input)]
    (is (= "[REDACTED]" (:password output)))
    (is (= "alice" (get-in output [:profile :name])))
    (is (= "[REDACTED]" (get-in output [:profile :api-token])))
    (is (= "[REDACTED]" (get-in output [:profile :items 0 :clientSecret])))
    (is (= "[REDACTED]" (get-in output [:profile :items 1 "Cookie"])))
    (is (= "one" (:password input)) "input remains unchanged")))

(deftest structured-event-uses-bound-context
  (binding [event/*clock* (constantly "2026-09-03T00:00:00Z")]
    (context/with-request-context {:request-id "request-9" :actor "alice"}
      (let [record (event/event "proxy.updated"
                                {:resource "proxy-1"
                                 :details {:token "never-log-me"}})
            decoded (json/parse-string (event/event->json record) true)]
        (is (= {:timestamp "2026-09-03T00:00:00Z"
                :request-id "request-9"
                :actor "alice"
                :resource "proxy-1"
                :details {:token "[REDACTED]"}
                :event "proxy.updated"}
               record))
        (is (= record decoded))
        (is (not (.contains (event/json-event "test" {:password "raw-secret"})
                            "raw-secret")))))))
