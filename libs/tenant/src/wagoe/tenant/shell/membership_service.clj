(ns wagoe.tenant.shell.membership-service
  (:require [wagoe.tenant.shell.time :as time]
            [wagoe.tenant.core.membership :as membership-core]
            [wagoe.tenant.ports :as ports]
            [wagoe.platform.shell.service-interceptors :as service-interceptors])
  (:import (java.util UUID)))

(defn- generate-membership-id []
  (UUID/randomUUID))

(def ^:private page-size 500)

(defn all-pages
  "Every row `(read {:limit :offset})` returns, a page at a time, each once: a
   row inserted mid-read shifts the offsets and repeats one already read."
  [read]
  (loop [offset 0 seen #{} acc []]
    (let [page (read {:limit page-size :offset offset})
          acc  (into acc (remove (comp seen :id)) page)
          seen (into seen (map :id) page)]
      (if (< (count page) page-size)
        acc
        (recur (+ offset page-size) seen acc)))))

(defn read-memberships
  "The tenant's memberships as `find-memberships-after` gives them. A repository
   without it, an app's own on the stable port, is read by offset pages."
  [repository tenant-id {:keys [after-id limit status] :as options}]
  (if (satisfies? ports/ITenantMembershipsByKeyset repository)
    (ports/find-memberships-after repository tenant-id options)
    (cond->> (->> (all-pages #(ports/find-memberships-by-tenant repository tenant-id (assoc % :status status)))
                  ;; :status is not part of the stable port, so an app's own may ignore it
                  (filter #(or (nil? status) (= status (:status %))))
                  (sort-by (comp str :id))
                  (drop-while #(and after-id (<= (compare (str (:id %)) (str after-id)) 0))))
      limit (take limit)
      true  vec)))

(defn- active-admin-memberships
  "Every one: a tenant with more than a page of members once read only its
   newest 250, and a last-admin check could miss the admins (BOU-1090)."
  [membership-repository tenant-id]
  (filter #(= :admin (:role %))
          (read-memberships membership-repository tenant-id {:status :active})))

(defrecord MembershipService [membership-repository logger metrics-emitter error-reporter]
  ports/ITenantMembershipService

  (invite-user [_ tenant-id user-id role]
    (service-interceptors/execute-service-operation
     :invite-user
     {:tenant-id tenant-id :user-id user-id :role role}
     (fn [{:keys [params]}]
       (let [{:keys [tenant-id user-id role]} params]
         (when (ports/membership-exists? membership-repository user-id tenant-id)
           (throw (ex-info "Membership already exists for this user in tenant"
                           {:type      :conflict
                            :user-id   user-id
                            :tenant-id tenant-id})))
         (let [now           (time/now)
               membership-id (generate-membership-id)
               membership    (membership-core/prepare-invitation*
                              membership-id user-id tenant-id role now)]
           (ports/create-membership membership-repository membership))))
     {:logger          logger
      :metrics-emitter metrics-emitter
      :error-reporter  error-reporter}))

  (bootstrap-open? [_ tenant-id]
    (service-interceptors/execute-service-operation
     :bootstrap-open?
     {:tenant-id tenant-id}
     (fn [{:keys [params]}]
       (empty? (active-admin-memberships membership-repository (:tenant-id params))))
     {:logger          logger
      :metrics-emitter metrics-emitter
      :error-reporter  error-reporter}))

  (bootstrap-first-member [_ tenant-id user-id role]
    (service-interceptors/execute-service-operation
     :bootstrap-first-member
     {:tenant-id tenant-id :user-id user-id :role role}
     (fn [{:keys [params]}]
       (let [{:keys [tenant-id user-id role]} params]
         (when-not (.bootstrap-open? ^wagoe.tenant.ports.ITenantMembershipService _ tenant-id)
           (throw (ex-info "Tenant already has an active admin"
                           {:type      :conflict
                            :tenant-id tenant-id})))
         (when (ports/membership-exists? membership-repository user-id tenant-id)
           (throw (ex-info "Membership already exists for this user in tenant"
                           {:type      :conflict
                            :user-id   user-id
                            :tenant-id tenant-id})))
         (let [now           (time/now)
               membership-id (generate-membership-id)
               membership    (membership-core/prepare-active-membership*
                              membership-id user-id tenant-id role now)]
           (ports/create-membership membership-repository membership))))
     {:logger          logger
      :metrics-emitter metrics-emitter
      :error-reporter  error-reporter}))

  (accept-invitation [_ membership-id]
    (service-interceptors/execute-service-operation
     :accept-invitation
     {:membership-id membership-id}
     (fn [{:keys [params]}]
       (let [membership (ports/find-membership-by-id membership-repository (:membership-id params))]
         (when-not membership
           (throw (ex-info "Membership not found"
                           {:type          :not-found
                            :membership-id (:membership-id params)})))
         (when-not (= :invited (:status membership))
           (throw (ex-info "Membership is not in invited status"
                           {:type   :validation-error
                            :status (:status membership)})))
         (let [now     (time/now)
               updated (membership-core/accept-invitation membership now)]
           (ports/update-membership membership-repository updated)
           updated)))
     {:logger          logger
      :metrics-emitter metrics-emitter
      :error-reporter  error-reporter}))

  (update-member-role [_ membership-id role]
    (service-interceptors/execute-service-operation
     :update-member-role
     {:membership-id membership-id :role role}
     (fn [{:keys [params]}]
       (let [membership (ports/find-membership-by-id membership-repository (:membership-id params))]
         (when-not membership
           (throw (ex-info "Membership not found"
                           {:type          :not-found
                            :membership-id (:membership-id params)})))
         (let [now     (time/now)
               updated (membership-core/update-role membership (:role params) now)]
           (ports/update-membership membership-repository updated)
           updated)))
     {:logger          logger
      :metrics-emitter metrics-emitter
      :error-reporter  error-reporter}))

  (suspend-member [_ membership-id]
    (service-interceptors/execute-service-operation
     :suspend-member
     {:membership-id membership-id}
     (fn [{:keys [params]}]
       (let [membership (ports/find-membership-by-id membership-repository (:membership-id params))]
         (when-not membership
           (throw (ex-info "Membership not found"
                           {:type          :not-found
                            :membership-id (:membership-id params)})))
         (when (= :revoked (:status membership))
           (throw (ex-info "Cannot suspend a revoked membership"
                           {:type          :validation-error
                            :membership-id (:membership-id params)})))
         (let [now     (time/now)
               updated (membership-core/suspend-membership membership now)]
           (ports/update-membership membership-repository updated)
           updated)))
     {:logger          logger
      :metrics-emitter metrics-emitter
      :error-reporter  error-reporter}))

  (revoke-member [_ membership-id]
    (service-interceptors/execute-service-operation
     :revoke-member
     {:membership-id membership-id}
     (fn [{:keys [params]}]
       (let [membership (ports/find-membership-by-id membership-repository (:membership-id params))]
         (when-not membership
           (throw (ex-info "Membership not found"
                           {:type          :not-found
                            :membership-id (:membership-id params)})))
         ;; Deleted, not kept as :revoked (BOU-611; see shell.user-foreign-keys).
         (ports/delete-membership membership-repository (:id membership))
         (membership-core/revoke-membership membership (time/now))))
     {:logger          logger
      :metrics-emitter metrics-emitter
      :error-reporter  error-reporter}))

  (get-membership [_ membership-id]
    (service-interceptors/execute-service-operation
     :get-membership
     {:membership-id membership-id}
     (fn [{:keys [params]}]
       (let [membership (ports/find-membership-by-id membership-repository (:membership-id params))]
         (when-not membership
           (throw (ex-info "Membership not found"
                           {:type          :not-found
                            :membership-id (:membership-id params)})))
         membership))
     {:logger          logger
      :metrics-emitter metrics-emitter
      :error-reporter  error-reporter}))

  (get-active-membership [_ user-id tenant-id]
    (service-interceptors/execute-service-operation
     :get-active-membership
     {:user-id user-id :tenant-id tenant-id}
     (fn [{:keys [params]}]
       (let [membership (ports/find-membership-by-user-and-tenant
                         membership-repository
                         (:user-id params)
                         (:tenant-id params))]
         (when (and membership (membership-core/active-member? membership))
           membership)))
     {:logger          logger
      :metrics-emitter metrics-emitter
      :error-reporter  error-reporter}))

  (list-tenant-members [_ tenant-id options]
    (service-interceptors/execute-service-operation
     :list-tenant-members
     {:tenant-id tenant-id :options options}
     (fn [{:keys [params]}]
       (ports/find-memberships-by-tenant
        membership-repository
        (:tenant-id params)
        (:options params)))
     {:logger          logger
      :metrics-emitter metrics-emitter
      :error-reporter  error-reporter}))

  ports/ITenantMembers
  (tenant-members [_ tenant-id options]
    (service-interceptors/execute-service-operation
     :tenant-members
     {:tenant-id tenant-id :options options}
     (fn [{:keys [params]}]
       (read-memberships membership-repository (:tenant-id params) (:options params)))
     {:logger          logger
      :metrics-emitter metrics-emitter
      :error-reporter  error-reporter}))

  ports/ITenantMembershipsOfUser
  (memberships-of-user [_ user-id]
    (into [] (remove #(= :revoked (:status %))) (ports/find-memberships-by-user membership-repository user-id))))

(defn create-membership-service
  [membership-repository logger metrics-emitter error-reporter]
  (->MembershipService membership-repository logger metrics-emitter error-reporter))
