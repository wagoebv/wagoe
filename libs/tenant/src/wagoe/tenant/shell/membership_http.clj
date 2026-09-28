(ns wagoe.tenant.shell.membership-http
  "HTTP routes and handlers for tenant membership management.

   Provides REST API endpoints for:
   - Inviting users to a tenant (POST /api/tenants/:tenant-id/memberships)
   - Listing tenant members (GET /api/tenants/:tenant-id/memberships)
   - Getting a membership (GET /api/tenants/:tenant-id/memberships/:id)
   - Updating a membership role/status (PUT /api/tenants/:tenant-id/memberships/:id)
   - Revoking a membership (DELETE /api/tenants/:tenant-id/memberships/:id)
   - Accepting an invitation (POST /api/memberships/:id/accept)

   Reading a tenant's memberships takes an active membership of that tenant;
   changing them takes its admin role. A global admin may do both. Only the
   invitee may accept an invitation (BOU-568)."
  (:require [wagoe.platform.core.http.access :as access]
            [wagoe.platform.core.http.errors :as errors]
            [wagoe.tenant.ports :as membership-ports]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Utilities
;; =============================================================================

(defn- json-response
  ([data] (json-response 200 data))
  ([status data]
   {:status  status
    :headers {"Content-Type" "application/json"}
    :body    (json/generate-string data)}))

(defn- error-response
  [status message & [details]]
  (json-response status (errors/body (errors/status->type status) message
                                     {:details details})))

(defn- parse-uuid-safe
  [s]
  (try
    (java.util.UUID/fromString s)
    (catch IllegalArgumentException _
      nil)))

(defn- require-tenant-role
  "Middleware: the caller is a global admin, or an active member of the path's
   tenant whose role is in `roles` (any role when `roles` is nil).

   An unparseable tenant id passes through, and the handler answers 400."
  [membership-service roles]
  (fn [handler]
    (fn [request]
      (let [tenant-id (some-> (get-in request [:path-params :tenant-id]) parse-uuid-safe)
            user-id   (get-in request [:user :id])
            member    (when (and tenant-id user-id)
                        (membership-ports/get-active-membership
                         membership-service user-id tenant-id))]
        (if (or (nil? tenant-id)
                (access/admin? request)
                (and member (or (nil? roles) (contains? roles (:role member)))))
          (handler request)
          (access/forbidden-response
           (if roles "Tenant admin role required" "Tenant membership required")
           request (access/correlation-id request (str (random-uuid)))))))))

(defn- membership-in-tenant
  "The membership `membership-id`, or a :not-found throw when it belongs to a
   tenant other than `tenant-id` — otherwise an admin of one tenant could act
   on another's memberships by naming their own tenant in the path."
  [membership-service tenant-id membership-id]
  (let [membership (membership-ports/get-membership membership-service membership-id)]
    (if (= tenant-id (:tenant-id membership))
      membership
      (throw (ex-info "Membership not found" {:type :not-found})))))

;; =============================================================================
;; Handlers
;; =============================================================================

(defn invite-user-handler
  "POST /api/tenants/:tenant-id/memberships
   Body: {:user-id UUID :role keyword}"
  [membership-service]
  (fn [request]
    (try
      (let [tenant-id-str (get-in request [:path-params :tenant-id])
            tenant-id     (parse-uuid-safe tenant-id-str)]
        (if-not tenant-id
          (error-response 400 "Invalid tenant ID format")
          (let [body    (or (:body-params request)
                            (json/parse-string (slurp (:body request)) true))
                user-id (some-> (:userId body) parse-uuid-safe)
                role    (some-> (:role body) keyword)]
            (cond
              (nil? user-id)
              (error-response 400 "Invalid or missing userId")

              (not (#{:admin :member :viewer :contractor} role))
              (error-response 400 "Invalid role — must be admin, member, viewer, or contractor")

              :else
              (let [membership (membership-ports/invite-user membership-service tenant-id user-id role)]
                (json-response 201 membership))))))
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [type]} (ex-data e)]
          (case type
            :conflict    (error-response 409 (.getMessage e))
            :not-found   (error-response 404 (.getMessage e))
            (do
              (log/error e "Failed to invite user")
              (error-response 500 "Internal server error")))))
      (catch Exception e
        (log/error e "Failed to invite user")
        (error-response 500 "Internal server error")))))

(defn list-members-handler
  "GET /api/tenants/:tenant-id/memberships"
  [membership-service]
  (fn [request]
    (try
      (let [tenant-id-str (get-in request [:path-params :tenant-id])
            tenant-id     (parse-uuid-safe tenant-id-str)]
        (if-not tenant-id
          (error-response 400 "Invalid tenant ID format")
          (let [params  (:params request)
                limit   (min (or (some-> (:limit params) parse-long) 50) 100)
                offset  (or (some-> (:offset params) parse-long) 0)
                status  (some-> (:status params) keyword)
                options (cond-> {:limit limit :offset offset}
                          status (assoc :status status))
                members (membership-ports/list-tenant-members membership-service tenant-id options)]
            (json-response 200 members))))
      (catch Exception e
        (log/error e "Failed to list members")
        (error-response 500 "Internal server error")))))

(defn get-membership-handler
  "GET /api/tenants/:tenant-id/memberships/:id"
  [membership-service]
  (fn [request]
    (try
      (let [tenant-id-str     (get-in request [:path-params :tenant-id])
            membership-id-str (get-in request [:path-params :id])
            tenant-id         (parse-uuid-safe tenant-id-str)
            membership-id     (parse-uuid-safe membership-id-str)]
        (cond
          (nil? tenant-id)
          (error-response 400 "Invalid tenant ID format")

          (nil? membership-id)
          (error-response 400 "Invalid membership ID format")

          :else
          (json-response 200 (membership-in-tenant membership-service tenant-id membership-id))))
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [type]} (ex-data e)]
          (if (= type :not-found)
            (error-response 404 "Membership not found")
            (do
              (log/error e "Failed to get membership")
              (error-response 500 "Internal server error")))))
      (catch Exception e
        (log/error e "Failed to get membership")
        (error-response 500 "Internal server error")))))

(defn update-membership-handler
  "PUT /api/tenants/:tenant-id/memberships/:id
   Body: {:role keyword, :status keyword} (both optional)"
  [membership-service]
  (fn [request]
    (try
      (let [tenant-id-str     (get-in request [:path-params :tenant-id])
            membership-id-str (get-in request [:path-params :id])
            tenant-id         (parse-uuid-safe tenant-id-str)
            membership-id     (parse-uuid-safe membership-id-str)]
        (cond
          (nil? tenant-id)
          (error-response 400 "Invalid tenant ID format")

          (nil? membership-id)
          (error-response 400 "Invalid membership ID format")

          :else
          (let [body   (or (:body-params request)
                           (json/parse-string (slurp (:body request)) true))
                role   (some-> (:role body) keyword)
                status (some-> (:status body) keyword)]
            (cond
              (and role (not (#{:admin :member :viewer :contractor} role)))
              (error-response 400 "Invalid role")

              (and status (not (#{:suspended :revoked} status)))
              (error-response 400 "Invalid status — must be suspended or revoked")

              :else
              (let [_ (membership-in-tenant membership-service tenant-id membership-id)
                    result
                    (cond
                      role   (membership-ports/update-member-role membership-service membership-id role)
                      status (case status
                               :suspended (membership-ports/suspend-member membership-service membership-id)
                               :revoked   (membership-ports/revoke-member  membership-service membership-id))
                      :else  (membership-ports/get-membership membership-service membership-id))]
                (json-response 200 result))))))
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [type]} (ex-data e)]
          (case type
            :not-found       (error-response 404 "Membership not found")
            :validation-error (error-response 400 (.getMessage e))
            (do
              (log/error e "Failed to update membership")
              (error-response 500 "Internal server error")))))
      (catch Exception e
        (log/error e "Failed to update membership")
        (error-response 500 "Internal server error")))))

(defn revoke-member-handler
  "DELETE /api/tenants/:tenant-id/memberships/:id"
  [membership-service]
  (fn [request]
    (try
      (let [tenant-id-str     (get-in request [:path-params :tenant-id])
            membership-id-str (get-in request [:path-params :id])
            tenant-id         (parse-uuid-safe tenant-id-str)
            membership-id     (parse-uuid-safe membership-id-str)]
        (cond
          (nil? tenant-id)
          (error-response 400 "Invalid tenant ID format")

          (nil? membership-id)
          (error-response 400 "Invalid membership ID format")

          :else
          (do
            (membership-in-tenant membership-service tenant-id membership-id)
            (membership-ports/revoke-member membership-service membership-id)
            (json-response 200 {:message "Membership revoked successfully"}))))
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [type]} (ex-data e)]
          (if (= type :not-found)
            (error-response 404 "Membership not found")
            (do
              (log/error e "Failed to revoke membership")
              (error-response 500 "Internal server error")))))
      (catch Exception e
        (log/error e "Failed to revoke membership")
        (error-response 500 "Internal server error")))))

(defn accept-invitation-handler
  "POST /api/memberships/:id/accept"
  [membership-service]
  (fn [request]
    (try
      (let [membership-id-str (get-in request [:path-params :id])
            membership-id     (parse-uuid-safe membership-id-str)]
        (if-not membership-id
          (error-response 400 "Invalid membership ID format")
          (let [invited (membership-ports/get-membership membership-service membership-id)]
            (if (= (get-in request [:user :id]) (:user-id invited))
              (json-response 200 (membership-ports/accept-invitation membership-service membership-id))
              (error-response 403 "Only the invited user may accept this invitation")))))
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [type]} (ex-data e)]
          (case type
            :not-found        (error-response 404 "Membership not found")
            :validation-error (error-response 400 (.getMessage e))
            (do
              (log/error e "Failed to accept invitation")
              (error-response 500 "Internal server error")))))
      (catch Exception e
        (log/error e "Failed to accept invitation")
        (error-response 500 "Internal server error")))))

;; =============================================================================
;; Routes
;; =============================================================================

(defn membership-routes
  "Membership's contribution to the route table: Reitit route data under
   :api, at paths relative to /api/v1."
  [membership-service]
  (let [member (require-tenant-role membership-service nil)
        admin  (require-tenant-role membership-service #{:admin})]
    {:api
     [["/tenants/:tenant-id/memberships"
       {:post {:handler     (invite-user-handler membership-service)
               :middleware  [admin]
               :summary     "Invite user to tenant"
               :tags        ["memberships"]
               :responses   {201 {:description "Membership created"}
                             400 {:description "Validation error"}
                             409 {:description "Membership already exists"}}}
        :get  {:handler     (list-members-handler membership-service)
               :middleware  [member]
               :summary     "List tenant members"
               :tags        ["memberships"]
               :responses   {200 {:description "List of memberships"}
                             400 {:description "Bad request"}}}}]

      ["/tenants/:tenant-id/memberships/:id"
       {:get    {:handler   (get-membership-handler membership-service)
                 :middleware [member]
                 :summary   "Get membership by ID"
                 :tags      ["memberships"]
                 :responses {200 {:description "Membership details"}
                             404 {:description "Membership not found"}}}
        :put    {:handler   (update-membership-handler membership-service)
                 :middleware [admin]
                 :summary   "Update membership role or status"
                 :tags      ["memberships"]
                 :responses {200 {:description "Membership updated"}
                             400 {:description "Validation error"}
                             404 {:description "Membership not found"}}}
        :delete {:handler   (revoke-member-handler membership-service)
                 :middleware [admin]
                 :summary   "Revoke membership"
                 :tags      ["memberships"]
                 :responses {200 {:description "Membership revoked"}
                             404 {:description "Membership not found"}}}}]

      ["/memberships/:id/accept"
       {:post {:handler   (accept-invitation-handler membership-service)
               :summary   "Accept membership invitation"
               :tags      ["memberships"]
               :responses {200 {:description "Invitation accepted"}
                           400 {:description "Invitation already accepted or invalid status"}
                           404 {:description "Membership not found"}}}}]]}))
