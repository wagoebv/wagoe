(ns wagoe.tenant.shell.membership-middleware
  (:require [wagoe.tenant.ports :as ports]))

(defn wrap-tenant-membership
  "Ring middleware that enriches the request with :tenant-membership.

   Looks up the active membership for the current user+tenant pair (from
   :user and :tenant keys already set on the request by upstream middleware).
   Sets :tenant-membership to nil when no active membership is found or when
   either :user or :tenant is absent.

   Args:
     membership-service - ITenantMembershipService implementation
     handler            - next Ring handler

   Returns:
     Ring handler."
  [membership-service handler]
  (fn [request]
    (let [user-id   (get-in request [:user :id])
          tenant-id (get-in request [:tenant :id])]
      (if (and user-id tenant-id)
        (let [membership (ports/get-active-membership membership-service user-id tenant-id)]
          (handler (assoc request :tenant-membership membership)))
        (handler request)))))
