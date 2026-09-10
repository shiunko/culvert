(ns culvert.persistence.sqlite
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [culvert.persistence.repository :as repository]
            [culvert.persistence.schema :as schema])
  (:import [java.sql Connection]
           [java.time Instant]
           [org.sqlite SQLiteConfig SQLiteDataSource]))

(def ^:private query-options {:builder-fn rs/as-unqualified-lower-maps})

(defn datasource [database]
  (let [path (.getAbsolutePath (io/file database))
        config (doto (SQLiteConfig.)
                 (.enforceForeignKeys true)
                 (.setBusyTimeout 5000))]
    (io/make-parents path)
    (doto (SQLiteDataSource. config)
      (.setUrl (str "jdbc:sqlite:" path)))))

(defn open-connection [connectable]
  (doto (jdbc/get-connection connectable)
    (.setAutoCommit true)))

(defn close-connection! [connection]
  (when connection (.close ^Connection connection)))

(defmacro with-connection [[binding connectable] & body]
  `(with-open [~binding (open-connection ~connectable)]
     ~@body))

(defn configure-connection! [connection]
  (jdbc/execute! connection ["PRAGMA foreign_keys = ON"])
  (jdbc/execute! connection ["PRAGMA busy_timeout = 5000"])
  connection)

(defn with-transaction [connectable f]
  (jdbc/with-transaction [transaction connectable]
    (configure-connection! transaction)
    (f transaction)))

(defn- ensure-migrations-table! [connection]
  (jdbc/execute! connection
                 ["CREATE TABLE IF NOT EXISTS migrations (version INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, applied_at TEXT NOT NULL)"]))

(defn applied-migrations [connectable]
  (with-open [connection (open-connection connectable)]
    (ensure-migrations-table! connection)
    (mapv :version
          (jdbc/execute! connection
                         ["SELECT version FROM migrations ORDER BY version"]
                         query-options))))

(defn migrate! [connectable]
  (with-transaction connectable
    (fn [transaction]
      (ensure-migrations-table! transaction)
      (let [applied (set (map :version
                              (jdbc/execute! transaction
                                             ["SELECT version FROM migrations"]
                                             query-options)))]
        (doseq [{:keys [version name statements]} schema/migrations
                :when (not (contains? applied version))]
          (doseq [statement statements]
            (jdbc/execute! transaction [statement]))
          (jdbc/execute-one! transaction
                             ["INSERT INTO migrations(version, name, applied_at) VALUES (?, ?, ?)"
                              version name (str (Instant/now))]))
        (mapv :version
              (jdbc/execute! transaction
                             ["SELECT version FROM migrations ORDER BY version"]
                             query-options))))))

(defn- encode-json [value]
  (json/generate-string (or value {})))

(defn- decode-resource [row]
  (when row
    {:id (:id row)
     :owner (:owner row)
     :resource-type (:resource_type row)
     :resource-key (:resource_key row)
     :state (:state row)
     :data (json/parse-string (:data_json row) true)
     :created-at (:created_at row)
     :updated-at (:updated_at row)}))

(defrecord SQLiteResourceRepository [connectable]
  repository/ResourceRepository
  (create-resource! [_ resource]
    (let [resource (repository/validate-resource resource)
          now (str (Instant/now))
          created-at (or (:created-at resource) now)
          updated-at (or (:updated-at resource) created-at)]
      (jdbc/execute-one! connectable
                         ["INSERT INTO resources(id, owner, resource_type, resource_key, state, data_json, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                          (:id resource) (:owner resource) (:resource-type resource)
                          (:resource-key resource) (:state resource)
                          (encode-json (:data resource)) created-at updated-at])
      (decode-resource
       (jdbc/execute-one! connectable
                          ["SELECT * FROM resources WHERE id = ?" (:id resource)]
                          query-options))))
  (get-resource [_ resource-id]
    (decode-resource
     (jdbc/execute-one! connectable
                        ["SELECT * FROM resources WHERE id = ?" resource-id]
                        query-options)))
  (list-resources [_ {:keys [owner resource-type]}]
    (let [[sql & params]
          (cond
            (and owner resource-type)
            ["SELECT * FROM resources WHERE owner = ? AND resource_type = ? ORDER BY id" owner resource-type]
            owner ["SELECT * FROM resources WHERE owner = ? ORDER BY id" owner]
            resource-type ["SELECT * FROM resources WHERE resource_type = ? ORDER BY id" resource-type]
            :else ["SELECT * FROM resources ORDER BY id"])]
      (mapv decode-resource (jdbc/execute! connectable (into [sql] params) query-options))))
  (update-resource! [_ resource-id changes]
    (let [allowed (select-keys changes [:state :data])]
      (when (empty? allowed)
        (throw (ex-info "No mutable resource fields supplied" {:type ::no-changes})))
      (let [current (repository/get-resource _ resource-id)]
        (when-not current
          (throw (ex-info "Resource not found" {:type ::repository/not-found :id resource-id})))
        (let [updated (merge current allowed)
              updated-at (str (Instant/now))]
          (jdbc/execute-one! connectable
                             ["UPDATE resources SET state = ?, data_json = ?, updated_at = ? WHERE id = ?"
                              (:state updated) (encode-json (:data updated)) updated-at resource-id])
          (repository/get-resource _ resource-id)))))
  (delete-resource! [_ resource-id]
    (pos? (:next.jdbc/update-count
           (jdbc/execute-one! connectable
                              ["DELETE FROM resources WHERE id = ?" resource-id])))))

(defn sqlite-repository [connectable]
  (->SQLiteResourceRepository connectable))

(defn integrity-check [connectable]
  (with-open [connection (open-connection connectable)]
    (let [rows (jdbc/execute! connection ["PRAGMA integrity_check"] query-options)
          messages (mapv (comp str val first) rows)]
      {:ok? (= ["ok"] messages) :messages messages})))

(defn backup! [connectable destination]
  (with-open [connection (open-connection connectable)]
    (let [target (.getAbsolutePath (io/file destination))
          quoted (str/replace target "'" "''")]
      (io/make-parents target)
      (jdbc/execute! connection [(str "VACUUM INTO '" quoted "'")])
      target)))
