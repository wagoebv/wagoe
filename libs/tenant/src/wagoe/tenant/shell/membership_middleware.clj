(ns wagoe.tenant.shell.membership-middleware
  (:require [cheshire.core :as json]
            [wagoe.core.http.access :as access]
            [wagoe.core.http.errors :as errors]
            [wagoe.tenant.ports :as ports]))

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

(defn wrap-tenant-scope
  "Refuse a signed-in caller who may not use the schema the request runs in
   (`in-tenant-scope?`): the tenant comes from a header or host they chose (BOU-982)."
  [handler]
  (fn [request]
    (if (and (get-in request [:user :id]) (not (access/in-tenant-scope? request)))
      ;; Encoded here, as the tenant middleware's refusals are: this may sit
      ;; outside content negotiation
      {:status  403
       :headers {"Content-Type" "application/json"}
       :body    (json/generate-string
                 (errors/body :forbidden "Not a member of this tenant"
                              {:correlation-id (:correlation-id request)}))}
      (handler request))))
