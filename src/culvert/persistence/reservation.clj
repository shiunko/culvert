(ns culvert.persistence.reservation
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [culvert.persistence.sqlite :as sqlite])
  (:import [java.time Instant]
           [java.util UUID]))

(def ^:private query-options {:builder-fn rs/as-unqualified-lower-maps})
(def ^:private protocols #{"tcp" "udp"})

(defn- fail! [message data]
  (throw (ex-info message data)))

(defn- epoch-second []
  (.getEpochSecond (Instant/now)))

(defn- username [actor]
  (let [value (if (map? actor) (:username actor) actor)]
    (when-not (and (string? value) (not-empty value))
      (fail! "Reservation actor must have a username"
             {:type ::invalid-actor :actor actor}))
    value))

(defn- requested-ports [{:keys [port ports port-range start-port end-port]}]
  (let [values (cond
                 (some? port) [port]
                 (some? ports) ports
                 (some? port-range) (let [[start end] port-range]
                                      (when-not (and (integer? start) (integer? end) (<= start end))
                                        (fail! "Invalid port range"
                                               {:type ::invalid-request :port-range port-range}))
                                      (range start (inc end)))
                 (or (some? start-port) (some? end-port))
                 (do
                   (when-not (and (integer? start-port) (integer? end-port)
                                  (<= start-port end-port))
                     (fail! "Invalid port range"
                            {:type ::invalid-request
                             :start-port start-port :end-port end-port}))
                   (range start-port (inc end-port)))
                 :else (fail! "Reservation request must include a port or port range"
                              {:type ::invalid-request}))
        result (vec values)]
    (when (empty? result)
      (fail! "Reservation request contains no ports" {:type ::invalid-request}))
    (when-not (every? #(and (integer? %) (<= 1 % 65535)) result)
      (fail! "Reservation ports must be integers between 1 and 65535"
             {:type ::invalid-request :ports result}))
    (when-not (= (count result) (count (distinct result)))
      (fail! "Reservation request contains duplicate ports"
             {:type ::invalid-request :ports result}))
    result))

(defn- normalize-request [request now]
  (let [protocol (some-> (:protocol request) name)
        bind-address (or (:bind-address request) "0.0.0.0")
        ttl-seconds (:ttl-seconds request)
        expires-at (or (:expires-at request)
                       (when (some? ttl-seconds) (+ now ttl-seconds)))]
    (when-not (contains? protocols protocol)
      (fail! "Reservation protocol must be tcp or udp"
             {:type ::invalid-request :protocol (:protocol request)}))
    (when-not (and (string? bind-address) (not-empty bind-address))
      (fail! "Reservation bind address must be a non-empty string"
             {:type ::invalid-request :bind-address bind-address}))
    (when-not (and (integer? expires-at) (> expires-at now))
      (fail! "Reservation TTL must expire in the future"
             {:type ::invalid-request :expires-at expires-at :now now}))
    {:bind-address bind-address
     :protocol protocol
     :ports (requested-ports request)
     :expires-at expires-at
     :resource-id (:resource-id request)}))

(defn- decode [row]
  (when row
    {:id (:id row)
     :owner (:owner row)
     :resource-id (:resource_id row)
     :bind-address (:bind_address row)
     :protocol (:protocol row)
     :port (:port row)
     :status (:status row)
     :expires-at (:expires_at row)
     :created-at (:created_at row)}))

(defn- delete-expired! [connectable now]
  (:next.jdbc/update-count
   (jdbc/execute-one! connectable
                      ["DELETE FROM port_reservations WHERE status = 'reserved' AND expires_at <= ?"
                       now])))

(defn expire!
  "Deletes expired, uncommitted reservations and returns the number removed."
  ([connectable]
   (expire! connectable (epoch-second)))
  ([connectable now]
   (sqlite/with-transaction connectable
     #(delete-expired! % now))))

(defn reserve!
  "Atomically reserves every requested port. Returns a vector of reservation rows.

   Request keys include :bind-address, :protocol, one of :port/:ports/:port-range,
   and either :ttl-seconds or :expires-at. A conflicting port fails the entire request."
  [connectable actor request]
  (let [owner (username actor)
        now (or (:now request) (epoch-second))
        {:keys [bind-address protocol ports expires-at resource-id]}
        (normalize-request request now)
        created-at (str (Instant/ofEpochSecond now))]
    (sqlite/with-transaction connectable
      (fn [transaction]
        ;; A write before the conflict check serializes competing SQLite reservations.
        (delete-expired! transaction now)
        (let [placeholders (str/join "," (repeat (count ports) "?"))
              wildcard? (contains? #{"0.0.0.0" "::"} bind-address)
              address-clause (if wildcard?
                               "1 = 1"
                               "bind_address IN (?, '0.0.0.0', '::')")
              address-params (if wildcard? [] [bind-address])
              conflicts (jdbc/execute!
                         transaction
                         (into [(str "SELECT port FROM port_reservations WHERE " address-clause
                                     " AND protocol = ? AND port IN (" placeholders ") ORDER BY port")]
                               (concat address-params [protocol] ports))
                         query-options)]
          (when (seq conflicts)
            (fail! "One or more ports are already reserved"
                   {:type ::conflict
                    :bind-address bind-address
                    :protocol protocol
                    :ports (mapv :port conflicts)}))
          (mapv
           (fn [port]
             (let [id (str (UUID/randomUUID))]
               (jdbc/execute-one!
                transaction
                ["INSERT INTO port_reservations(id, owner, resource_id, bind_address, protocol, port, status, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?, 'reserved', ?, ?)"
                 id owner resource-id bind-address protocol port expires-at created-at])
               (decode
                (jdbc/execute-one! transaction
                                   ["SELECT * FROM port_reservations WHERE id = ?" id]
                                   query-options))))
           ports))))))

(defn commit!
  "Marks a live reservation as committed. Returns the updated row, or nil if absent/expired."
  [connectable reservation-id resource-id]
  (let [now (epoch-second)]
    (sqlite/with-transaction connectable
      (fn [transaction]
        (delete-expired! transaction now)
        (let [result (jdbc/execute-one!
                      transaction
                      ["UPDATE port_reservations SET status = 'committed', resource_id = ?, expires_at = NULL WHERE id = ? AND status = 'reserved'"
                       resource-id reservation-id])]
          (when (pos? (:next.jdbc/update-count result))
            (decode
             (jdbc/execute-one! transaction
                                ["SELECT * FROM port_reservations WHERE id = ?" reservation-id]
                                query-options))))))))

(defn release!
  "Deletes a reservation so its port can immediately be reserved again."
  [connectable reservation-id]
  (sqlite/with-transaction connectable
    (fn [transaction]
      (pos? (:next.jdbc/update-count
             (jdbc/execute-one! transaction
                                ["DELETE FROM port_reservations WHERE id = ?" reservation-id]))))))

(defn list-active
  "Lists committed and non-expired reserved rows. Optional filters are :owner,
   :bind-address, :protocol, and :resource-id."
  ([connectable]
   (list-active connectable {}))
  ([connectable filters]
   (let [now (or (:now filters) (epoch-second))
         clauses (cond-> ["(status = 'committed' OR expires_at > ?)"]
                   (:owner filters) (conj "owner = ?")
                   (:bind-address filters) (conj "bind_address = ?")
                   (:protocol filters) (conj "protocol = ?")
                   (:resource-id filters) (conj "resource_id = ?"))
         params (cond-> [now]
                  (:owner filters) (conj (:owner filters))
                  (:bind-address filters) (conj (:bind-address filters))
                  (:protocol filters) (conj (name (:protocol filters)))
                  (:resource-id filters) (conj (:resource-id filters)))
         sql (str "SELECT * FROM port_reservations WHERE "
                  (str/join " AND " clauses)
                  " ORDER BY bind_address, protocol, port")]
     (mapv decode (jdbc/execute! connectable (into [sql] params) query-options)))))
