(ns culvert.observability.context-test
  (:require [clojure.test :refer [deftest is testing]]
            [culvert.observability.context :as context]))

(deftest request-id-validation-and-generation
  (testing "safe bounded identifiers are accepted"
    (is (context/valid-request-id? "req-123_ABC:node.7"))
    (is (context/valid-request-id? (apply str (repeat context/max-request-id-length "a")))))
  (testing "empty, oversized, non-string, whitespace, controls, and punctuation are rejected"
    (doseq [value [nil "" 42 "has space" "line\nbreak" "路径" "id/part"
                   (apply str (repeat (inc context/max-request-id-length) "a"))]]
      (is (not (context/valid-request-id? value)) (pr-str value))))
  (testing "generated and replacement identifiers are safe"
    (let [generated (context/generate-request-id)]
      (is (context/valid-request-id? generated))
      (is (= "trusted-1" (context/ensure-request-id "trusted-1")))
      (is (context/valid-request-id? (context/ensure-request-id "bad id"))))))

(deftest dynamic-request-context
  (is (nil? (context/request-id)))
  (is (nil? (context/actor)))
  (context/with-request-context {:request-id "request-7" :actor {:id "alice"}}
    (is (= "request-7" (context/request-id)))
    (is (= {:id "alice"} (context/actor))))
  (is (nil? (context/request-id)))
  (is (nil? (context/actor))))

(deftest ring-middleware-binds-and-propagates-context
  (let [seen (atom nil)
        handler (context/wrap-request-context
                 (fn [request]
                   (reset! seen {:dynamic-id (context/request-id)
                                 :dynamic-actor (context/actor)
                                 :request request})
                   {:status 200 :headers {"Content-Type" "text/plain"}}))
        response (handler {:headers {"x-request-id" "client-id"}
                           :identity {:username "alice"}})]
    (is (= "client-id" (get-in response [:headers "X-Request-ID"])))
    (is (= "client-id" (:dynamic-id @seen)))
    (is (= {:username "alice"} (:dynamic-actor @seen)))
    (is (= "client-id" (get-in @seen [:request :request-id]))))
  (testing "unsafe inbound IDs are never reflected"
    (let [response ((context/wrap-request-context (constantly {:status 204}))
                    {:headers {"X-Request-ID" "bad\r\nid"}})
          effective-id (get-in response [:headers "X-Request-ID"])]
      (is (context/valid-request-id? effective-id))
      (is (not= "bad\r\nid" effective-id)))))
