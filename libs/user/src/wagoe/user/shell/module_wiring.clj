(ns wagoe.user.shell.module-wiring
  "Integrant wiring for the user module.

  This namespace owns all Integrant init/halt methods for user-specific
  components so that shared system wiring does not depend directly on
  user shell namespaces."
  (:require [wagoe.user.shell.persistence :as user-persistence]
            [wagoe.user.shell.auth-persistence :as auth-persistence]
            [wagoe.user.shell.service :as user-service]
            [wagoe.user.shell.auth :as user-auth]
            [wagoe.user.shell.mfa :as user-mfa]
            [wagoe.user.shell.middleware :as user-middleware]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

;; =============================================================================
;; User Repository
;; =============================================================================

(defmethod ig/init-key :wagoe/user-repository
  [_ {:keys [ctx]}]
  (log/info "Initializing user repository")
  (let [repo (user-persistence/create-user-repository ctx)]
    (log/info "User repository initialized")
    repo))

(defmethod ig/halt-key! :wagoe/user-repository
  [_ _repo]
  (log/info "User repository halted (no cleanup needed)"))

;; =============================================================================
;; Auth User Repository
;; =============================================================================

(defmethod ig/init-key :wagoe/auth-user-repository
  [_ {:keys [ctx]}]
  (log/info "Initializing auth user repository")
  (let [repo (auth-persistence/create-auth-user-repository ctx)]
    (log/info "Auth user repository initialized")
    repo))

(defmethod ig/halt-key! :wagoe/auth-user-repository
  [_ _repo]
  (log/info "Auth user repository halted (no cleanup needed)"))

;; =============================================================================
;; Session Repository
;; =============================================================================

(defmethod ig/init-key :wagoe/session-repository
  [_ {:keys [ctx]}]
  (log/info "Initializing session repository")
  (let [repo (user-persistence/create-session-repository ctx)]
    (log/info "Session repository initialized")
    repo))

(defmethod ig/halt-key! :wagoe/session-repository
  [_ _repo]
  (log/info "Session repository halted (no cleanup needed)"))

;; =============================================================================
;; Audit Repository
;; =============================================================================

(defmethod ig/init-key :wagoe/audit-repository
  [_ {:keys [ctx pagination-config]}]
  (log/info "Initializing audit repository")
  (let [repo (user-persistence/create-audit-repository ctx pagination-config)]
    (log/info "Audit repository initialized")
    repo))

(defmethod ig/halt-key! :wagoe/audit-repository
  [_ _repo]
  (log/info "Audit repository halted (no cleanup needed)"))

;; =============================================================================
;; MFA Service
;; =============================================================================

(defmethod ig/init-key :wagoe/mfa-service
  [_ {:keys [user-repository mfa-config]}]
  (log/info "Initializing MFA service")
  (let [service (user-mfa/create-mfa-service user-repository mfa-config)]
    (log/info "MFA service initialized")
    service))

(defmethod ig/halt-key! :wagoe/mfa-service
  [_ _service]
  (log/info "MFA service halted (no cleanup needed)"))

;; =============================================================================
;; Authentication Service
;; =============================================================================

(defmethod ig/init-key :wagoe/auth-service
  [_ {:keys [user-repository session-repository mfa-service auth-config]}]
  (log/info "Initializing authentication service")
  ;; Fail fast: a missing or too-short JWT_SECRET aborts boot here rather than
  ;; surfacing on the first authentication request.
  (user-auth/validate-jwt-secret!)
  (let [service (user-auth/create-authentication-service
                 user-repository session-repository mfa-service auth-config)]
    (log/info "Authentication service initialized")
    service))

(defmethod ig/halt-key! :wagoe/auth-service
  [_ _service]
  (log/info "Authentication service halted (no cleanup needed)"))

;; =============================================================================
;; User Service
;; =============================================================================

(defmethod ig/init-key :wagoe/user-service
  [_ {:keys [user-repository session-repository audit-repository validation-config auth-service cache]}]
  (log/info "Initializing user service" {:cache-enabled? (some? cache)})
  (let [service (user-service/create-user-service
                 user-repository session-repository audit-repository validation-config auth-service cache)]
    (log/info "User service initialized")
    service))

(defmethod ig/halt-key! :wagoe/user-service
  [_ _service]
  (log/info "User service halted (no cleanup needed)"))

;; =============================================================================
;; User Routes (Structured Format for Top-Level Composition)
;; =============================================================================

(defmethod ig/init-key :wagoe/user-routes
  [_ {:keys [user-service mfa-service config email-sender]}]
  (log/info "Initializing user module routes"
            {:email-sender? (some? email-sender)})
  (require 'wagoe.user.shell.http)
  (let [user-routes-fn (ns-resolve 'wagoe.user.shell.http 'user-routes)
        config-with-email (cond-> (or config {})
                            email-sender
                            (assoc :email-sender email-sender
                                   :welcome-email-from (get-in config [:active :wagoe.external/smtp :from])
                                   :app-name (get-in config [:active :wagoe/settings :name])))
        routes (user-routes-fn user-service mfa-service config-with-email)]
    (log/info "User module routes initialized successfully"
              {:route-keys (keys routes)
               :api-count (count (:api routes))
               :web-count (count (:web routes))})
    routes))

(defmethod ig/halt-key! :wagoe/user-routes
  [_ _routes]
  (log/info "User module routes halted (no cleanup needed)"))

;; =============================================================================
;; User HTTP middleware
;; =============================================================================

(defmethod ig/init-key :wagoe/user-http-middleware
  [_ {:keys [user-service]}]
  ;; Sets :user when the request carries valid credentials and does nothing
  ;; when it does not — so it can run on every route, which is what the tenant
  ;; middleware needs. Platform takes it as :auth-middleware and puts it
  ;; outermost; membership enrichment reads [:user :id] and had nothing to read
  ;; before this existed (BOU-373).
  ;;
  ;; Rejecting stays per-route. A seq, like tenant's, so an application that
  ;; wires no user service contributes no middleware rather than a broken one.
  (if user-service
    [(user-middleware/authenticate-if-present user-service)]
    (do (log/info "No user service: skipping authentication middleware")
        [])))

(defmethod ig/halt-key! :wagoe/user-http-middleware
  [_ _mw]
  (log/info "User HTTP middleware halted (no cleanup needed)"))

;; =============================================================================
;; User HTTP Handler (DEPRECATED - Legacy Support REMOVED)
;; =============================================================================

(defmethod ig/init-key :wagoe/user-http-handler
  [_ {:keys [user-service config]}]
  (throw (ex-info "DEPRECATED: :wagoe/user-http-handler no longer supported"
                  {:type :configuration-error
                   :message "Legacy create-handler function has been removed"
                   :migration "Use :wagoe/user-routes with top-level :wagoe/http-handler instead"
                   :user-service user-service
                   :config config})))

(defmethod ig/halt-key! :wagoe/user-http-handler
  [_ _handler]
  (log/info "User HTTP handler halted"))

;; =============================================================================
;; User Database Schema
;; =============================================================================

(defmethod ig/init-key :wagoe/user-db-schema
  [_ {:keys [ctx]}]
  (log/info "Initializing user module database schema")
  (user-persistence/initialize-user-schema! ctx)
  (log/info "User module database schema initialized")
  {:status :initialized})

(defmethod ig/halt-key! :wagoe/user-db-schema
  [_ _state]
  (log/info "User module database schema component halted"))

;; =============================================================================
;; Module graph
;; =============================================================================

(defn ig-config
  "This module's Integrant entries, for `wagoe.platform.shell.system.config`.

   Called with the module's `:active` settings — nil, because the user module is
   switched on in code rather than in config — and the context the application
   supplies: `:config`, `:validation-config`, and `:enabled`, the set of sibling
   modules that are on."
  [_settings {:keys [config validation-config enabled]}]
  {:components
   {:wagoe/user-db-schema     {:ctx (ig/ref :wagoe/db-context)}
    :wagoe/user-repository    {:ctx (ig/ref :wagoe/db-context)}
    :wagoe/session-repository {:ctx (ig/ref :wagoe/db-context)}
    :wagoe/audit-repository   {:ctx               (ig/ref :wagoe/db-context)
                               :pagination-config (get-in config [:active :wagoe/pagination]
                                                          {:default-limit 20 :max-limit 100})}
    :wagoe/mfa-service        {:user-repository (ig/ref :wagoe/user-repository)
                               :mfa-config      {}}
    :wagoe/auth-service       {:user-repository    (ig/ref :wagoe/user-repository)
                               :session-repository (ig/ref :wagoe/session-repository)
                               :mfa-service        (ig/ref :wagoe/mfa-service)
                               :auth-config        {}}
    :wagoe/user-service       (cond-> {:user-repository    (ig/ref :wagoe/user-repository)
                                       :session-repository (ig/ref :wagoe/session-repository)
                                       :audit-repository   (ig/ref :wagoe/audit-repository)
                                       :validation-config  validation-config
                                       :auth-service       (ig/ref :wagoe/auth-service)
                                       :logger             (ig/ref :wagoe/logging)
                                       :metrics            (ig/ref :wagoe/metrics)
                                       :error-reporter     (ig/ref :wagoe/error-reporting)}
                                (contains? enabled :wagoe/cache)
                                (assoc :cache (ig/ref :wagoe/cache)))
    :wagoe/user-routes        {:user-service (ig/ref :wagoe/user-service)
                               :mfa-service  (ig/ref :wagoe/mfa-service)
                               :email-sender (ig/ref :wagoe/email)
                               :config       config}

    :wagoe/user-http-middleware {:user-service (ig/ref :wagoe/user-service)}}

   ;; :user-service stays named: the handler passes it to the test-reset
   ;; endpoint and the readiness check. Routes are a collection (BOU-330).
   ;;
   ;; :auth-middleware is its own key rather than part of :extra-middleware
   ;; because platform decides its position — outermost, ahead of the tenant
   ;; middleware that reads [:user :id]. Contributions merge by key and modules
   ;; iterate sorted, and :wagoe/tenant sorts before :wagoe/user, so ordering
   ;; could not have come from here (BOU-373).
   :http   {:user-service    (ig/ref :wagoe/user-service)
            :auth-middleware (ig/ref :wagoe/user-http-middleware)}
   :routes [(ig/ref :wagoe/user-routes)]})
