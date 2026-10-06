(ns culvert.authorization
  (:require [clojure.string :as str]))

(defn actor
  "Builds the canonical domain actor used during handler migration."
  [username admin?]
  {:username username
   :admin? (boolean admin?)})

(defn require-actor!
  "Returns a valid actor or throws. Anonymous and malformed actors are denied."
  [actor]
  (when-not (and (map? actor)
                 (string? (:username actor))
                 (not (str/blank? (:username actor)))
                 (boolean? (:admin? actor)))
    (throw (ex-info "Authentication required" {:type ::unauthenticated})))
  actor)

(defn legacy-owner?
  [owner legacy-user]
  (or (nil? owner)
      (and (string? owner) (str/blank? owner))
      (= owner legacy-user)))

(defn authorized?
  "Admins may access every resource. Non-admins may access only explicitly owned,
   non-legacy resources."
  [actor owner legacy-user]
  (let [{:keys [username admin?]} (require-actor! actor)]
    (or admin?
        (and (not (legacy-owner? owner legacy-user))
             (= owner username)))))

(defn require-resource-access!
  [actor owner legacy-user permission-message]
  (when-not (authorized? actor owner legacy-user)
    (throw (ex-info permission-message
                    {:type ::forbidden
                     :username (:username actor)})))
  actor)

(defn require-admin!
  "要求 actor 是管理员，否则抛 ::admin-required。

   管理员专属能力没有资源所有者可以做后备校验（任意目标代理、宿主机容器会话等），
   必须在领域入口校验，Handler 的角色判断只是第二道。"
  ([actor] (require-admin! actor "仅管理员可执行此操作"))
  ([actor permission-message]
   (let [{:keys [username admin?]} (require-actor! actor)]
     (when-not admin?
       (throw (ex-info permission-message
                       {:type ::admin-required
                        :username username})))
     actor)))
