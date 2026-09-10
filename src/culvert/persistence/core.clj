(ns culvert.persistence.core
  (:require [culvert.persistence.sqlite :as sqlite]))

(defn start!
  "Opens and configures a SQLite connection, runs pending migrations, and returns
  a lifecycle handle. This is opt-in and is not connected to the production JSON path."
  [database]
  (let [datasource (sqlite/datasource database)
        connection (sqlite/open-connection datasource)]
    (try
      (sqlite/configure-connection! connection)
      (sqlite/migrate! connection)
      {:datasource datasource :connection connection}
      (catch Throwable throwable
        (sqlite/close-connection! connection)
        (throw throwable)))))

(defn stop! [{:keys [connection]}]
  (sqlite/close-connection! connection)
  nil)
