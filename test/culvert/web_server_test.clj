(ns culvert.web-server-test
  (:require [clojure.test :refer [deftest is]]
            [org.httpkit.server :as http-server]
            [culvert.config :as config]
            [culvert.web.server :as server]))

(deftest server-binds-to-configured-address
  (let [captured-options (atom nil)
        stopped? (atom false)]
    (with-redefs [config/config (assoc config/config
                                       :listen-address "127.0.0.1"
                                       :port 10092)
                  http-server/run-server (fn [_handler options]
                                           (reset! captured-options options)
                                           (fn [] (reset! stopped? true)))]
      (server/start-server!)
      (try
        (is (= {:ip "127.0.0.1" :port 10092} @captured-options))
        (finally
          (server/stop-server!)))
      (is (true? @stopped?)))))
