(ns culvert.persistence.repository)

(defprotocol ResourceRepository
  (create-resource! [repository resource])
  (get-resource [repository resource-id])
  (list-resources [repository filters])
  (update-resource! [repository resource-id changes])
  (delete-resource! [repository resource-id]))

(defn- require-value [resource key]
  (let [value (get resource key)]
    (when (or (nil? value) (and (string? value) (empty? value)))
      (throw (ex-info (str "Resource requires " key)
                      {:type ::invalid-resource :key key})))
    value))

(defn validate-resource [resource]
  (doseq [key [:id :owner :resource-type :resource-key]]
    (require-value resource key))
  (assoc resource :state (or (:state resource) "requested")))

(defrecord MemoryResourceRepository [state]
  ResourceRepository
  (create-resource! [_ resource]
    (let [resource (validate-resource resource)
          id (:id resource)
          unique-key [(:resource-type resource) (:resource-key resource)]]
      (locking state
        (when (contains? (:resources @state) id)
          (throw (ex-info "Resource id already exists" {:type ::unique-violation :id id})))
        (when (contains? (:unique-keys @state) unique-key)
          (throw (ex-info "Resource key already exists" {:type ::unique-violation :key unique-key})))
        (swap! state (fn [current]
                       (-> current
                           (assoc-in [:resources id] resource)
                           (assoc-in [:unique-keys unique-key] id))))
        resource)))
  (get-resource [_ resource-id]
    (get-in @state [:resources resource-id]))
  (list-resources [_ {:keys [owner resource-type]}]
    (->> (vals (:resources @state))
         (filter #(and (or (nil? owner) (= owner (:owner %)))
                       (or (nil? resource-type) (= resource-type (:resource-type %)))))
         (sort-by :id)
         vec))
  (update-resource! [_ resource-id changes]
    (locking state
      (let [current (get-in @state [:resources resource-id])]
        (when-not current
          (throw (ex-info "Resource not found" {:type ::not-found :id resource-id})))
        (when (some #(contains? changes %) [:id :owner :resource-type :resource-key])
          (throw (ex-info "Resource identity fields cannot be changed"
                          {:type ::immutable-fields :id resource-id})))
        (let [updated (merge current changes)]
          (swap! state assoc-in [:resources resource-id] updated)
          updated))))
  (delete-resource! [_ resource-id]
    (locking state
      (when-let [current (get-in @state [:resources resource-id])]
        (swap! state (fn [value]
                       (-> value
                           (update :resources dissoc resource-id)
                           (update :unique-keys dissoc [(:resource-type current)
                                                        (:resource-key current)]))))
        true))))

(defn memory-repository []
  (->MemoryResourceRepository (atom {:resources {} :unique-keys {}})))
