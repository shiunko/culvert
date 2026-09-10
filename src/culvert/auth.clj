(ns culvert.auth
  (:require [clojure.string :as str]
            [culvert.config :refer [config]]
            [culvert.db :as db]
            [culvert.i18n :as i18n]
            [culvert.log :as log])
  (:import [javax.crypto SecretKeyFactory]
           [javax.crypto.spec PBEKeySpec]
           [java.security MessageDigest SecureRandom]
           [java.util Base64]))

;; ============================================================
;; PBKDF2 密码学（时序安全与高强度）
;; ============================================================

(defn- generate-salt []
  (let [salt (byte-array 16)
        sr (SecureRandom.)]
    (.nextBytes sr salt)
    salt))

(defn- pbkdf2-hash [password salt iterations key-length]
  (let [spec (PBEKeySpec. (.toCharArray password) salt iterations key-length)
        skf (SecretKeyFactory/getInstance "PBKDF2WithHmacSHA512")
        hash (.getEncoded (.generateSecret skf spec))]
    hash))

(def ^:private pbkdf2-iterations 210000)
(def ^:private pbkdf2-key-length 256)

(defn hash-password
  "使用 PBKDF2-HMAC-SHA512 哈希密码；可选迭代次数仅用于测试迁移行为。"
  ([password]
   (hash-password password pbkdf2-iterations))
  ([password iterations]
   (let [salt (generate-salt)
         hash (pbkdf2-hash password salt iterations pbkdf2-key-length)
         encoder (Base64/getEncoder)]
     (str "pbkdf2$" iterations "$"
          (.encodeToString encoder salt) "$"
          (.encodeToString encoder hash)))))

(defn password-needs-rehash? [stored-hash]
  (try
    (let [[algorithm iterations] (str/split (or stored-hash "") #"\$")]
      (or (not= algorithm "pbkdf2")
          (< (Long/parseLong iterations) pbkdf2-iterations)))
    (catch Exception _
      true)))

(defn verify-password
  "以时序安全方式验证密码与已存哈希。"
  [password stored-hash]
  (cond
    (nil? stored-hash) false

    (str/starts-with? stored-hash "pbkdf2$")
    (try
      (let [parts (str/split stored-hash #"\$")
            iterations (Integer/parseInt (nth parts 1))
            salt-b64 (nth parts 2)
            hash-b64 (nth parts 3)
            decoder (Base64/getDecoder)
            salt (.decode decoder salt-b64)
            stored-bytes (.decode decoder hash-b64)
            computed-bytes (pbkdf2-hash password salt iterations (* 8 (alength stored-bytes)))]
        (MessageDigest/isEqual stored-bytes computed-bytes))
      (catch Exception _
        false))

    :else false))

;; ============================================================
;; 基于 token 的会话存储（atom + TTL）
;; ============================================================

(declare state get-default-quotas)

(defonce token-store (atom {}))
(defonce ^:private token-cleaner (atom nil))

(def ^:private token-ttl-ms (* 24 60 60 1000))
(def ^:private token-max 500)
(def ^:private token-cleanup-ms 300000)

(defn- generate-token-str []
  (let [bytes (byte-array 32)
        sr (java.security.SecureRandom.)]
    (.nextBytes sr bytes)
    (let [hex (StringBuilder.)]
      (doseq [b bytes] (.append hex (format "%02x" b)))
      (.toString hex))))

(defn- normalize-username [username]
  (str/lower-case (str/trim (or username ""))))

(defn- current-auth-version [user-info]
  (long (or (:authVersion user-info) (:auth-version user-info) 1)))

(defn- evict-oldest-session [store]
  (if (> (count store) token-max)
    (dissoc store (key (apply min-key (fn [[token session]]
                                        [(or (:created-at (meta session))
                                             (- (:expires session) token-ttl-ms))
                                         token])
                              store)))
    store))

(defn issue-token [user-info]
  (let [username (normalize-username (:username user-info))
        stored-user (get-in @state [:users (keyword username)])]
    (when-not stored-user
      (throw (ex-info "Cannot issue a session for an unknown user" {:username username})))
    (let [token (generate-token-str)
          now (System/currentTimeMillis)
          session (with-meta {:username username
                              :session-id (generate-token-str)
                              :auth-version (current-auth-version stored-user)
                              :expires (+ now token-ttl-ms)}
                    {:created-at now})]
      (swap! token-store #(-> % (assoc token session) evict-oldest-session))
      token)))

(defn validate-token [token]
  (when-not (str/blank? token)
    (when-let [session (get @token-store token)]
      (let [now (System/currentTimeMillis)
            username (:username session)
            user-info (get-in @state [:users (keyword username)])]
        (if (and (<= now (:expires session))
                 user-info
                 (= (:auth-version session) (current-auth-version user-info)))
          {:username username
           :session-id (:session-id session)
           :auth-version (current-auth-version user-info)
           :role (:role user-info "user")
           :quotas (or (:quotas user-info) (get-default-quotas))
           :createdAt (:createdAt user-info)}
          (do
            (swap! token-store dissoc token)
            nil))))))

(defn revoke-token [token]
  (swap! token-store dissoc token))

(defn revoke-user-sessions! [username]
  (let [username (normalize-username username)]
    (swap! token-store
           (fn [store]
             (into {} (remove (fn [[_ session]]
                                (= username (:username session)))
                              store))))))

(defn cleanup-expired-tokens! []
  (let [now (System/currentTimeMillis)]
    (swap! token-store
           (fn [store]
             (into {} (filter (fn [[_ session]]
                                (<= now (:expires session)))
                              store))))))

(defn start-token-cleaner! []
  (locking token-cleaner
    (when-not @token-cleaner
      (let [running (atom true)
            thread (Thread. (fn []
                              (try
                                (while @running
                                  (Thread/sleep token-cleanup-ms)
                                  (cleanup-expired-tokens!))
                                (catch InterruptedException _))))]
        (.setDaemon thread true)
        (.start thread)
        (reset! token-cleaner {:running running :thread thread})))
    @token-cleaner))

(defn stop-token-cleaner! []
  (locking token-cleaner
    (when-let [{:keys [running thread]} @token-cleaner]
      (reset! running false)
      (.interrupt thread)
      (reset! token-cleaner nil))
    nil))

;; ============================================================
;; 用户数据库状态
;; ============================================================

(defonce state (atom {:users {}}))
(defonce ^:private users-write-lock (Object.))

(defn get-default-quotas []
  (let [dq (:default-quota config)]
    {:portForwards (:portForwards dq 5)
     :reverseProxies (:reverseProxies dq 3)
     :containers (:containers dq 2)
     :smolvmMachines (:smolvmMachines dq 2)
     :cpuLimit (:cpuLimit dq 8)
     :memLimit (:memLimit dq "8g")}))

(defn- persist-users! [users]
  (db/write-json (:users-db-path config) {:users users}))

(defn load-users! []
  (locking users-write-lock
    (let [users (:users (db/read-json (:users-db-path config) {:users {}}) {})]
      (swap! state assoc :users users)
      (log/debug (format "已加载 %d 个用户数据库记录" (count users))))))

(defn bootstrap-admin!
  "首次启动时，若 users.json 为空，用 config.json 的 authUser/authPass 创建 admin 用户。"
  []
  (let [admin-user (normalize-username (:auth-user config))
        admin-pass (or (:auth-pass config) "")
        new-user (when (and (not (str/blank? admin-user))
                            (not (str/blank? admin-pass)))
                   {:password (hash-password admin-pass)
                    :authVersion 1
                    :role "admin"
                    :createdAt (.toString (java.time.Instant/now))
                    :quotas (get-default-quotas)})]
    (locking users-write-lock
      (let [loaded-users (:users (db/read-json (:users-db-path config) {:users {}}) {})]
        (if (and (empty? loaded-users) new-user)
          (let [candidate (assoc loaded-users (keyword admin-user) new-user)]
            (persist-users! candidate)
            (swap! state assoc :users candidate)
            (log/info (format "首次启动：自动创建管理员用户 %s" admin-user)))
          (swap! state assoc :users loaded-users))))))

;; ============================================================
;; 认证与管理 API
;; ============================================================

(defn get-all-users []
  (->> (:users @state)
       (map (fn [[username info]]
              {:username (name username)
               :role (:role info "user")
               :createdAt (:createdAt info)
               :quotas (or (:quotas info) (get-default-quotas))}))
       (sort-by :username)))

(defn get-user [username]
  (let [k (keyword (str/lower-case (str/trim username)))
        info (get-in @state [:users k])]
    (when info
      {:username (name k)
       :role (:role info "user")
       :auth-version (current-auth-version info)
       :createdAt (:createdAt info)
       :quotas (or (:quotas info) (get-default-quotas))})))

(defn get-user-quotas [username]
  (or (:quotas (get-user username)) (get-default-quotas)))

(defn authenticate
  "通过 users.json 中的用户状态认证。旧哈希升级只有在原哈希未被并发修改时才提交。"
  [username password]
  (let [u-clean (normalize-username username)
        k (keyword u-clean)
        user-info (get-in @state [:users k])]
    (when (and user-info (verify-password password (:password user-info)))
      (when (password-needs-rehash? (:password user-info))
        (let [old-hash (:password user-info)
              upgraded-hash (hash-password password)]
          (locking users-write-lock
            (let [users (:users @state)
                  current-user (get users k)]
              (when (and current-user (= old-hash (:password current-user)))
                (let [candidate (assoc-in users [k :password] upgraded-hash)]
                  (persist-users! candidate)
                  (swap! state assoc :users candidate)))))))
      (let [current-user (get-in @state [:users k])]
        (when current-user
          {:username u-clean
           :session-id (str "basic:" u-clean ":" (current-auth-version current-user))
           :auth-version (current-auth-version current-user)
           :role (:role current-user "user")
           :quotas (or (:quotas current-user) (get-default-quotas))
           :createdAt (:createdAt current-user)})))))

(defn create-user! [username password & [opts]]
  (let [u-clean (str/lower-case (str/trim (or username "")))
        k (keyword u-clean)]
    (when (or (str/blank? u-clean) (< (count u-clean) 2) (> (count u-clean) 32))
      (throw (Exception. (:username-length (:validation i18n/strings)))))
    (when-not (re-matches #"^[a-z0-9_][a-z0-9_-]*$" u-clean)
      (throw (Exception. (:username-format (:validation i18n/strings)))))
    (when (or (str/blank? password) (< (count password) 6))
      (throw (Exception. (:password-too-short (:validation i18n/strings)))))

    (let [role (or (:role opts) "user")
          _ (when-not (contains? #{"admin" "user"} role)
              (throw (Exception. (:role-invalid (:validation i18n/strings)))))
          quotas (or (:quotas opts) (get-default-quotas))
          new-user {:password (hash-password password)
                    :authVersion 1
                    :role role
                    :createdAt (.toString (java.time.Instant/now))
                    :quotas quotas}]
      (locking users-write-lock
        (let [users (:users @state)]
          (when (get users k)
            (throw (Exception. ((:user-exists (:validation i18n/strings)) u-clean))))
          (let [candidate (assoc users k new-user)]
            (persist-users! candidate)
            (swap! state assoc :users candidate))))
      (log/info (format "已创建用户: %s (%s)" u-clean role))
      {:username u-clean
       :role role
       :quotas quotas
       :createdAt (:createdAt new-user)})))

(defn delete-user! [username current-username]
  (let [u-clean (normalize-username username)
        current-clean (normalize-username current-username)
        k (keyword u-clean)]
    (locking users-write-lock
      (let [users (:users @state)
            user (get users k)
            admin-count (count (filter #(= (:role %) "admin") (vals users)))]
        (when-not user
          (throw (Exception. ((:user-not-found (:validation i18n/strings)) u-clean))))
        (when (= u-clean current-clean)
          (throw (Exception. (:cannot-delete-self (:validation i18n/strings)))))
        (when (and (= (:role user) "admin") (<= admin-count 1))
          (throw (Exception. (:cannot-delete-admin (:validation i18n/strings)))))
        (let [candidate (dissoc users k)]
          (persist-users! candidate)
          (swap! state assoc :users candidate))))
    (revoke-user-sessions! u-clean)
    (log/info (format "已删除用户: %s" u-clean))))

(defn change-password! [username new-password]
  (let [u-clean (normalize-username username)
        k (keyword u-clean)]
    (when (or (str/blank? new-password) (< (count new-password) 6))
      (throw (Exception. (:password-too-short (:validation i18n/strings)))))
    (let [new-hash (hash-password new-password)]
      (locking users-write-lock
        (let [users (:users @state)]
          (when-not (get users k)
            (throw (Exception. ((:user-not-found (:validation i18n/strings)) u-clean))))
          (let [candidate (-> users
                              (assoc-in [k :password] new-hash)
                              (update-in [k :authVersion] (fnil inc 1)))]
            (persist-users! candidate)
            (swap! state assoc :users candidate)))))
    (revoke-user-sessions! u-clean)
    (log/info (format "已修改用户密码: %s" u-clean))))

(defn- merge-valid-quotas [current-quotas new-quotas]
  (reduce (fn [acc [field val]]
            (cond
              (contains? #{:portForwards :reverseProxies :containers :smolvmMachines} field)
              (let [v (if (string? val) (Integer/parseInt val) val)]
                (if (and (integer? v) (>= v 0))
                  (assoc acc field v)
                  (throw (Exception. ((:quota-must-be-int (:validation i18n/strings)) field)))))

              (= field :cpuLimit)
              (let [v (if (string? val) (Double/parseDouble val) val)]
                (if (and (number? v) (>= v 0))
                  (assoc acc field v)
                  (throw (Exception. (:cpu-quota-must-be-num (:validation i18n/strings))))))

              (= field :memLimit)
              (let [v (str/trim (str val))]
                (if-not (str/blank? v)
                  (assoc acc field v)
                  (throw (Exception. (:mem-quota-not-empty (:validation i18n/strings))))))

              :else acc))
          current-quotas
          new-quotas))

(defn update-quota! [username new-quotas]
  (let [u-clean (normalize-username username)
        k (keyword u-clean)
        updated (locking users-write-lock
                  (let [users (:users @state)
                        user (get users k)]
                    (when-not user
                      (throw (Exception. ((:user-not-found (:validation i18n/strings)) u-clean))))
                    (let [quotas (merge-valid-quotas (or (:quotas user) (get-default-quotas))
                                                     new-quotas)
                          candidate (-> users
                                        (assoc-in [k :quotas] quotas)
                                        (update-in [k :authVersion] (fnil inc 1)))]
                      (persist-users! candidate)
                      (swap! state assoc :users candidate)
                      quotas)))]
    (revoke-user-sessions! u-clean)
    (log/info (format "已更新用户配额: %s" u-clean))
    updated))
