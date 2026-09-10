(ns culvert.system
  "显式且可重启的应用组件生命周期协调器。"
  (:require [culvert.auth :as auth]
            [culvert.caddy :as caddy]
            [culvert.docker :as docker]
            [culvert.forward :as forward]
            [culvert.health :as health]
            [culvert.config :as config]
            [culvert.runtime.instance-lock :as instance-lock]
            [culvert.smolvm.manager :as smolvm]
            [culvert.web.core :as web-core]
            [culvert.web.server :as web-server]
            [culvert.web.ws :as ws]))

(defn default-dependencies []
  (let [lock-handle (atom nil)]
    {:order [:instance-lock
             :sensitive-permissions
             :users
             :caddy
             :forwards
             :docker
             :smolvm
             :token-cleaner
             :web-cleaner
             :web-server
             :stats-broadcaster]
     :components
     {:instance-lock {:start! #(reset! lock-handle (instance-lock/acquire! (:data-dir config/config)))
                      :stop! #(when-let [handle @lock-handle]
                                (instance-lock/release! handle)
                                (reset! lock-handle nil))}
      :sensitive-permissions {:start! #(config/validate-sensitive-permissions! config/config)
                              :stop! (fn [] nil)}
      :users {:start! auth/bootstrap-admin! :stop! (fn [] nil)}
      :caddy {:start! caddy/sync-caddy! :stop! caddy/shutdown!}
      :forwards {:start! forward/sync-forwards! :stop! forward/shutdown!}
      :docker {:start! docker/sync-containers! :stop! docker/shutdown!}
      :smolvm {:start! smolvm/sync-machines! :stop! smolvm/shutdown!}
      :token-cleaner {:start! auth/start-token-cleaner! :stop! auth/stop-token-cleaner!}
      :web-cleaner {:start! web-core/start-cleaner! :stop! web-core/stop-cleaner!}
      :web-server {:start! web-server/start-server! :stop! web-server/stop-server!}
      :stats-broadcaster {:start! ws/start-stats-broadcaster! :stop! ws/stop-stats-broadcaster!}}}))

(defn- invalid-dependencies! [message data]
  (throw (ex-info message (assoc data :type ::invalid-dependencies))))

(defn create [{:keys [order components] :as dependencies}]
  (when-not (vector? order)
    (invalid-dependencies! "System component order must be a vector" {:order order}))
  (when-not (= (count order) (count (distinct order)))
    (invalid-dependencies! "System component order contains duplicates" {:order order}))
  (doseq [id order]
    (let [component (get components id)]
      (when-not component
        (invalid-dependencies! "System component is missing" {:component id}))
      (doseq [operation [:start! :stop!]]
        (when-not (fn? (get component operation))
          (invalid-dependencies! "System component operation must be a function"
                                 {:component id :operation operation})))))
  {:dependencies dependencies
   :state (atom {:phase :stopped :started []})})

(defn- stop-components [components started]
  (reduce (fn [{:keys [failed errors]} id]
            (try
              ((get-in components [id :stop!]))
              {:failed failed :errors errors}
              (catch Throwable error
                {:failed (conj failed id)
                 :errors (conj errors {:component id :exception error})})))
          {:failed [] :errors []}
          (reverse started)))

(defn start! [{:keys [dependencies state] :as system}]
  (locking state
    (let [{:keys [phase started]} @state]
      (cond
        (= phase :running) system
        (not= phase :stopped)
        (throw (ex-info "System is not fully stopped"
                        {:type ::not-stopped :phase phase :started started}))
        :else
        (let [{:keys [order components]} dependencies]
          (loop [remaining order
                 started []]
            (if-let [id (first remaining)]
              (let [start-error (try
                                  ((get-in components [id :start!]))
                                  nil
                                  (catch Throwable error error))]
                (if start-error
                  (let [_ (health/mark-not-ready! "start-failed")
                        {:keys [failed errors]} (stop-components components started)]
                    (reset! state {:phase (if (seq failed) :stop-failed :stopped)
                                   :started (vec (reverse failed))})
                    (throw (ex-info "System start failed"
                                    {:type ::start-failed
                                     :component id
                                     :started started
                                     :rollback-errors errors}
                                    start-error)))
                  (let [started (conj started id)]
                    (reset! state {:phase :starting :started started})
                    (recur (next remaining) started))))
              (do
                (reset! state {:phase :running :started started})
                (health/mark-ready!)
                system))))))))

(defn stop! [{:keys [dependencies state] :as system}]
  (locking state
    (health/mark-not-ready!)
    (let [{:keys [started]} @state]
      (if (empty? started)
        (do
          (reset! state {:phase :stopped :started []})
          system)
        (let [{:keys [failed errors]} (stop-components (:components dependencies) started)]
          (reset! state {:phase (if (seq failed) :stop-failed :stopped)
                         :started (vec (reverse failed))})
          (if (seq errors)
            (throw (ex-info "System stop failed"
                            {:type ::stop-failed :errors errors}))
            system))))))
