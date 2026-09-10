(ns culvert.workflow.saga-test
  (:require [clojure.test :refer [deftest is]]
            [culvert.workflow.saga :as saga]))

(defn- captured-error [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error error)))

(deftest successful-saga-returns-step-results
  (is (= {:status :ok
          :completed [:a :b]
          :results [{:step :a :value 1} {:step :b :value 2}]}
         (saga/execute [{:id :a :do! (fn [] 1) :undo! (fn [])}
                        {:id :b :do! (fn [] 2) :undo! (fn [])}]))))

(deftest failure-compensates-in-reverse-order
  (let [events (atom [])
        result (saga/execute
                [{:id :a
                  :do! #(swap! events conj [:do :a])
                  :undo! #(swap! events conj [:undo :a])}
                 {:id :b
                  :do! #(swap! events conj [:do :b])
                  :undo! #(swap! events conj [:undo :b])}
                 {:id :c
                  :do! (fn []
                         (swap! events conj [:do :c])
                         (throw (ex-info "failed" {:source :c})))
                  :undo! #(swap! events conj [:undo :c])}])]
    (is (= :failed (:status result)))
    (is (= ::saga/execution-failed (:type result)))
    (is (= :c (:failed-step result)))
    (is (= [:a :b] (:completed result)))
    (is (empty? (:compensation-errors result)))
    (is (= [[:do :a] [:do :b] [:do :c] [:undo :b] [:undo :a]] @events))))

(deftest compensation-failures-do-not-stop-later-compensations
  (let [events (atom [])
        error (captured-error
               #(saga/execute!
                 [{:id :a
                   :do! (fn [] :a)
                   :undo! (fn []
                            (swap! events conj :a)
                            (throw (ex-info "undo a" {})))}
                  {:id :b
                   :do! (fn [] :b)
                   :undo! (fn []
                            (swap! events conj :b)
                            (throw (ex-info "undo b" {})))}
                  {:id :c
                   :do! (fn [] (throw (ex-info "do c" {})))
                   :undo! (fn [])}]))
        data (ex-data error)]
    (is (= ::saga/execution-failed (:type data)))
    (is (= [:b :a] @events))
    (is (= [:b :a] (mapv :step (:compensation-errors data))))
    (is (= "do c" (.getMessage (ex-cause error))))))
