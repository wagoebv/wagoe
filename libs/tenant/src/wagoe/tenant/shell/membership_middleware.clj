(ns wagoe.tenant.shell.membership-middleware
  (:require [cheshire.core :as json]
            [wagoe.core.http.access :as access]
            [wagoe.core.http.errors :as errors]
            [wagoe.tenant.ports :as ports]))

(defn- forbidden
  "403 with `message`, encoded here, as the tenant middleware's refusals are:
   this may sit outside content negotiation."
  [request message]
  {:status  403
   :headers {"Content-Type" "application/json"}
   :body    (json/generate-string
             (errors/body :forbidden message {:correlation-id (:correlation-id request)}))})

(defn- refused [request message]
  (access/refused-view (forbidden request message)))

(defn- viewing-refusal
  "While an administrator views the app as another user (`:viewing-as`, set
   by the user module, BOU-868): a 403 where that user is an admin by
   membership, or where the administrator themselves may not be (BOU-1004).
   nil otherwise."
  [membership-service request]
  (when-let [admin (get-in request [:viewing-as :admin])]
    (cond
      (access/membership-admin? (:tenant-membership request))
      (refused request "An administrator is not viewed as")

      ;; Asked only when their own field or role does not settle it
      (not (or (access/platform-admin? admin)
               (access/same-id? (:tenant-id admin) (get-in request [:tenant :id]))
               (ports/get-active-membership membership-service (:id admin) (get-in request [:tenant :id]))))
      (refused request "A view stays within the tenants you reach"))))

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
        (let [membership (ports/get-active-membership membership-service user-id tenant-id)
              request    (assoc request :tenant-membership membership)]
          (or (viewing-refusal membership-service request)
              (handler request)))
        (handler request)))))

(defn wrap-tenant-scope
  "Refuse a signed-in caller who may not use the schema the request runs in
   (`in-tenant-scope?`): the tenant comes from a header or host they chose (BOU-982)."
  [handler]
  (fn [request]
    (if (and (get-in request [:user :id]) (not (access/in-tenant-scope? request)))
      (forbidden request "Not a member of this tenant")
      (handler request))))
