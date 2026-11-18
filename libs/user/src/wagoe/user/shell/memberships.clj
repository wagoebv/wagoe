(ns wagoe.user.shell.memberships
  "What the tenant module says of a user's memberships, when it is on (BOU-1008).
   Boot refuses a membership service that cannot say it."
  (:require [wagoe.core.http.access :as access]
            [wagoe.tenant.ports :as tenant-ports]))

(defn admin-anywhere?
  "Whether the user administers some tenant through a membership."
  [membership-service user-id]
  (boolean (when membership-service
             (some access/membership-admin? (tenant-ports/memberships-of-user membership-service user-id)))))

(defn elsewhere?
  "Whether the user is a member of a tenant other than their own."
  [membership-service {:keys [id tenant-id]}]
  (boolean (when membership-service
             (some #(not (access/same-id? (:tenant-id %) tenant-id))
                   (tenant-ports/memberships-of-user membership-service id)))))
