(ns culvert.workflow.reconciliation
  "Pure desired-versus-actual reporting. This namespace performs no actions.")

(defn- resource-map [resources]
  (cond
    (nil? resources) {}
    (map? resources) resources
    (sequential? resources)
    (into {} (map (fn [resource] [(:id resource) resource]) resources))
    :else
    (throw (ex-info "Resources must be a map or sequence"
                    {:type ::invalid-resources :resources resources}))))

(defn- stable-ids [ids]
  (sort-by pr-str ids))

(defn- field-differences [desired actual]
  (into (sorted-map)
        (keep (fn [[field desired-value]]
                (let [actual-value (get actual field)]
                  (when (not= desired-value actual-value)
                    [field {:desired desired-value :actual actual-value}]))))
        desired))

(defn report
  "Return a deterministic reconciliation report. Unknown actual resources are
  retained by default. With :delete-unknown? true they are only reported as
  delete candidates; this pure function never deletes anything."
  ([desired actual]
   (report desired actual {}))
  ([desired actual {:keys [delete-unknown?] :or {delete-unknown? false}}]
   (let [desired (resource-map desired)
         actual (resource-map actual)
         desired-ids (set (keys desired))
         actual-ids (set (keys actual))
         create-ids (stable-ids (remove actual-ids desired-ids))
         shared-ids (stable-ids (filter actual-ids desired-ids))
         unknown-ids (stable-ids (remove desired-ids actual-ids))
         comparisons (mapv (fn [id]
                             [id (field-differences (get desired id) (get actual id))])
                           shared-ids)
         updates (into []
                       (keep (fn [[id differences]]
                               (when (seq differences)
                                 {:id id
                                  :desired (get desired id)
                                  :actual (get actual id)
                                  :differences differences})))
                       comparisons)
         in-sync (into []
                       (keep (fn [[id differences]]
                               (when (empty? differences) id)))
                       comparisons)
         unknown (mapv (fn [id]
                         {:id id
                          :actual (get actual id)
                          :action (if delete-unknown? :delete :retain)})
                       unknown-ids)]
     {:create (mapv (fn [id] {:id id :desired (get desired id)}) create-ids)
      :update updates
      :unknown unknown
      :delete (if delete-unknown? unknown [])
      :in-sync in-sync})))
