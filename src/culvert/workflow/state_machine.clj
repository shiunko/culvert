(ns culvert.workflow.state-machine
  "Pure resource lifecycle transition policy.")

(def states
  #{"requested" "provisioning" "running" "stopped" "deleting" "deleted" "error"})

(def transitions
  {"requested" #{"provisioning" "deleting" "error"}
   "provisioning" #{"running" "stopped" "deleting" "error"}
   "running" #{"stopped" "deleting" "error"}
   "stopped" #{"provisioning" "deleting" "error"}
   "deleting" #{"deleted" "error"}
   "deleted" #{}
   "error" #{"provisioning" "stopped" "deleting"}})

(defn- state-name [state]
  (if (keyword? state) (name state) state))

(defn valid-state? [state]
  (contains? states (state-name state)))

(defn allowed-transition? [from to]
  (let [from (state-name from)
        to (state-name to)]
    (and (valid-state? from)
         (valid-state? to)
         (contains? (get transitions from) to))))

(defn transition-state
  "Return the canonical target state string, or throw structured ex-info."
  [from to]
  (let [from (state-name from)
        to (state-name to)]
    (when-not (valid-state? from)
      (throw (ex-info "Unknown resource state"
                      {:type ::invalid-state
                       :role :from
                       :state from
                       :states states})))
    (when-not (valid-state? to)
      (throw (ex-info "Unknown resource state"
                      {:type ::invalid-state
                       :role :to
                       :state to
                       :states states})))
    (when-not (allowed-transition? from to)
      (throw (ex-info "Illegal resource state transition"
                      {:type ::invalid-transition
                       :from from
                       :to to
                       :allowed (get transitions from)})))
    to))

(defn transition
  "Purely transition a resource map's :state."
  [resource to]
  (assoc resource :state (transition-state (:state resource) to)))
