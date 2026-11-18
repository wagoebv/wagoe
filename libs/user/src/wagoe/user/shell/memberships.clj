(ns wagoe.user.shell.memberships
  "What the tenant module says of a user's memberships, when it is on and
   answers ITenantMembershipsOfUser (BOU-1008)."
  (:require [wagoe.core.http.access :as access]
            [wagoe.tenant.ports :as tenant-ports]))

(defn of-user
  "The user's memberships in every tenant but those revoked: an invited one is
   theirs once they accept it. None without the module."
  [membership-service user-id]
  (when (satisfies? tenant-ports/ITenantMembershipsOfUser membership-service)
    (remove #(= :revoked (keyword (:status %))) (tenant-ports/memberships-of-user membership-service user-id))))

(defn admin-anywhere?
  "Whether the user administers some tenant through a membership."
  [membership-service user-id]
  (boolean (some access/membership-admin? (of-user membership-service user-id))))

(defn elsewhere?
  "Whether the user is a member of a tenant other than their own."
  [membership-service {:keys [id tenant-id]}]
  (boolean (some #(not= (str (:tenant-id %)) (str tenant-id)) (of-user membership-service id))))
