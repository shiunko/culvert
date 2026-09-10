(ns culvert.system-test
  (:require [clojure.test :refer [deftest is testing]]
            [culvert.config :as config]
            [culvert.runtime.instance-lock :as instance-lock]
            [culvert.system :as system]))

(defn- fake-dependencies [events {:keys [start-failure stop-failures stop-fail-once]
                                  :or {stop-failures #{} stop-fail-once #{}}}]
  (let [order [:a :b :c]
        attempts (atom {})]
    {:order order
     :components
     (into {}
           (map (fn [id]
                  [id {:start! (fn []
                                 (swap! events conj [:start id])
                                 (when (= id start-failure)
                                   (throw (ex-info "start failed" {:component id}))))
                       :stop! (fn []
                                (swap! events conj [:stop id])
                                (let [attempt (get (swap! attempts update id (fnil inc 0)) id)]
                                  (when (or (contains? stop-failures id)
                                            (and (contains? stop-fail-once id) (= attempt 1)))
                                    (throw (ex-info "stop failed" {:component id})))))}])
                order))}))

(defn- captured-error [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error error)))

(deftest lifecycle-is-ordered-and-idempotent
  (let [events (atom [])
        sut (system/create (fake-dependencies events {}))]
    (is (identical? sut (system/stop! sut)))
    (is (identical? sut (system/start! sut)))
    (is (identical? sut (system/start! sut)))
    (is (= [[:start :a] [:start :b] [:start :c]] @events))
    (is (= {:phase :running :started [:a :b :c]} @(:state sut)))
    (is (identical? sut (system/stop! sut)))
    (is (= [[:start :a] [:start :b] [:start :c]
            [:stop :c] [:stop :b] [:stop :a]]
           @events))
    (is (= {:phase :stopped :started []} @(:state sut)))
    (is (identical? sut (system/stop! sut)))))

(deftest start-failure-rolls-back-in-reverse-order
  (let [events (atom [])
        sut (system/create (fake-dependencies events {:start-failure :c}))
        error (captured-error #(system/start! sut))]
    (is (= ::system/start-failed (:type (ex-data error))))
    (is (= :c (:component (ex-data error))))
    (is (= [:a :b] (:started (ex-data error))))
    (is (empty? (:rollback-errors (ex-data error))))
    (is (= [[:start :a] [:start :b] [:start :c]
            [:stop :b] [:stop :a]]
           @events))
    (is (= {:phase :stopped :started []} @(:state sut)))))

(deftest stop-errors-are-aggregated-and-retryable
  (let [events (atom [])
        sut (system/create (fake-dependencies events {:stop-fail-once #{:a :b}}))]
    (system/start! sut)
    (reset! events [])
    (let [error (captured-error #(system/stop! sut))]
      (is (= ::system/stop-failed (:type (ex-data error))))
      (is (= [:b :a] (mapv :component (:errors (ex-data error))))))
    (is (= [[:stop :c] [:stop :b] [:stop :a]] @events))
    (is (= {:phase :stop-failed :started [:a :b]} @(:state sut)))
    (is (= ::system/not-stopped
           (:type (ex-data (captured-error #(system/start! sut))))))
    (reset! events [])
    (system/stop! sut)
    (is (= [[:stop :b] [:stop :a]] @events))
    (is (= {:phase :stopped :started []} @(:state sut)))))

(deftest rollback-errors-are-preserved-for-retry
  (let [events (atom [])
        sut (system/create (fake-dependencies events {:start-failure :c
                                                      :stop-failures #{:b}}))
        error (captured-error #(system/start! sut))]
    (is (= [:b] (mapv :component (:rollback-errors (ex-data error)))))
    (is (= {:phase :stop-failed :started [:b]} @(:state sut)))
    (is (= [[:start :a] [:start :b] [:start :c]
            [:stop :b] [:stop :a]]
           @events))))

(deftest dependency-descriptions-are-validated
  (testing "order must be complete and unique"
    (is (= ::system/invalid-dependencies
           (:type (ex-data (captured-error #(system/create {:order [:missing]
                                                            :components {}}))))))
    (is (= ::system/invalid-dependencies
           (:type (ex-data (captured-error #(system/create
                                             {:order [:a :a]
                                              :components {:a {:start! (fn [])
                                                               :stop! (fn [])}}})))))))
  (testing "operations must be functions"
    (is (= ::system/invalid-dependencies
           (:type (ex-data (captured-error #(system/create
                                             {:order [:a]
                                              :components {:a {:start! nil
                                                               :stop! (fn [])}}}))))))))

(deftest production-wiring-is-declarative
  (let [{:keys [order components]} (system/default-dependencies)]
    (is (= (set order) (set (keys components))))
    (is (< (.indexOf order :instance-lock)
           (.indexOf order :sensitive-permissions)
           (.indexOf order :users)))
    (is (every? fn? (map #(get-in components [% :start!]) order)))
    (is (every? fn? (map #(get-in components [% :stop!]) order)))))

(deftest production-permission-gate-runs-after-instance-lock
  (let [events (atom [])]
    (with-redefs [instance-lock/acquire! (fn [_]
                                           (swap! events conj :lock)
                                           :lock-handle)
                  instance-lock/release! (fn [_]
                                           (swap! events conj :unlock))
                  config/validate-sensitive-permissions! (fn [_]
                                                           (swap! events conj :permissions)
                                                           (throw (ex-info "权限错误" {})))]
      (let [sut (system/create (system/default-dependencies))]
        (is (= ::system/start-failed
               (:type (ex-data (captured-error #(system/start! sut))))))
        (is (= [:lock :permissions :unlock] @events))))))
