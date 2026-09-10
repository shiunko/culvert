(ns culvert.web.handlers
  "Forward、Caddy、Docker、管理操作及主路由分派器。"
  (:require [clojure.string :as str]
            [hiccup2.core :as h]
            [culvert.web.core :as core]
            [culvert.web.views :as views]
            [culvert.web.ws :as ws]
            [culvert.auth :as auth]
            [culvert.authorization :as authorization]
            [culvert.caddy :as caddy]
            [culvert.cli :as cli]
            [culvert.docker :as docker]
            [culvert.forward :as forward]
            [culvert.health :as health]
            [culvert.i18n :as i18n]
            [culvert.s3 :as s3]
            [culvert.smolvm.manager :as smolvm]))

(defn- request-actor [req]
  (let [user (:user req)]
    (authorization/actor (:username user) (= (:role user) "admin"))))

;; ============================================================
;; Panel Refresh Handlers (polling + WebSocket triggered)
;; ============================================================

(defn- handle-panel-forwards [req]
  (let [user (:user req)
        is-admin? (= (:role user) "admin")]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (str (views/render-forwards-tbody (:username user) is-admin? (core/csrf-generate)))}))

(defn- handle-panel-caddy [req]
  (let [user (:user req)
        is-admin? (= (:role user) "admin")]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (str (views/render-caddy-tbody (:username user) is-admin? (core/csrf-generate)))}))

(defn- handle-panel-docker [req]
  (let [user (:user req)
        is-admin? (= (:role user) "admin")]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (str (views/render-docker-tbody (:username user) is-admin? (core/csrf-generate)))}))

(defn- handle-panel-quota [req]
  (let [user (:user req)]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (str (h/html (views/render-quota-bar-content (:username user) user)))}))

(defn- handle-panel-users [_req]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str (views/render-users-tbody (core/csrf-generate)))})

(defn- handle-panel-overview [req]
  (let [user (:user req)]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (str (views/render-overview-stats-content (:username user)))}))

;; ============================================================
;; Container Logs Handler
;; ============================================================

(defn- handle-docker-stats [req]
  (let [user (:user req)
        is-admin? (= (:role user) "admin")
        containers (docker/get-containers (:username user) is-admin?)
        all-stats (cli/get-container-stats)
        result (into {}
                     (for [c containers
                           :when (= (:status c) "running")
                           :let [stats (get all-stats (:name c))]]
                       [(:id c)
                        (if stats
                          stats
                          {:cpu-pct 0.0 :mem-pct 0.0 :mem-used "—" :mem-limit "—"})]))]
    (core/json-response result)))

(defn- handle-ws-ticket [req]
  (let [params (core/parse-form-body req)
        user (:user req)]
    (if-not (core/csrf-validate (core/get-csrf-token req params))
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (core/json-response {:ticket (core/ws-ticket-generate user)}))))

(defn- handle-docker-logs-start [req]
  (let [params (core/parse-form-body req)
        id (:id params)
        actor (request-actor req)]
    (if-not (core/csrf-validate (core/get-csrf-token req params))
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [result (docker/start-log-ttyd! id actor)]
          (core/json-response result))
        (catch Exception e
          (core/json-error-response e))))))

(defn- handle-docker-logs-stop [req]
  (let [params (core/parse-form-body req)
        id (:id params)
        actor (request-actor req)]
    (if-not (core/csrf-validate (core/get-csrf-token req params))
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (docker/stop-log-ttyd! id actor)
        (core/json-response {:status "ok"})
        (catch Exception e
          (core/json-error-response e))))))

;; ============================================================
;; Route Dispatcher Handlers
;; ============================================================

(defn- handle-index [req]
  (views/render-dashboard req))

;; --- Forward Handlers ---

(defn- handle-add-forward [req]
  (let [params (core/parse-form-body req)
        user (:user req)
        ctx {:username (:username user)
             :is-admin (= (:role user) "admin")
             :csrf (core/get-csrf-token req params)
             :user user}
        before (views/snapshot-atoms views/all-atoms)]
    (if-not (core/csrf-validate (:csrf ctx))
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [ip (:ip params)
              port (:port params)
              to-port (:toPort params)
              protocol (:protocol params)
              method (:method params)
              remark (:remark params)]
          (forward/add-forward! port protocol ip to-port method remark (:username user))
          (ws/broadcast! {:event "refresh" :panels ["forward-tbody" "quota-bar"]})
          (views/build-oob-response "forward-tbody" ctx before
                                    (:forward-added (:toast i18n/strings)) "success"))
        (catch Exception e
          (views/build-oob-error-response "forward-tbody" ctx before e))))))

(defn- handle-remove-forward [req]
  (let [params (core/parse-form-body req)
        user (:user req)
        ctx {:username (:username user)
             :is-admin (= (:role user) "admin")
             :csrf (core/get-csrf-token req params)
             :user user}
        before (views/snapshot-atoms views/all-atoms)]
    (if-not (core/csrf-validate (:csrf ctx))
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [port (:port params)
              protocol (:protocol params)
              method (:method params)]
          (forward/remove-forward! port protocol method (:username user) (:is-admin ctx))
          (ws/broadcast! {:event "refresh" :panels ["forward-tbody" "quota-bar"]})
          (views/build-oob-response "forward-tbody" ctx before
                                    (:forward-removed (:toast i18n/strings)) "success"))
        (catch Exception e
          (views/build-oob-error-response "forward-tbody" ctx before e))))))

(defn- handle-toggle-forward [req]
  (let [params (core/parse-form-body req)
        user (:user req)
        ctx {:username (:username user)
             :is-admin (= (:role user) "admin")
             :csrf (core/get-csrf-token req params)
             :user user}
        before (views/snapshot-atoms views/all-atoms)]
    (if-not (core/csrf-validate (:csrf ctx))
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [port (:port params)
              protocol (:protocol params)
              method (:method params)]
          (forward/toggle-forward! port protocol method (:username user) (:is-admin ctx))
          (ws/broadcast! {:event "refresh" :panels ["forward-tbody" "quota-bar"]})
          (views/build-oob-response "forward-tbody" ctx before
                                    (:forward-toggled (:toast i18n/strings)) "success"))
        (catch Exception e
          (views/build-oob-error-response "forward-tbody" ctx before e))))))

(defn- handle-edit-remark [req]
  (let [params (core/parse-form-body req)
        user (:user req)
        ctx {:username (:username user)
             :is-admin (= (:role user) "admin")
             :csrf (core/get-csrf-token req params)
             :user user}
        before (views/snapshot-atoms views/all-atoms)]
    (if-not (core/csrf-validate (:csrf ctx))
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [port (:port params)
              protocol (:protocol params)
              method (:method params)
              remark (:remark params)]
          (forward/update-remark! port protocol method remark (:username user) (:is-admin ctx))
          (ws/broadcast! {:event "refresh" :panels ["forward-tbody"]})
          (views/build-oob-response "forward-tbody" ctx before
                                    (:remark-updated (:toast i18n/strings)) "success"))
        (catch Exception e
          (views/build-oob-error-response "forward-tbody" ctx before e))))))

;; --- Caddy Handlers ---

(defn- handle-caddy-add [req]
  (let [params (core/parse-form-body req)
        user (:user req)
        ctx {:username (:username user)
             :is-admin (= (:role user) "admin")
             :csrf (core/get-csrf-token req params)
             :user user}
        before (views/snapshot-atoms views/all-atoms)]
    (if-not (core/csrf-validate (:csrf ctx))
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [domain (:domain params)
              worker (:worker params)
              target (:target params)
              remark (:remark params)]
          (caddy/add-rule! domain worker target remark (request-actor req))
          (ws/broadcast! {:event "refresh" :panels ["caddy-tbody" "quota-bar"]})
          (views/build-oob-response "caddy-tbody" ctx before
                                    (:caddy-added (:toast i18n/strings)) "success"))
        (catch Exception e
          (views/build-oob-error-response "caddy-tbody" ctx before e))))))

(defn- handle-caddy-remove [req]
  (let [params (core/parse-form-body req)
        user (:user req)
        ctx {:username (:username user)
             :is-admin (= (:role user) "admin")
             :csrf (core/get-csrf-token req params)
             :user user}
        before (views/snapshot-atoms views/all-atoms)]
    (if-not (core/csrf-validate (:csrf ctx))
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [domain (:domain params)
              worker (:worker params)]
          (caddy/remove-rule! domain worker (:username user) (:is-admin ctx))
          (ws/broadcast! {:event "refresh" :panels ["caddy-tbody" "quota-bar"]})
          (views/build-oob-response "caddy-tbody" ctx before
                                    (:caddy-removed (:toast i18n/strings)) "success"))
        (catch Exception e
          (views/build-oob-error-response "caddy-tbody" ctx before e))))))

(defn- handle-caddy-toggle [req]
  (let [params (core/parse-form-body req)
        user (:user req)
        ctx {:username (:username user)
             :is-admin (= (:role user) "admin")
             :csrf (core/get-csrf-token req params)
             :user user}
        before (views/snapshot-atoms views/all-atoms)]
    (if-not (core/csrf-validate (:csrf ctx))
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [domain (:domain params)
              worker (:worker params)]
          (caddy/toggle-rule! domain worker (:username user) (:is-admin ctx))
          (ws/broadcast! {:event "refresh" :panels ["caddy-tbody" "quota-bar"]})
          (views/build-oob-response "caddy-tbody" ctx before
                                    (:caddy-toggled (:toast i18n/strings)) "success"))
        (catch Exception e
          (views/build-oob-error-response "caddy-tbody" ctx before e))))))

(defn- handle-caddy-edit-remark [req]
  (let [params (core/parse-form-body req)
        user (:user req)
        ctx {:username (:username user)
             :is-admin (= (:role user) "admin")
             :csrf (core/get-csrf-token req params)
             :user user}
        before (views/snapshot-atoms views/all-atoms)]
    (if-not (core/csrf-validate (:csrf ctx))
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [domain (:domain params)
              worker (:worker params)
              remark (:remark params)]
          (caddy/update-remark! domain worker remark (:username user) (:is-admin ctx))
          (ws/broadcast! {:event "refresh" :panels ["caddy-tbody"]})
          (views/build-oob-response "caddy-tbody" ctx before
                                    (:remark-updated (:toast i18n/strings)) "success"))
        (catch Exception e
          (views/build-oob-error-response "caddy-tbody" ctx before e))))))

;; --- Docker Handlers ---

(defn- handle-docker-create [req]
  (let [params (core/parse-form-body req)
        user (:user req)
        ctx {:username (:username user)
             :is-admin (= (:role user) "admin")
             :csrf (core/get-csrf-token req params)
             :user user}
        before (views/snapshot-atoms views/all-atoms)]
    (if-not (core/csrf-validate (:csrf ctx))
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [image (:image params)
              cpu (:cpuLimit params)
              mem (:memLimit params)
              gpu? (boolean (:gpu params))
              s3-config (when (seq (:s3Bucket params))
                          {:endpoint (:s3Endpoint params)
                           :bucket (:s3Bucket params)
                           :accessKey (:s3AccessKey params)
                           :secretKey (:s3SecretKey params)
                           :region (:s3Region params)})
              container (docker/create-container! (:username user) image cpu mem s3-config gpu?)]
          (ws/broadcast! {:event "refresh" :panels ["docker-tbody" "quota-bar" "caddy-tbody" "admin-containers-tbody"]})
          (if (:s3Warning container)
            (views/build-oob-response "docker-tbody" ctx before
                                      ((:container-created-s3-warn (:toast i18n/strings)) (:s3Warning container)) "warning")
            (views/build-oob-response "docker-tbody" ctx before
                                      ((:container-created (:toast i18n/strings)) (:name container)) "success")))
        (catch Exception e
          (views/build-oob-error-response "docker-tbody" ctx before e))))))

(defn- handle-docker-kill [req]
  (let [params (core/parse-form-body req)
        user (:user req)
        ctx {:username (:username user)
             :is-admin (= (:role user) "admin")
             :csrf (core/get-csrf-token req params)
             :user user}
        before (views/snapshot-atoms views/all-atoms)]
    (if-not (core/csrf-validate (:csrf ctx))
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [id (:id params)]
          (docker/kill-container! id (:username user) (:is-admin ctx))
          (ws/broadcast! {:event "refresh" :panels ["docker-tbody" "quota-bar" "caddy-tbody" "admin-containers-tbody"]})
          (views/build-oob-response "docker-tbody" ctx before
                                    (:container-deleted (:toast i18n/strings)) "success"))
        (catch Exception e
          (views/build-oob-error-response "docker-tbody" ctx before e))))))

(defn- handle-docker-status [req]
  (let [id (last (str/split (:uri req) #"/"))
        user (:user req)
        is-admin? (= (:role user) "admin")
        container (docker/get-container id (:username user) is-admin?)]
    (if-not container
      {:status 404 :body (:not-found (:toast i18n/strings))}
      (do
        (docker/refresh-status! id)
        (let [fresh-c (docker/get-container id (:username user) is-admin?)
              response {:container fresh-c :_csrf "multi-use"}]
          (core/json-response response))))))

(defn- handle-docker-credentials [req]
  (let [params (core/parse-form-body req)
        user (:user req)
        is-admin? (= (:role user) "admin")
        id (:id params)]
    (if-not (core/csrf-validate (core/get-csrf-token req params))
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (if-not (docker/get-container id (:username user) is-admin?)
        {:status 404 :body (:not-found (:toast i18n/strings))}
        (core/json-response
         {:shellPass (docker/get-shell-pass id (:username user) is-admin?)})))))

(defn- handle-docker-port-add [req]
  (let [params (core/parse-form-body req)
        user (:user req)
        ctx {:username (:username user)
             :is-admin (= (:role user) "admin")
             :csrf (core/get-csrf-token req params)
             :user user}
        before (views/snapshot-atoms views/all-atoms)]
    (if-not (core/csrf-validate (:csrf ctx))
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [id (:id params)
              port (:port params)
              remark (:remark params)]
          (docker/expose-port! id port remark (request-actor req))
          (ws/broadcast! {:event "refresh" :panels ["docker-tbody" "quota-bar" "caddy-tbody"]})
          (views/build-oob-response "docker-tbody" ctx before
                                    ((:port-exposed (:toast i18n/strings)) port) "success"))
        (catch Exception e
          (views/build-oob-error-response "docker-tbody" ctx before e))))))

(defn- handle-docker-port-remove [req]
  (let [params (core/parse-form-body req)
        user (:user req)
        ctx {:username (:username user)
             :is-admin (= (:role user) "admin")
             :csrf (core/get-csrf-token req params)
             :user user}
        before (views/snapshot-atoms views/all-atoms)]
    (if-not (core/csrf-validate (:csrf ctx))
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [id (:id params)
              port (:port params)]
          (docker/unexpose-port! id port (request-actor req))
          (ws/broadcast! {:event "refresh" :panels ["docker-tbody" "quota-bar" "caddy-tbody"]})
          (views/build-oob-response "docker-tbody" ctx before
                                    ((:port-unexposed (:toast i18n/strings)) port) "success"))
        (catch Exception e
          (views/build-oob-error-response "docker-tbody" ctx before e))))))

(defn- handle-docker-port-toggle [req]
  (let [params (core/parse-form-body req)
        user (:user req)
        ctx {:username (:username user)
             :is-admin (= (:role user) "admin")
             :csrf (core/get-csrf-token req params)
             :user user}
        before (views/snapshot-atoms views/all-atoms)]
    (if-not (core/csrf-validate (:csrf ctx))
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [id (:id params)
              port (:port params)]
          (docker/toggle-port! id port (request-actor req))
          (ws/broadcast! {:event "refresh" :panels ["docker-tbody" "quota-bar"]})
          (views/build-oob-response "docker-tbody" ctx before
                                    (:port-toggled (:toast i18n/strings)) "success"))
        (catch Exception e
          (views/build-oob-error-response "docker-tbody" ctx before e))))))

(defn- handle-docker-s3-test [req]
  (let [params (core/parse-form-body req)
        csrf-token (core/get-csrf-token req params)]
    (if-not (core/csrf-validate csrf-token)
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [s3-config {:endpoint (:s3Endpoint params)
                         :bucket (:s3Bucket params)
                         :accessKey (:s3AccessKey params)
                         :secretKey (:s3SecretKey params)
                         :region (:s3Region params)}
              result (s3/test-connection s3-config)]
          (if (:success result)
            {:status 200
             :headers {"Content-Type" "text/html; charset=utf-8"}
             :body (str (h/html [:span.pf-text-success "🟢 " (:message result)]))}
            {:status 200
             :headers {"Content-Type" "text/html; charset=utf-8"}
             :body (str (h/html [:span.pf-text-error "🔴 " (:message result)]))}))
        (catch Exception e
          (let [{:keys [message status]} (core/error-info e)]
            {:status status
             :headers {"Content-Type" "text/html; charset=utf-8"}
             :body (str (h/html [:span.pf-text-error "🔴 " message]))}))))))

(defn- handle-docker-start [req]
  (let [params (core/parse-form-body req)
        user (:user req)
        ctx {:username (:username user)
             :is-admin (= (:role user) "admin")
             :csrf (core/get-csrf-token req params)
             :user user}
        before (views/snapshot-atoms views/all-atoms)]
    (if-not (core/csrf-validate (:csrf ctx))
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [id (:id params)]
          (docker/start-container! id (:username user) (:is-admin ctx))
          (ws/broadcast! {:event "refresh" :panels ["docker-tbody" "quota-bar" "caddy-tbody" "admin-containers-tbody"]})
          (views/build-oob-response "docker-tbody" ctx before
                                    ((:container-started (:toast i18n/strings)) id) "success"))
        (catch Exception e
          (views/build-oob-error-response "docker-tbody" ctx before e))))))

;; --- SmolVM Handlers ---

(defn- handle-panel-smolvm [req]
  (let [user (:user req)
        is-admin? (= (:role user) "admin")]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (str (views/render-smolvm-tbody (:username user) is-admin? (core/csrf-generate)))}))

(defn- handle-smolvm-create [req]
  (let [params (core/parse-form-body req)
        user (:user req)
        ctx {:username (:username user)
             :is-admin (= (:role user) "admin")
             :csrf (core/get-csrf-token req params)
             :user user}
        before (views/snapshot-atoms views/all-atoms)]
    (if-not (core/csrf-validate (:csrf ctx))
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [image (:image params)
              cpu (:cpuLimit params)
              mem (:memLimit params)
              net? (boolean (:netEnabled params))
              ssh-agent? (boolean (:sshAgent params))
              allow-hosts (when-let [raw (:allowHosts params)]
                            (vec (remove str/blank? (str/split-lines raw))))
              allow-cidrs (when-let [raw (:allowCidrs params)]
                            (vec (remove str/blank? (str/split-lines raw))))
              opts {:net? net?
                    :ssh-agent? ssh-agent?
                    :allow-hosts (or allow-hosts [])
                    :allow-cidrs (or allow-cidrs [])}
              machine (smolvm/create-machine! (:username user) image cpu mem opts)]
          (ws/broadcast! {:event "refresh" :panels ["smolvm-tbody" "quota-bar"]})
          (views/build-oob-response "smolvm-tbody" ctx before
                                    ((:machine-created (:toast i18n/strings)) (:name machine)) "success"))
        (catch Exception e
          (views/build-oob-error-response "smolvm-tbody" ctx before e))))))

(defn- handle-smolvm-kill [req]
  (let [params (core/parse-form-body req)
        user (:user req)
        ctx {:username (:username user)
             :is-admin (= (:role user) "admin")
             :csrf (core/get-csrf-token req params)
             :user user}
        before (views/snapshot-atoms views/all-atoms)]
    (if-not (core/csrf-validate (:csrf ctx))
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [id (:id params)]
          (smolvm/kill-machine! id (:username user) (:is-admin ctx))
          (ws/broadcast! {:event "refresh" :panels ["smolvm-tbody" "quota-bar" "caddy-tbody"]})
          (views/build-oob-response "smolvm-tbody" ctx before
                                    (:machine-deleted (:toast i18n/strings)) "success"))
        (catch Exception e
          (views/build-oob-error-response "smolvm-tbody" ctx before e))))))

(defn- handle-smolvm-start [req]
  (let [params (core/parse-form-body req)
        user (:user req)
        ctx {:username (:username user)
             :is-admin (= (:role user) "admin")
             :csrf (core/get-csrf-token req params)
             :user user}
        before (views/snapshot-atoms views/all-atoms)]
    (if-not (core/csrf-validate (:csrf ctx))
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [id (:id params)]
          (smolvm/start-machine! id (:username user) (:is-admin ctx))
          (ws/broadcast! {:event "refresh" :panels ["smolvm-tbody" "quota-bar" "caddy-tbody"]})
          (views/build-oob-response "smolvm-tbody" ctx before
                                    ((:machine-started (:toast i18n/strings)) id) "success"))
        (catch Exception e
          (views/build-oob-error-response "smolvm-tbody" ctx before e))))))

(defn- handle-smolvm-stop [req]
  (let [params (core/parse-form-body req)
        user (:user req)
        ctx {:username (:username user)
             :is-admin (= (:role user) "admin")
             :csrf (core/get-csrf-token req params)
             :user user}
        before (views/snapshot-atoms views/all-atoms)]
    (if-not (core/csrf-validate (:csrf ctx))
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [id (:id params)]
          (smolvm/stop-machine! id (:username user) (:is-admin ctx))
          (ws/broadcast! {:event "refresh" :panels ["smolvm-tbody" "quota-bar"]})
          (views/build-oob-response "smolvm-tbody" ctx before
                                    ((:machine-stopped (:toast i18n/strings)) id) "success"))
        (catch Exception e
          (views/build-oob-error-response "smolvm-tbody" ctx before e))))))

(defn- handle-smolvm-status [req]
  (let [id (last (str/split (:uri req) #"/"))
        user (:user req)
        is-admin? (= (:role user) "admin")
        machine (smolvm/get-machine id (:username user) is-admin?)]
    (if-not machine
      {:status 404 :body (:not-found (:toast i18n/strings))}
      (do
        (smolvm/refresh-status! id)
        (let [fresh-m (smolvm/get-machine id (:username user) is-admin?)
              response {:container fresh-m :_csrf "multi-use"}]
          (core/json-response response))))))

(defn- handle-smolvm-credentials [req]
  (let [params (core/parse-form-body req)
        user (:user req)
        is-admin? (= (:role user) "admin")
        id (:id params)]
    (if-not (core/csrf-validate (core/get-csrf-token req params))
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (if-not (smolvm/get-machine id (:username user) is-admin?)
        {:status 404 :body (:not-found (:toast i18n/strings))}
        (core/json-response
         {:shellPass (smolvm/get-shell-pass id (:username user) is-admin?)})))))

(defn- handle-smolvm-port-add [req]
  (let [params (core/parse-form-body req)
        user (:user req)
        ctx {:username (:username user)
             :is-admin (= (:role user) "admin")
             :csrf (core/get-csrf-token req params)
             :user user}
        before (views/snapshot-atoms views/all-atoms)]
    (if-not (core/csrf-validate (:csrf ctx))
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [id (:id params)
              port (:port params)
              remark (:remark params)]
          (smolvm/expose-port! id port remark (request-actor req))
          (ws/broadcast! {:event "refresh" :panels ["smolvm-tbody" "quota-bar" "caddy-tbody"]})
          (views/build-oob-response "smolvm-tbody" ctx before
                                    ((:machine-port-exposed (:toast i18n/strings)) port) "success"))
        (catch Exception e
          (views/build-oob-error-response "smolvm-tbody" ctx before e))))))

(defn- handle-smolvm-port-remove [req]
  (let [params (core/parse-form-body req)
        user (:user req)
        ctx {:username (:username user)
             :is-admin (= (:role user) "admin")
             :csrf (core/get-csrf-token req params)
             :user user}
        before (views/snapshot-atoms views/all-atoms)]
    (if-not (core/csrf-validate (:csrf ctx))
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [id (:id params)
              port (:port params)]
          (smolvm/unexpose-port! id port (request-actor req))
          (ws/broadcast! {:event "refresh" :panels ["smolvm-tbody" "quota-bar" "caddy-tbody"]})
          (views/build-oob-response "smolvm-tbody" ctx before
                                    ((:machine-port-unexposed (:toast i18n/strings)) port) "success"))
        (catch Exception e
          (views/build-oob-error-response "smolvm-tbody" ctx before e))))))

(defn- handle-smolvm-port-toggle [req]
  (let [params (core/parse-form-body req)
        user (:user req)
        ctx {:username (:username user)
             :is-admin (= (:role user) "admin")
             :csrf (core/get-csrf-token req params)
             :user user}
        before (views/snapshot-atoms views/all-atoms)]
    (if-not (core/csrf-validate (:csrf ctx))
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [id (:id params)
              port (:port params)]
          (smolvm/toggle-port! id port (request-actor req))
          (ws/broadcast! {:event "refresh" :panels ["smolvm-tbody" "quota-bar"]})
          (views/build-oob-response "smolvm-tbody" ctx before
                                    (:machine-port-toggled (:toast i18n/strings)) "success"))
        (catch Exception e
          (views/build-oob-error-response "smolvm-tbody" ctx before e))))))

(defn- handle-smolvm-pack [req]
  (let [params (core/parse-form-body req)
        user (:user req)
        ctx {:username (:username user)
             :is-admin (= (:role user) "admin")
             :csrf (core/get-csrf-token req params)
             :user user}
        before (views/snapshot-atoms views/all-atoms)]
    (if-not (core/csrf-validate (:csrf ctx))
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [id (:id params)]
          (smolvm/pack-machine! id "/tmp" (:username user) (:is-admin ctx))
          (views/build-oob-response "smolvm-tbody" ctx before
                                    ((:machine-packed (:toast i18n/strings)) id) "success"))
        (catch Exception e
          (views/build-oob-error-response "smolvm-tbody" ctx before e))))))

;; --- Admin Container Management Handlers ---

(defn- handle-admin-containers [_req]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str (views/render-admin-containers-tbody (core/csrf-generate)))})

(defn- handle-admin-assign-container [req]
  (let [params (core/parse-form-body req)
        csrf-token (core/get-csrf-token req params)]
    (if-not (core/csrf-validate csrf-token)
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [container-name (:name params)
              user-id (:user-id params)]
          (docker/assign-container! container-name user-id)
          (ws/broadcast! {:event "refresh" :panels ["admin-containers-tbody" "docker-tbody" "quota-bar" "caddy-tbody"]})
          (core/htmx-response (views/render-admin-containers-tbody csrf-token)
                              ((:container-assigned (:toast i18n/strings)) {:name container-name :user user-id}) "success"))
        (catch Exception e
          (core/htmx-error-response (views/render-admin-containers-tbody csrf-token) e))))))

;; --- Admin Handlers ---

(defn- handle-admin-create-user [req]
  (let [params (core/parse-form-body req)
        csrf-token (core/get-csrf-token req params)]
    (if-not (core/csrf-validate csrf-token)
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [username (:username params)
              password (:password params)
              role (:role params)]
          (auth/create-user! username password {:role role})
          (ws/broadcast! {:event "refresh" :panels ["admin-users-tbody"]})
          (core/htmx-response (views/render-users-tbody csrf-token)
                              ((:user-created (:toast i18n/strings)) username) "success"))
        (catch Exception e
          (core/htmx-error-response (views/render-users-tbody csrf-token) e))))))

(defn- handle-admin-delete-user [req]
  (let [params (core/parse-form-body req)
        current-user (:user req)
        csrf-token (core/get-csrf-token req params)]
    (if-not (core/csrf-validate csrf-token)
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [username (:username params)]
          (auth/delete-user! username (:username current-user))
          (ws/broadcast! {:event "refresh" :panels ["admin-users-tbody" "quota-bar"]})
          (core/htmx-response (views/render-users-tbody csrf-token)
                              ((:user-deleted (:toast i18n/strings)) username) "success"))
        (catch Exception e
          (core/htmx-error-response (views/render-users-tbody csrf-token) e))))))

(defn- handle-admin-change-password [req]
  (let [params (core/parse-form-body req)
        csrf-token (core/get-csrf-token req params)
        csrf-context (assoc (:security-context req) :action :admin/change-password)]
    (if-not (core/csrf-validate csrf-token csrf-context true)
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [username (:username params)
              password (:password params)]
          (auth/change-password! username password)
          (ws/broadcast! {:event "refresh" :panels ["admin-users-tbody"]})
          (core/htmx-response (views/render-users-tbody csrf-token)
                              ((:password-changed (:toast i18n/strings)) username) "success"
                              {"closePasswordModal" {}}))
        (catch Exception e
          (core/htmx-error-response (views/render-users-tbody csrf-token) e))))))

(defn- handle-admin-update-quota [req]
  (let [params (core/parse-form-body req)
        csrf-token (core/get-csrf-token req params)]
    (if-not (core/csrf-validate csrf-token)
      {:status 403 :body (:csrf-failed (:toast i18n/strings))}
      (try
        (let [username (:username params)
              quotas {:portForwards (:portForwards params)
                      :reverseProxies (:reverseProxies params)
                      :containers (:containers params)
                      :smolvmMachines (:smolvmMachines params)
                      :cpuLimit (:cpuLimit params)
                      :memLimit (:memLimit params)}]
          (auth/update-quota! username quotas)
          (ws/broadcast! {:event "refresh" :panels ["admin-users-tbody" "quota-bar"]})
          (core/htmx-response (views/render-users-tbody csrf-token)
                              ((:quota-updated (:toast i18n/strings)) username) "success"))
        (catch Exception e
          (core/htmx-error-response (views/render-users-tbody csrf-token) e))))))

;; ============================================================
;; 备注编辑表单处理器（HTMX GET/POST 两步）
;; ============================================================

(defn- handle-forward-remark-form [req]
  (let [qs (core/parse-query-string (:query-string req))
        csrf-token (core/csrf-generate)]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (str (h/html
                 [:form {:hx-post "/edit-remark"
                         :hx-target "#forward-tbody"
                         :hx-swap "innerHTML"}
                  [:input {:type "hidden" :name "_csrf" :value csrf-token}]
                  [:input {:type "hidden" :name "port" :value (:port qs)}]
                  [:input {:type "hidden" :name "protocol" :value (:protocol qs)}]
                  [:input {:type "hidden" :name "method" :value (:method qs)}]
                  [:input.pf-input
                   {:type "text" :name "remark"
                    :style "width:100%"
                    :value (or (:remark qs) "")
                    :autofocus true
                    :data-remark-cancel "/forward/remark-cancel"}]]))}))

(defn- handle-forward-remark-cancel [req]
  (let [qs (core/parse-query-string (:query-string req))]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (str (h/html (list (or (:remark qs) "")
                              " "
                              [:button.pf-btn.pf-btn-ghost.pf-btn-xs
                               {:title (:edit-remark (:tooltip i18n/strings))
                                :hx-get (str "/forward/remark-form?port=" (:port qs)
                                             "&protocol=" (:protocol qs)
                                             "&method=" (:method qs)
                                             "&remark=" (java.net.URLEncoder/encode (or (:remark qs) "") "UTF-8"))
                                :hx-target "closest .remark-cell"
                                :hx-swap "innerHTML"}
                               "✏️"])))}))

(defn- handle-caddy-remark-form [req]
  (let [qs (core/parse-query-string (:query-string req))
        csrf-token (core/csrf-generate)]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (str (h/html
                 [:form {:hx-post "/caddy/edit-remark"
                         :hx-target "#caddy-tbody"
                         :hx-swap "innerHTML"}
                  [:input {:type "hidden" :name "_csrf" :value csrf-token}]
                  [:input {:type "hidden" :name "domain" :value (:domain qs)}]
                  [:input {:type "hidden" :name "worker" :value (:worker qs)}]
                  [:input.pf-input
                   {:type "text" :name "remark"
                    :style "width:100%"
                    :value (or (:remark qs) "")
                    :autofocus true
                    :data-remark-cancel "/caddy/remark-cancel"}]]))}))

(defn- handle-caddy-remark-cancel [req]
  (let [qs (core/parse-query-string (:query-string req))]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (str (h/html (list (or (:remark qs) "")
                              " "
                              [:button.pf-btn.pf-btn-ghost.pf-btn-xs
                               {:title (:edit-remark (:tooltip i18n/strings))
                                :hx-get (str "/caddy/remark-form?domain=" (:domain qs)
                                             "&worker=" (:worker qs)
                                             "&remark=" (java.net.URLEncoder/encode (or (:remark qs) "") "UTF-8"))
                                :hx-target "closest .remark-cell"
                                :hx-swap "innerHTML"}
                               (:edit-remark (:button i18n/strings))])))}))

;; --- Admin Password Modal Handler ---

(defn- handle-admin-password-form [req]
  (let [qs (core/parse-query-string (:query-string req))
        username (:username qs)
        csrf-token (core/csrf-generate {:action :admin/change-password})]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (str (h/html
                 [:dialog.pf-modal {:id "passwordModal"}
                  [:div.pf-modal-box
                   [:form {:hx-post "/admin/users/password"
                           :hx-target "#admin-users-tbody"
                           :hx-swap "innerHTML"}
                    [:input {:type "hidden" :name "_csrf" :value csrf-token}]
                    [:h3.pf-modal-title (:change-password-title (:modal i18n/strings))]
                    [:div.pf-modal-body
                     [:input {:type "hidden" :name "username" :value username}]
                     [:label {:for "modal-new-password"}
                      [:span.pf-label-text (:label-new-password (:form i18n/strings))]]
                     [:input.pf-input {:type "password" :name "password" :id "modal-new-password" :placeholder (:placeholder-password (:form i18n/strings)) :minlength 6 :required true}]]
                    [:div.pf-modal-actions
                     [:button.pf-btn {:type "button" :data-close-password-modal "true"} (:label-cancel (:form i18n/strings))]
                     [:button.pf-btn.pf-btn-primary {:type "submit"} (:label-confirm-change (:form i18n/strings))]]]]
                  [:form.pf-modal-backdrop-close {:method "dialog"}
                   [:button "close"]]]))}))

;; ============================================================
;; Main Router Dispatcher
;; ============================================================

(defn- admin-only [handler]
  (fn [r]
    (if (= (get-in r [:user :role]) "admin")
      (handler r)
      {:status 403 :body (:forbidden (:toast i18n/strings))})))

(defn route-dispatcher [req]
  (let [{:keys [request-method uri]} req]
    (cond
      ;; Static files
      (and (= request-method :get) (= uri "/main.js"))
      (core/serve-static-file "main.js" "application/javascript")

      (and (= request-method :get) (= uri "/app.css"))
      (core/serve-static-file "app.css" "text/css")

      (and (= request-method :get) (= uri "/tabs.js"))
      (core/serve-static-file "tabs.js" "application/javascript")

      ;; Login/Logout (no auth required for login)
      (and (= request-method :post) (= uri "/login"))
      (core/handle-login req)

      (and (= request-method :post) (= uri "/logout"))
      (core/handle-logout req)

      ;; WebSocket 短期连接票据
      (and (= request-method :post) (= uri "/ws/ticket"))
      ((core/wrap-auth handle-ws-ticket) req)

      ;; Dashboard Index
      (and (= request-method :get) (= uri "/"))
      ((core/wrap-auth handle-index) req)

      ;; Panel refresh endpoints (polling + WebSocket triggered)
      (and (= request-method :get) (= uri "/panel/forwards"))
      ((core/wrap-auth handle-panel-forwards) req)

      (and (= request-method :get) (= uri "/panel/caddy"))
      ((core/wrap-auth handle-panel-caddy) req)

      (and (= request-method :get) (= uri "/panel/docker"))
      ((core/wrap-auth handle-panel-docker) req)

      (and (= request-method :get) (= uri "/panel/quota"))
      ((core/wrap-auth handle-panel-quota) req)

      (and (= request-method :get) (= uri "/panel/overview"))
      ((core/wrap-auth handle-panel-overview) req)

      ;; Admin panel refresh (requires admin role)
      (and (= request-method :get) (= uri "/panel/users"))
      ((core/wrap-auth (admin-only handle-panel-users)) req)

      ;; Forwards API
      (and (= request-method :post) (= uri "/add"))
      ((core/wrap-auth handle-add-forward) req)

      (and (= request-method :post) (= uri "/remove"))
      ((core/wrap-auth handle-remove-forward) req)

      (and (= request-method :post) (= uri "/toggle"))
      ((core/wrap-auth handle-toggle-forward) req)

      (and (= request-method :post) (= uri "/edit-remark"))
      ((core/wrap-auth handle-edit-remark) req)

      ;; Remark form GET (htmx inline edit)
      (and (= request-method :get) (= uri "/forward/remark-form"))
      ((core/wrap-auth handle-forward-remark-form) req)

      (and (= request-method :get) (= uri "/forward/remark-cancel"))
      ((core/wrap-auth handle-forward-remark-cancel) req)

      ;; Caddy API
      (and (= request-method :post) (= uri "/caddy/add"))
      ((core/wrap-auth handle-caddy-add) req)

      (and (= request-method :post) (= uri "/caddy/remove"))
      ((core/wrap-auth handle-caddy-remove) req)

      (and (= request-method :post) (= uri "/caddy/toggle"))
      ((core/wrap-auth handle-caddy-toggle) req)

      (and (= request-method :post) (= uri "/caddy/edit-remark"))
      ((core/wrap-auth handle-caddy-edit-remark) req)

      ;; Caddy remark form GET (htmx inline edit)
      (and (= request-method :get) (= uri "/caddy/remark-form"))
      ((core/wrap-auth handle-caddy-remark-form) req)

      (and (= request-method :get) (= uri "/caddy/remark-cancel"))
      ((core/wrap-auth handle-caddy-remark-cancel) req)

      ;; Container/Docker API
      (and (= request-method :post) (= uri "/docker/create"))
      ((core/wrap-auth handle-docker-create) req)

      (and (= request-method :post) (= uri "/docker/kill"))
      ((core/wrap-auth handle-docker-kill) req)

      (and (= request-method :post) (= uri "/docker/start"))
      ((core/wrap-auth handle-docker-start) req)

      (and (= request-method :get) (str/starts-with? uri "/docker/status/"))
      ((core/wrap-auth handle-docker-status) req)

      (and (= request-method :post) (= uri "/docker/credentials"))
      ((core/wrap-auth handle-docker-credentials) req)

      (and (= request-method :post) (= uri "/docker/port/add"))
      ((core/wrap-auth handle-docker-port-add) req)

      (and (= request-method :post) (= uri "/docker/port/remove"))
      ((core/wrap-auth handle-docker-port-remove) req)

      (and (= request-method :post) (= uri "/docker/port/toggle"))
      ((core/wrap-auth handle-docker-port-toggle) req)

      (and (= request-method :post) (= uri "/docker/s3-test"))
      ((core/wrap-auth handle-docker-s3-test) req)

      ;; Container Stats (JSON)
      (and (= request-method :get) (= uri "/docker/stats"))
      ((core/wrap-auth handle-docker-stats) req)

      ;; Container Log Streaming (ttyd)
      (and (= request-method :post) (= uri "/docker/logs/start"))
      ((core/wrap-auth handle-docker-logs-start) req)

      (and (= request-method :post) (= uri "/docker/logs/stop"))
      ((core/wrap-auth handle-docker-logs-stop) req)

      ;; SmolVM Panel
      (and (= request-method :get) (= uri "/panel/smolvm"))
      ((core/wrap-auth handle-panel-smolvm) req)

      ;; SmolVM API
      (and (= request-method :post) (= uri "/smolvm/create"))
      ((core/wrap-auth handle-smolvm-create) req)

      (and (= request-method :post) (= uri "/smolvm/kill"))
      ((core/wrap-auth handle-smolvm-kill) req)

      (and (= request-method :post) (= uri "/smolvm/start"))
      ((core/wrap-auth handle-smolvm-start) req)

      (and (= request-method :post) (= uri "/smolvm/stop"))
      ((core/wrap-auth handle-smolvm-stop) req)

      (and (= request-method :get) (str/starts-with? uri "/smolvm/status/"))
      ((core/wrap-auth handle-smolvm-status) req)

      (and (= request-method :post) (= uri "/smolvm/credentials"))
      ((core/wrap-auth handle-smolvm-credentials) req)

      (and (= request-method :post) (= uri "/smolvm/port/add"))
      ((core/wrap-auth handle-smolvm-port-add) req)

      (and (= request-method :post) (= uri "/smolvm/port/remove"))
      ((core/wrap-auth handle-smolvm-port-remove) req)

      (and (= request-method :post) (= uri "/smolvm/port/toggle"))
      ((core/wrap-auth handle-smolvm-port-toggle) req)

      (and (= request-method :post) (= uri "/smolvm/pack"))
      ((core/wrap-auth handle-smolvm-pack) req)

      ;; Admin API (admin-only)
      (and (= request-method :post) (= uri "/admin/users/create"))
      ((core/wrap-auth (admin-only handle-admin-create-user)) req)

      (and (= request-method :post) (= uri "/admin/users/delete"))
      ((core/wrap-auth (admin-only handle-admin-delete-user)) req)

      (and (= request-method :post) (= uri "/admin/users/password"))
      ((core/wrap-auth (admin-only handle-admin-change-password)) req)

      (and (= request-method :post) (= uri "/admin/users/quota"))
      ((core/wrap-auth (admin-only handle-admin-update-quota)) req)

      ;; Admin password form GET (htmx modal)
      (and (= request-method :get) (= uri "/admin/password-form"))
      ((core/wrap-auth (admin-only handle-admin-password-form)) req)

      ;; Admin container management (admin-only)
      (and (= request-method :get) (= uri "/admin/containers"))
      ((core/wrap-auth (admin-only handle-admin-containers)) req)

      (and (= request-method :post) (= uri "/admin/containers/assign"))
      ((core/wrap-auth (admin-only handle-admin-assign-container)) req)

      ;; Health checks (no auth required)
      (and (= request-method :get) (contains? #{"/health" "/health/live"} uri))
      (health/live-response)

      (and (= request-method :get) (= uri "/health/ready"))
      (health/ready-response)

      :else
      {:status 404
       :body (:not-found (:toast i18n/strings))})))
