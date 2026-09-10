(ns culvert.persistence.importer
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [culvert.persistence.repository :as repository]
            [culvert.persistence.sqlite :as sqlite])
  (:import [java.time Instant]))

(defn- fail! [message data]
  (throw (ex-info message (assoc data :type ::invalid-import))))

(defn read-json-strict [path]
  (let [file (io/file path)]
    (when-not (.isFile file)
      (fail! "JSON import source does not exist" {:path (str path)}))
    (try
      (json/parse-string-strict (slurp file) true)
      (catch Exception exception
        (throw (ex-info "JSON import source is malformed"
                        {:type ::malformed-json :path (str path)}
                        exception))))))

(defn- nonblank! [value field context]
  (when-not (and (string? value) (not (str/blank? value)))
    (fail! (str "Missing or blank " (name field)) (assoc context :field field)))
  value)

(defn- entries! [value field path]
  (when-not (map? value)
    (fail! (str "Expected object at " (name field)) {:path path :field field}))
  value)

(defn- owner! [entry context]
  (nonblank! (:userId entry) :owner context))

(defn- normalize-key [value]
  (if (keyword? value) (name value) (str value)))

(defn- user-rows [path data]
  (for [[username user] (entries! (:users data) :users path)]
    (let [username (nonblank! (normalize-key username) :username {:path path})]
      {:username username
       :password-hash (nonblank! (:password user) :password {:path path :username username})
       :role (or (:role user) "user")
       :auth-version (or (:authVersion user) 1)
       :quotas (or (:quotas user) {})
       :created-at (or (:createdAt user) (str Instant/EPOCH))})))

(defn- resource-rows [path resource-type entries]
  (for [[key entry] (entries! entries resource-type path)]
    (let [resource-key (normalize-key key)
          owner (owner! entry {:path path :resource-type resource-type :resource-key resource-key})]
      {:id (str (name resource-type) ":" resource-key)
       :owner owner
       :resource-type (name resource-type)
       :resource-key resource-key
       :state (or (:state entry) "running")
       :data entry})))

(defn- proxy-rows [path entries]
  (for [[key entry] (entries! entries :proxy-rules path)]
    (let [key (normalize-key key)
          parts (str/split key #"@@" -1)]
      (when-not (= 2 (count parts))
        (fail! "Proxy key must be domain@@worker" {:path path :key key}))
      (let [[domain worker] parts]
        {:id (str "proxy:" key)
         :owner (owner! entry {:path path :key key})
         :domain (nonblank! domain :domain {:path path :key key})
         :worker (nonblank! worker :worker {:path path :key key})
         :target (nonblank! (:target entry) :target {:path path :key key})
         :enabled (if (false? (:enabled entry)) 0 1)
         :data entry}))))

(defn- duplicate-values [values]
  (->> values frequencies (keep (fn [[value n]] (when (> n 1) value))) vec))

(defn build-plan [{:keys [users resources proxies]}]
  (let [users-data (read-json-strict users)
        user-rows (vec (user-rows users users-data))
        resource-rows (vec (mapcat (fn [[resource-type source]]
                                     (let [{:keys [path root-key]}
                                           (if (map? source) source {:path source})
                                           data (read-json-strict path)
                                           entries (if root-key (get data root-key) data)]
                                       (resource-rows path resource-type entries)))
                                   resources))
        proxy-rows (if proxies (vec (proxy-rows proxies (read-json-strict proxies))) [])
        canonical-usernames (map (comp str/lower-case :username) user-rows)
        usernames (set canonical-usernames)
        owners (concat (map :owner resource-rows) (map :owner proxy-rows))
        missing-owners (->> owners
                            (remove #(contains? usernames (str/lower-case %)))
                            distinct sort vec)
        duplicate-users (duplicate-values canonical-usernames)
        duplicate-resource-keys (duplicate-values (map (juxt :resource-type :resource-key) resource-rows))
        duplicate-proxy-keys (duplicate-values
                              (map (fn [{:keys [domain worker]}]
                                     [(str/lower-case domain) (str/lower-case worker)])
                                   proxy-rows))]
    (when (seq duplicate-users)
      (fail! "Import contains duplicate usernames" {:usernames duplicate-users}))
    (when (seq missing-owners)
      (fail! "Import contains owners absent from users" {:owners missing-owners}))
    (when (seq duplicate-resource-keys)
      (fail! "Import contains duplicate resource keys" {:keys duplicate-resource-keys}))
    (when (seq duplicate-proxy-keys)
      (fail! "Import contains duplicate proxy keys" {:keys duplicate-proxy-keys}))
    {:users user-rows
     :resources resource-rows
     :proxy-rules proxy-rows
     :counts {:users (count user-rows)
              :resources (count resource-rows)
              :proxy-rules (count proxy-rows)}}))

(defn- insert-user! [transaction user]
  (jdbc/execute-one! transaction
                     ["INSERT INTO users(username, password_hash, role, auth_version, quotas_json, created_at) VALUES (?, ?, ?, ?, ?, ?)"
                      (:username user) (:password-hash user) (:role user) (:auth-version user)
                      (json/generate-string (:quotas user)) (:created-at user)]))

(defn- insert-proxy! [transaction proxy]
  (let [now (str (Instant/now))]
    (jdbc/execute-one! transaction
                       ["INSERT INTO proxy_rules(id, owner, domain, worker, target, enabled, data_json, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        (:id proxy) (:owner proxy) (:domain proxy) (:worker proxy) (:target proxy)
                        (:enabled proxy) (json/generate-string (:data proxy)) now now])))

(defn- table-count [transaction table]
  (-> (jdbc/execute-one! transaction [(str "SELECT count(*) AS row_count FROM " table)])
      first val))

(defn- verify-empty! [transaction]
  (doseq [table ["users" "resources" "proxy_rules"]]
    (when-not (zero? (table-count transaction table))
      (fail! "SQLite import target must be empty" {:table table}))))

(defn import! [connectable sources {:keys [dry-run?] :or {dry-run? false}}]
  (let [plan (build-plan sources)]
    (if dry-run?
      (assoc plan :dry-run? true)
      (do
        (sqlite/migrate! connectable)
        (sqlite/with-transaction connectable
          (fn [transaction]
            (verify-empty! transaction)
            (doseq [user (:users plan)] (insert-user! transaction user))
            (let [resource-repository (sqlite/sqlite-repository transaction)]
              (doseq [resource (:resources plan)]
                (repository/create-resource! resource-repository resource)))
            (doseq [proxy (:proxy-rules plan)] (insert-proxy! transaction proxy))
            (doseq [[table expected] [["users" (get-in plan [:counts :users])]
                                      ["resources" (get-in plan [:counts :resources])]
                                      ["proxy_rules" (get-in plan [:counts :proxy-rules])]]]
              (when-not (= expected (table-count transaction table))
                (fail! "SQLite import count verification failed"
                       {:table table :expected expected
                        :actual (table-count transaction table)})))))
        (assoc (:counts plan) :dry-run? false)))))
