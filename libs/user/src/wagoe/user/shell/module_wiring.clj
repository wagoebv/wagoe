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
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

;; =============================================================================
;; User Repository
;; =============================================================================

(defmethod ig/init-key :boundary/user-repository
  [_ {:keys [ctx]}]
  (log/info "Initializing user repository")
  (let [repo (user-persistence/create-user-repository ctx)]
    (log/info "User repository initialized")
    repo))

(defmethod ig/halt-key! :boundary/user-repository
  [_ _repo]
  (log/info "User repository halted (no cleanup needed)"))

;; =============================================================================
;; Auth User Repository
;; =============================================================================

(defmethod ig/init-key :boundary/auth-user-repository
  [_ {:keys [ctx]}]
  (log/info "Initializing auth user repository")
  (let [repo (auth-persistence/create-auth-user-repository ctx)]
    (log/info "Auth user repository initialized")
    repo))

(defmethod ig/halt-key! :boundary/auth-user-repository
  [_ _repo]
  (log/info "Auth user repository halted (no cleanup needed)"))

;; =============================================================================
;; Session Repository
;; =============================================================================

(defmethod ig/init-key :boundary/session-repository
  [_ {:keys [ctx]}]
  (log/info "Initializing session repository")
  (let [repo (user-persistence/create-session-repository ctx)]
    (log/info "Session repository initialized")
    repo))

(defmethod ig/halt-key! :boundary/session-repository
  [_ _repo]
  (log/info "Session repository halted (no cleanup needed)"))

;; =============================================================================
;; Audit Repository
;; =============================================================================

(defmethod ig/init-key :boundary/audit-repository
  [_ {:keys [ctx pagination-config]}]
  (log/info "Initializing audit repository")
  (let [repo (user-persistence/create-audit-repository ctx pagination-config)]
    (log/info "Audit repository initialized")
    repo))

(defmethod ig/halt-key! :boundary/audit-repository
  [_ _repo]
  (log/info "Audit repository halted (no cleanup needed)"))

;; =============================================================================
;; MFA Service
;; =============================================================================

(defmethod ig/init-key :boundary/mfa-service
  [_ {:keys [user-repository mfa-config]}]
  (log/info "Initializing MFA service")
  (let [service (user-mfa/create-mfa-service user-repository mfa-config)]
    (log/info "MFA service initialized")
    service))

(defmethod ig/halt-key! :boundary/mfa-service
  [_ _service]
  (log/info "MFA service halted (no cleanup needed)"))

;; =============================================================================
;; Authentication Service
;; =============================================================================

(defmethod ig/init-key :boundary/auth-service
  [_ {:keys [user-repository session-repository mfa-service auth-config]}]
  (log/info "Initializing authentication service")
  ;; Fail fast: a missing or too-short JWT_SECRET aborts boot here rather than
  ;; surfacing on the first authentication request.
  (user-auth/validate-jwt-secret!)
  (let [service (user-auth/create-authentication-service
                 user-repository session-repository mfa-service auth-config)]
    (log/info "Authentication service initialized")
    service))

(defmethod ig/halt-key! :boundary/auth-service
  [_ _service]
  (log/info "Authentication service halted (no cleanup needed)"))

;; =============================================================================
;; User Service
;; =============================================================================

(defmethod ig/init-key :boundary/user-service
  [_ {:keys [user-repository session-repository audit-repository validation-config auth-service cache]}]
  (log/info "Initializing user service" {:cache-enabled? (some? cache)})
  (let [service (user-service/create-user-service
                 user-repository session-repository audit-repository validation-config auth-service cache)]
    (log/info "User service initialized")
    service))

(defmethod ig/halt-key! :boundary/user-service
  [_ _service]
  (log/info "User service halted (no cleanup needed)"))

;; =============================================================================
;; User Routes (Structured Format for Top-Level Composition)
;; =============================================================================

(defmethod ig/init-key :boundary/user-routes
  [_ {:keys [user-service mfa-service config email-sender]}]
  (log/info "Initializing user module routes (normalized format)"
            {:email-sender? (some? email-sender)})
  (require 'wagoe.user.shell.http)
  (let [user-routes-fn (ns-resolve 'wagoe.user.shell.http 'user-routes-normalized)
        config-with-email (cond-> (or config {})
                            email-sender
                            (assoc :email-sender email-sender
                                   :welcome-email-from (get-in config [:active :wagoe.external/smtp :from])
                                   :app-name (get-in config [:active :boundary/settings :name])))
        routes (user-routes-fn user-service mfa-service config-with-email)]
    (log/info "User module routes initialized successfully"
              {:route-keys (keys routes)
               :api-count (count (:api routes))
               :web-count (count (:web routes))})
    routes))

(defmethod ig/halt-key! :boundary/user-routes
  [_ _routes]
  (log/info "User module routes halted (no cleanup needed)"))

;; =============================================================================
;; User HTTP Handler (DEPRECATED - Legacy Support REMOVED)
;; =============================================================================

(defmethod ig/init-key :boundary/user-http-handler
  [_ {:keys [user-service config]}]
  (throw (ex-info "DEPRECATED: :boundary/user-http-handler no longer supported"
                  {:message "Legacy create-handler function has been removed"
                   :migration "Use :boundary/user-routes with top-level :boundary/http-handler instead"
                   :user-service user-service
                   :config config})))

(defmethod ig/halt-key! :boundary/user-http-handler
  [_ _handler]
  (log/info "User HTTP handler halted"))

;; =============================================================================
;; User Database Schema
;; =============================================================================

(defmethod ig/init-key :boundary/user-db-schema
  [_ {:keys [ctx]}]
  (log/info "Initializing user module database schema")
  (user-persistence/initialize-user-schema! ctx)
  (log/info "User module database schema initialized")
  {:status :initialized})

(defmethod ig/halt-key! :boundary/user-db-schema
  [_ _state]
  (log/info "User module database schema component halted"))
