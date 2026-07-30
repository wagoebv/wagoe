(ns wagoe.tenant.shell.invite-service
  (:require [wagoe.platform.shell.adapters.database.common.core :as db]
            [wagoe.platform.shell.service-interceptors :as service-interceptors]
            [wagoe.tenant.core.invite :as invite-core]
            [wagoe.tenant.core.membership :as membership-core]
            [wagoe.tenant.shell.invite-persistence :as invite-persistence]
            [wagoe.tenant.ports :as ports])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.time Instant)
           (java.util UUID)))

(defn- current-timestamp []
  (Instant/now))

(defn- generate-invite-token []
  (let [random (SecureRandom.)
        token-bytes (byte-array 32)]
    (.nextBytes random token-bytes)
    (-> (java.util.Base64/getUrlEncoder)
        (.withoutPadding)
        (.encodeToString token-bytes))))

(defn- generate-invite-id []
  (UUID/randomUUID))

(defn- generate-membership-id []
  (UUID/randomUUID))

(defn- sha256 [value]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (.digest digest (.getBytes (str value) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn- default-expiry [now]
  (.plusSeconds ^Instant now (* 7 24 60 60)))

(defn- shared-db-context
  [invite-repository membership-repository]
  (let [invite-ctx (:ctx invite-repository)
        membership-ctx (:ctx membership-repository)]
    (when (= invite-ctx membership-ctx)
      invite-ctx)))

(defn- with-shared-transaction
  [invite-repository membership-repository tx-context f]
  (if tx-context
    (f (assoc invite-repository :ctx tx-context)
       (assoc membership-repository :ctx tx-context)
       tx-context)
    (if-let [ctx (shared-db-context invite-repository membership-repository)]
      (db/with-transaction [tx ctx]
        (f (assoc invite-repository :ctx tx)
           (assoc membership-repository :ctx tx)
           tx))
      (f invite-repository membership-repository nil))))

(defn- locked-invite-by-token
  [invite-repository token]
  (if-let [ctx (:ctx invite-repository)]
    (or (invite-persistence/lock-pending-invite-by-token-hash ctx (sha256 token))
        (throw (ex-info "Invite not found" {:type :not-found})))
    (let [invite (ports/find-pending-invite-by-token-hash invite-repository (sha256 token))]
      (when-not invite
        (throw (ex-info "Invite not found" {:type :not-found})))
      invite)))

(defn- validate-invite-for-acceptance
  [invite]
  (when (invite-core/expired? invite (current-timestamp))
    (throw (ex-info "Invite has expired"
                    {:type :validation-error
                     :invite-id (:id invite)})))
  invite)

(defn- service-system [service]
  {:logger          (:logger service)
   :metrics-emitter (:metrics-emitter service)
   :error-reporter  (:error-reporter service)})

(defrecord InviteService [invite-repository membership-repository logger metrics-emitter error-reporter]
  ports/ITenantInviteService

  (invite-external-member [this tenant-id email role options]
    (service-interceptors/execute-service-operation
     :invite-external-member
     {:tenant-id tenant-id :email email :role role :options options}
     (fn [{:keys [params]}]
       (let [{:keys [tenant-id email role options]} params
             normalized-email (invite-core/normalize-email email)]
         (when-let [existing (ports/find-pending-invite-by-email-and-tenant
                              invite-repository tenant-id normalized-email)]
           (when-not (invite-core/expired? existing (current-timestamp))
             (throw (ex-info "Pending invite already exists for this email in tenant"
                             {:type :conflict
                              :tenant-id tenant-id
                              :email normalized-email}))))
         (let [now (current-timestamp)
               invite-id (generate-invite-id)
               raw-token (generate-invite-token)
               invite (invite-core/prepare-invite*
                       {:invite-id invite-id
                        :tenant-id tenant-id
                        :email normalized-email
                        :role role
                        :token-hash (sha256 raw-token)
                        :expires-at (or (:expires-at options) (default-expiry now))
                        :metadata (:metadata options)}
                       now)]
           (assoc (ports/create-invite invite-repository invite)
                  :invite-token raw-token))))
     (service-system this)))

  (get-external-invite [this invite-id]
    (service-interceptors/execute-service-operation
     :get-external-invite
     {:invite-id invite-id}
     (fn [{:keys [params]}]
       (let [invite (ports/find-invite-by-id invite-repository (:invite-id params))]
         (when-not invite
           (throw (ex-info "Invite not found"
                           {:type :not-found
                            :invite-id (:invite-id params)})))
         invite))
     (service-system this)))

  (get-external-invite-by-token [this token]
    (service-interceptors/execute-service-operation
     :get-external-invite-by-token
     {:token token}
     (fn [_]
       (let [invite (ports/find-pending-invite-by-token-hash invite-repository (sha256 token))]
         (when-not invite
           (throw (ex-info "Invite not found" {:type :not-found})))
         (when (invite-core/expired? invite (current-timestamp))
           (throw (ex-info "Invite has expired"
                           {:type :validation-error
                            :invite-id (:id invite)})))
         invite))
     (service-system this)))

  (accept-external-invite [this token accepted-by-user-id]
    (service-interceptors/execute-service-operation
     :accept-external-invite
     {:token token :accepted-by-user-id accepted-by-user-id}
     (fn [_]
         (let [{:keys [invite membership]} (ports/accept-external-invite! this {:token token
                                                                           :accepted-by-user-id accepted-by-user-id})]
         {:invite invite :membership membership}))
     (service-system this)))

  (revoke-external-invite [this invite-id]
    (service-interceptors/execute-service-operation
     :revoke-external-invite
     {:invite-id invite-id}
     (fn [{:keys [params]}]
       (let [invite (ports/find-invite-by-id invite-repository (:invite-id params))]
         (when-not invite
           (throw (ex-info "Invite not found"
                           {:type :not-found
                            :invite-id (:invite-id params)})))
         (when-not (= :pending (:status invite))
           (throw (ex-info "Only pending invites can be revoked"
                           {:type :validation-error
                            :invite-id (:invite-id params)
                            :status (:status invite)})))
         (let [updated (invite-core/revoke-invite invite (current-timestamp))]
           (ports/update-invite invite-repository updated)
           updated)))
     (service-system this)))

  (list-tenant-invites [this tenant-id options]
    (service-interceptors/execute-service-operation
     :list-tenant-invites
     {:tenant-id tenant-id :options options}
     (fn [{:keys [params]}]
       (ports/find-invites-by-tenant invite-repository
                                     (:tenant-id params)
                                     (:options params)))
     (service-system this)))

  ports/ITenantInviteAcceptanceService

  (load-external-invite-for-acceptance [this {:keys [token tx-context]}]
    (service-interceptors/execute-service-operation
     :load-external-invite-for-acceptance
     {:token token :tx-context tx-context}
     (fn [_]
       (with-shared-transaction
         invite-repository
         membership-repository
         tx-context
         (fn [invite-repository _membership-repository _tx-context]
           (-> (locked-invite-by-token invite-repository token)
               validate-invite-for-acceptance))))
     (service-system this)))

  (accept-external-invite! [this {:keys [token accepted-by-user-id tx-context hooks]}]
    (service-interceptors/execute-service-operation
     :accept-external-invite!
     {:token token :accepted-by-user-id accepted-by-user-id :tx-context tx-context}
     (fn [_]
       (with-shared-transaction
         invite-repository
         membership-repository
         tx-context
         (fn [invite-repository membership-repository tx-context]
           (let [invite (-> (locked-invite-by-token invite-repository token)
                            validate-invite-for-acceptance)
                 now (current-timestamp)]
             (when (ports/membership-exists? membership-repository
                                             accepted-by-user-id
                                             (:tenant-id invite))
               (throw (ex-info "Membership already exists for this user in tenant"
                               {:type :conflict
                                :tenant-id (:tenant-id invite)
                                :user-id accepted-by-user-id
                                :invite-id (:id invite)})))
             (let [membership-id (generate-membership-id)
                   membership (membership-core/prepare-active-membership*
                               membership-id
                               accepted-by-user-id
                               (:tenant-id invite)
                               (:role invite)
                               now)
                   updated-invite (invite-core/accept-invite invite accepted-by-user-id now)
                   after-accept-tx (get hooks :after-accept-tx)]
               (ports/create-membership membership-repository membership)
               (ports/update-invite invite-repository updated-invite)
               {:invite updated-invite
                :membership membership
                :effects {:after-accept-tx (when after-accept-tx
                                              (after-accept-tx {:invite updated-invite
                                                                :membership membership
                                                                :tx-context tx-context}))}})))))
     (service-system this))))

(defn create-invite-service
  [invite-repository membership-repository logger metrics-emitter error-reporter]
  (->InviteService invite-repository
                   membership-repository
                   logger
                   metrics-emitter
                   error-reporter))
