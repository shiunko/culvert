(ns culvert.workflow.saga
  "Synchronous saga execution with best-effort reverse compensation.")

(defn- validate-steps! [steps]
  (when-not (sequential? steps)
    (throw (ex-info "Saga steps must be sequential"
                    {:type ::invalid-steps :steps steps})))
  (doseq [[index step] (map-indexed vector steps)]
    (when-not (map? step)
      (throw (ex-info "Saga step must be a map"
                      {:type ::invalid-step :index index :step step})))
    (doseq [operation [:do! :undo!]]
      (when-not (fn? (get step operation))
        (throw (ex-info "Saga operation must be a function"
                        {:type ::invalid-step
                         :index index
                         :step (:id step)
                         :operation operation}))))))

(defn- compensate [completed]
  (reduce (fn [errors {:keys [id undo!]}]
            (try
              (undo!)
              errors
              (catch Throwable error
                (conj errors {:step id :exception error}))))
          []
          (reverse completed)))

(defn execute
  "Execute zero-argument :do! functions in order. On failure, call every
  completed step's zero-argument :undo! in reverse order and return a
  structured result."
  [steps]
  (validate-steps! steps)
  (loop [remaining (seq steps)
         completed []]
    (if-let [{:keys [id do!] :as step} (first remaining)]
      (let [outcome (try
                      {:value (do!)}
                      (catch Throwable error {:error error}))]
        (if-let [error (:error outcome)]
          {:status :failed
           :type ::execution-failed
           :failed-step id
           :completed (mapv :id completed)
           :cause error
           :compensation-errors (compensate completed)}
          (recur (next remaining)
                 (conj completed (assoc step :value (:value outcome))))))
      {:status :ok
       :completed (mapv :id completed)
       :results (mapv (fn [{:keys [id value]}]
                        {:step id :value value})
                      completed)})))

(defn execute!
  "Like execute, but throw ex-info for an execution failure."
  [steps]
  (let [result (execute steps)]
    (if (= :ok (:status result))
      result
      (throw (ex-info "Saga execution failed"
                      (dissoc result :cause)
                      (:cause result))))))
