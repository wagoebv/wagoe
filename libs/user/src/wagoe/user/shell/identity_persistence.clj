(ns wagoe.user.shell.identity-persistence
  "IUserIdentityRepository on the `user_identities` table, which
   `initialize-user-schema!` creates."
  (:require [wagoe.core.utils.type-conversion :as type-conversion]
            [wagoe.platform.database :as db]
            [wagoe.user.ports :as ports])
  (:import (java.time Instant)
           (java.util UUID)))

(defn- ->db [identity]
  {:id            (type-conversion/uuid->string (:id identity))
   :user_id       (type-conversion/uuid->string (:user-id identity))
   :provider      (:provider identity)
   :issuer        (:issuer identity)
   :subject       (:subject identity)
   :email         (:email identity)
   :created_at    (:created-at identity)
   :last_login_at (:last-login-at identity)})

(defn- db-> [row]
  (when row
    (-> (select-keys row [:id :user-id :provider :issuer :subject :email
                          :created-at :last-login-at])
        (update :id type-conversion/string->uuid)
        (update :user-id type-conversion/string->uuid)
        (update :created-at type-conversion/string->instant)
        (update :last-login-at type-conversion/string->instant))))

(defrecord DatabaseUserIdentityRepository [ctx]
  ports/IUserIdentityRepository

  (find-identity [_ issuer subject]
    (db-> (db/execute-one! ctx {:select [:*]
                                :from   [:user_identities]
                                :where  [:and [:= :issuer issuer] [:= :subject subject]]})))

  (find-identities-by-user [_ user-id]
    (mapv db-> (db/execute-query! ctx {:select   [:*]
                                       :from     [:user_identities]
                                       :where    [:= :user_id (type-conversion/uuid->string user-id)]
                                       :order-by [[:created_at :asc]]})))

  (link-identity [_ identity]
    (let [stored (assoc identity
                        :id (UUID/randomUUID)
                        :created-at (Instant/now)
                        :last-login-at nil)]
      (db/execute-update! ctx {:insert-into :user_identities :values [(->db stored)]})
      stored))

  (touch-identity [_ issuer subject at]
    (pos? (db/execute-update! ctx {:update :user_identities
                                   :set    {:last_login_at at}
                                   :where  [:and [:= :issuer issuer] [:= :subject subject]]})))

  (unlink-identity [_ issuer subject]
    (pos? (db/execute-update! ctx {:delete-from :user_identities
                                   :where       [:and [:= :issuer issuer] [:= :subject subject]]}))))

(defn create-identity-repository [ctx]
  (->DatabaseUserIdentityRepository ctx))
