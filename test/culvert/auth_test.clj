(ns culvert.auth-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [culvert.auth :as auth]
            [culvert.config :as config]
            [culvert.db :as db]))

(deftest test-password-hashing
  (testing "PBKDF2 hashing and verification"
    (let [password "my-super-secret-pass"
          hashed (auth/hash-password password)]
      (is (str/starts-with? hashed "pbkdf2$"))
      (is (auth/verify-password password hashed))
      (is (not (auth/verify-password "wrong-password" hashed)))))

  (testing "Plaintext credentials are never accepted"
    (is (not (auth/verify-password "plain-pass" "plain-pass")))
    (is (not (auth/verify-password "wrong-pass" "plain-pass"))))

  (testing "Current hashes use a strong iteration count"
    (let [[_ iterations] (str/split (auth/hash-password "iteration-test") #"\$")]
      (is (>= (Long/parseLong iterations) 200000)))))

(deftest test-user-crud
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "culvert-auth-test-"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
        test-db-file (io/file directory "users.json")]
    (try
      (with-redefs [config/config (assoc config/config :users-db-path (.getAbsolutePath test-db-file))]
        (testing "Initial state is empty or loaded"
          (auth/load-users!)
          (is (empty? (auth/get-all-users))))

        (testing "Create user"
          (let [user (auth/create-user! "testuser" "password123" {:role "user"})]
            (is (= (:username user) "testuser"))
            (is (= (:role user) "user"))
            (is (some? (:createdAt user)))
            (is (= (count (auth/get-all-users)) 1))))

        (testing "Get user"
          (let [user (auth/get-user "testuser")]
            (is (= (:username user) "testuser"))
            (is (= (:role user) "user"))))

        (testing "Authenticate user"
          (is (auth/authenticate "testuser" "password123"))
          (is (nil? (auth/authenticate "testuser" "wrongpass")))
          (is (nil? (auth/authenticate "nonexistent" "pass"))))

        (testing "Legacy PBKDF2 hashes are upgraded after successful login"
          (let [legacy-hash (auth/hash-password "password123" 10000)]
            (swap! auth/state assoc-in [:users :testuser :password] legacy-hash)
            (is (auth/password-needs-rehash? legacy-hash))
            (is (auth/authenticate "testuser" "password123"))
            (let [upgraded (get-in @auth/state [:users :testuser :password])]
              (is (not= legacy-hash upgraded))
              (is (not (auth/password-needs-rehash? upgraded))))))

        (testing "Tokens contain no authorization snapshot and validation reloads current user"
          (reset! auth/token-store {})
          (let [token (auth/issue-token (auth/authenticate "testuser" "password123"))
                stored (get @auth/token-store token)]
            (is (= #{:username :session-id :auth-version :expires} (set (keys stored))))
            (is (= "user" (:role (auth/validate-token token))))
            (swap! auth/state assoc-in [:users :testuser :role] "admin")
            (is (= "admin" (:role (auth/validate-token token))))
            (swap! auth/state assoc-in [:users :testuser :role] "user")))

        (testing "Update user quota revokes all existing sessions"
          (let [token (auth/issue-token (auth/authenticate "testuser" "password123"))
                new-quotas {:portForwards 10 :reverseProxies 5 :containers 4 :cpuLimit 4.5 :memLimit "4g"}
                updated (auth/update-quota! "testuser" new-quotas)]
            (is (= (:portForwards updated) 10))
            (is (= (:cpuLimit updated) 4.5))
            (is (= (:memLimit updated) "4g"))
            (is (= (get-in (auth/get-user "testuser") [:quotas :portForwards]) 10))
            (is (nil? (auth/validate-token token)))))

        (testing "Password changes revoke sessions and bump auth version"
          (let [before (:auth-version (auth/get-user "testuser"))
                token (auth/issue-token (auth/authenticate "testuser" "password123"))]
            (auth/change-password! "testuser" "new-password123")
            (is (> (:auth-version (auth/get-user "testuser")) before))
            (is (nil? (auth/validate-token token)))
            (is (auth/authenticate "testuser" "new-password123"))))

        (testing "Delete user"
          (auth/delete-user! "testuser" "admin")
          (is (empty? (auth/get-all-users)))))

      (finally
        (doseq [file (reverse (file-seq directory))]
          (.delete file))))))

(deftest persistence-failure-does-not-publish-user-mutations
  (let [original-users {:alice {:password "hash"
                                :authVersion 1
                                :role "user"
                                :quotas {:portForwards 1}}}
        session {:username "alice" :auth-version 1 :expires Long/MAX_VALUE}]
    (reset! auth/state {:users original-users})
    (reset! auth/token-store {"token" session})
    (with-redefs [db/write-json (fn [& _]
                                  (throw (ex-info "磁盘不可写" {:type ::db/write-failed})))
                  auth/hash-password (constantly "new-hash")]
      (is (thrown? clojure.lang.ExceptionInfo
                   (auth/create-user! "bob" "password123")))
      (is (= original-users (:users @auth/state)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (auth/change-password! "alice" "password123")))
      (is (= original-users (:users @auth/state)))
      (is (contains? @auth/token-store "token"))
      (is (thrown? clojure.lang.ExceptionInfo
                   (auth/update-quota! "alice" {:portForwards 2})))
      (is (= original-users (:users @auth/state)))
      (is (contains? @auth/token-store "token")))))

(deftest concurrent-user-creation-keeps-both-updates
  (let [persisted (atom nil)]
    (reset! auth/state {:users {}})
    (with-redefs [db/write-json (fn [_ data] (reset! persisted data))
                  auth/hash-password (constantly "hash")]
      (let [alice (future (auth/create-user! "alice" "password123"))
            bob (future (auth/create-user! "bob" "password123"))]
        @alice
        @bob)
      (is (= #{:alice :bob} (set (keys (:users @auth/state)))))
      (is (= (:users @auth/state) (:users @persisted))))))
