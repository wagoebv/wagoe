(ns wagoe.user.shell.module-wiring
  "Integrant wiring for the user module.

  This namespace owns all Integrant init/halt methods for user-specific
  components so that shared system wiring does not depend directly on
  user shell namespaces."
  (:require [wagoe.user.shell.persistence :as user-persistence]
            [wagoe.user.shell.auth-persistence :as auth-persistence]
            [wagoe.user.shell.service :as user-service]
            [wagoe.user.shell.auth :as user-auth]
            [wagoe.user.shell.hooks :as hooks]
            [wagoe.user.shell.identity-persistence :as identity-persistence]
            [wagoe.user.shell.group-persistence :as group-persistence]
            [wagoe.user.shell.group-service :as group-service]
            [wagoe.user.shell.landing :as landing]
            [wagoe.user.shell.mfa :as user-mfa]
            [wagoe.user.shell.oidc :as oidc]
            [wagoe.user.shell.middleware :as user-middleware]
            [wagoe.user.shell.session-pruner :as session-pruner]
            [wagoe.user.shell.data-export :as data-export]
            [wagoe.user.shell.web-handlers :as web-handlers]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

;; =============================================================================
;; User Repository
;; =============================================================================

;; DEPRECATED (BOU-346): no consumer — :wagoe/auth-service takes
;; :wagoe/user-repository. Kept for one release: it builds a working
;; repository when wired by hand, and the stability policy does not remove a
;; working key in the release that first deprecates it.
(defmethod ig/init-key :wagoe/auth-user-repository
  [_ {:keys [ctx]}]
  ;; The module does not contribute this key, so init only runs for a
  ;; hand-wired one — the deprecated use. A changelog entry nothing says at
  ;; runtime is an announcement to whoever already read it.
  (log/warn "DEPRECATED :wagoe/auth-user-repository — no consumer;"
            ":wagoe/auth-service takes :wagoe/user-repository."
            "Drop it from your wiring; removed in 2.0.")
  (auth-persistence/create-auth-user-repository ctx))

(defmethod ig/halt-key! :wagoe/auth-user-repository
  [_ _repo]
  (log/info "Auth user repository halted (no cleanup needed)"))

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
  [_ {:keys [user-repository mfa-config] :as opts}]
  (log/info "Initializing MFA service")
  (let [service (user-mfa/create-mfa-service user-repository mfa-config opts)]
    (log/info "MFA service initialized")
    service))

(defmethod ig/halt-key! :wagoe/mfa-service
  [_ _service]
  (log/info "MFA service halted (no cleanup needed)"))

;; =============================================================================
;; Authentication Service
;; =============================================================================

(defmethod ig/init-key :wagoe/auth-hooks
  [_ {:keys [configured contributed]}]
  (hooks/prepare configured contributed))

(defmethod ig/init-key :wagoe/auth-service
  [_ {:keys [user-repository session-repository mfa-service auth-config hooks]}]
  (log/info "Initializing authentication service")
  ;; Fail fast: a missing or too-short JWT_SECRET aborts boot here rather than
  ;; surfacing on the first authentication request.
  (user-auth/validate-jwt-secret!)
  (let [service (user-auth/create-authentication-service
                 user-repository session-repository mfa-service
                 (merge auth-config (select-keys hooks [:mfa-required?])))]
    (log/info "Authentication service initialized")
    service))

(defmethod ig/halt-key! :wagoe/auth-service
  [_ _service]
  (log/info "Authentication service halted (no cleanup needed)"))

;; =============================================================================
;; User Service
;; =============================================================================

(defmethod ig/init-key :wagoe/user-service
  [_ {:keys [user-repository session-repository audit-repository validation-config auth-service
             metrics-emitter]}]
  (log/info "Initializing user service")
  ;; The emitter rides on the service because the user handlers build their
  ;; interceptor :system from it; without it op metrics never fire.
  (let [service (-> (user-service/create-user-service
                     user-repository session-repository audit-repository validation-config auth-service)
                    (assoc :metrics-emitter metrics-emitter))]
    (log/info "User service initialized")
    service))

(defmethod ig/halt-key! :wagoe/user-service
  [_ _service]
  (log/info "User service halted (no cleanup needed)"))

;; =============================================================================
;; User Routes (Structured Format for Top-Level Composition)
;; =============================================================================

(defmethod ig/init-key :wagoe/user-routes
  [_ {:keys [user-service mfa-service config email-sender hooks]}]
  (log/info "Initializing user module routes"
            {:email-sender? (some? email-sender)})
  (require 'wagoe.user.shell.http)
  (let [user-routes-fn (ns-resolve 'wagoe.user.shell.http 'user-routes)
        config-with-email (cond-> (landing/with-hooks (or config {}) hooks)
                            email-sender
                            (assoc :email-sender email-sender
                                   :welcome-email-from (or (get-in config [:active :wagoe/email :from])
                                                           (get-in config [:active :wagoe.external/smtp :from]))
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

(defmethod ig/init-key :wagoe/user-home-middleware
  [_ {:keys [config]}]
  (landing/wrap-home config))

(defmethod ig/init-key :wagoe/admin-only-middleware
  [_ {:keys [user-service]}]
  ;; The rejecting counterpart of the above: authenticate, then demand the
  ;; admin role. Exposed as a component so a module that must not be reachable
  ;; anonymously can take a ref to it without requiring this library — audience
  ;; mounts management endpoints and depends on no other Wagoe library
  ;; (BOU-419 review).
  ;;
  ;; A seq, like the middleware above, so an application with no user service
  ;; contributes none rather than a broken one. A module that guards on this
  ;; must not mount its routes in that case: an empty vector is no protection.
  (if user-service
    [(fn [handler] (user-middleware/protect-admin-only user-service handler))]
    (do (log/info "No user service: no admin-only middleware to contribute")
        [])))

(defmethod ig/halt-key! :wagoe/admin-only-middleware
  [_ _mw]
  nil)

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
;; Session Pruning
;; =============================================================================

(defmethod ig/init-key :wagoe/session-pruner
  [_ {:keys [session-repository settings]}]
  (if (false? (:enable-pruning settings))
    (do (log/info "Session pruning disabled; user_sessions will grow unbounded")
        nil)
    (session-pruner/start! session-repository settings)))

(defmethod ig/halt-key! :wagoe/session-pruner
  [_ handle]
  (session-pruner/stop! handle))

;; =============================================================================
;; OpenID Connect sign-in
;; =============================================================================

(defmethod ig/init-key :wagoe/user-identity-repository
  [_ {:keys [ctx]}]
  (identity-persistence/create-identity-repository ctx))

(defmethod ig/init-key :wagoe/user-oidc
  [_ {:keys [settings] :as opts}]
  (log/info "Initializing OIDC sign-in" {:providers (keys (:providers settings))})
  (oidc/create-oidc-client
   (assoc opts :secret (or (:state-secret settings) (user-auth/get-jwt-secret)))))

(defmethod ig/init-key :wagoe/user-oidc-routes
  [_ {:keys [oidc config hooks]}]
  (require 'wagoe.user.shell.http)
  ((ns-resolve 'wagoe.user.shell.http 'oidc-routes) oidc (landing/with-hooks config hooks)))

;; =============================================================================
;; Groups
;; =============================================================================

(defmethod ig/init-key :wagoe/user-group-repository
  [_ {:keys [ctx]}]
  (group-persistence/create-group-repository ctx))

(defmethod ig/init-key :wagoe/user-group-service
  [_ {:keys [group-repository user-repository audit-repository settings permission-registry hooks migrate?]}]
  (let [svc (group-service/create-group-service group-repository user-repository audit-repository
                                                (group-service/with-registry-scopes settings permission-registry)
                                                {:registry   permission-registry
                                                 :membership (:group-membership hooks)})]
    ;; `migrate? false`: `wagoe upgrade authorization check` reads what the
    ;; migration would do without doing it.
    (when-not (false? migrate?) (group-service/migrate! svc))
    svc))

(defmethod ig/init-key :wagoe/user-group-routes
  [_ {:keys [user-service group-service config]}]
  (require 'wagoe.user.shell.http)
  ((ns-resolve 'wagoe.user.shell.http 'group-routes) user-service group-service config))

;; =============================================================================
;; Data export and retention
;; =============================================================================

(defmethod ig/init-key :wagoe/user-data-exporter
  [_ repositories]
  (data-export/map->UserDataExporter repositories))

(defmethod ig/init-key :wagoe/user-audit-retention
  [_ {:keys [audit-repository]}]
  (data-export/->AuditLogRetention audit-repository))

(defmethod ig/init-key :wagoe/user-export-routes
  [_ config]
  (require 'wagoe.user.shell.http)
  ((ns-resolve 'wagoe.user.shell.http 'export-routes) (update config :export-jobs first)))

;; =============================================================================
;; Module graph
;; =============================================================================

(defn- base-graph
  [config validation-config auth-config group-settings]
  {:components
   {:wagoe/user-db-schema     {:ctx (ig/ref :wagoe/db-context)}
    :wagoe/user-repository    {:ctx (ig/ref :wagoe/db-context)}
    :wagoe/session-repository {:ctx (ig/ref :wagoe/db-context)}
    :wagoe/user-identity-repository {:ctx (ig/ref :wagoe/db-context)}
    ;; Expiry hides a session; it does not remove the row. Without this the
    ;; table grows with every login, for ever (BOU-429).
    :wagoe/session-pruner     {:session-repository (ig/ref :wagoe/session-repository)
                               :settings           (merge session-pruner/default-settings
                                                          (get-in config [:active :wagoe/session-pruner]))}
    :wagoe/audit-repository   {:ctx               (ig/ref :wagoe/db-context)
                               :pagination-config (get-in config [:active :wagoe/pagination]
                                                          {:default-limit 20 :max-limit 100})}
    :wagoe/mfa-service        {:user-repository  (ig/ref :wagoe/user-repository)
                               :audit-repository (ig/ref :wagoe/audit-repository)
                               :auth-config      (dissoc auth-config :mfa-required?)
                               :mfa-config       {}}
    ;; Platform adds the modules' :auth part as :contributed.
    :wagoe/auth-hooks         {:configured (assoc (select-keys (get-in config [:active :wagoe/user])
                                                               hooks/config-hooks)
                                                  :mfa-required? (:mfa-required? auth-config))}
    :wagoe/auth-service       {:user-repository    (ig/ref :wagoe/user-repository)
                               :session-repository (ig/ref :wagoe/session-repository)
                               :mfa-service        (ig/ref :wagoe/mfa-service)
                               :auth-config        (dissoc auth-config :mfa-required?)
                               :hooks              (ig/ref :wagoe/auth-hooks)}
    :wagoe/user-service       {:user-repository    (ig/ref :wagoe/user-repository)
                               :session-repository (ig/ref :wagoe/session-repository)
                               :audit-repository   (ig/ref :wagoe/audit-repository)
                               :validation-config  validation-config
                               :auth-service       (ig/ref :wagoe/auth-service)
                               :logger             (ig/ref :wagoe/logging)
                               :metrics-emitter    (ig/ref :wagoe/metrics)
                               :error-reporter     (ig/ref :wagoe/error-reporting)}
    :wagoe/user-routes        {:user-service (ig/ref :wagoe/user-service)
                               :mfa-service  (ig/ref :wagoe/mfa-service)
                               :email-sender (ig/ref :wagoe/email)
                               :hooks        (ig/ref :wagoe/auth-hooks)
                               :config       config}

    :wagoe/user-group-repository {:ctx (ig/ref :wagoe/db-context)}
    :wagoe/user-group-service {:group-repository (ig/ref :wagoe/user-group-repository)
                               :user-repository  (ig/ref :wagoe/user-repository)
                               :audit-repository (ig/ref :wagoe/audit-repository)
                               :permission-registry (ig/ref :wagoe/permission-registry)
                               :hooks            (ig/ref :wagoe/auth-hooks)
                               ;; Its tables exist before the migration runs.
                               :db-schema        (ig/ref :wagoe/user-db-schema)
                               :settings         group-settings}
    :wagoe/user-group-routes  {:user-service  (ig/ref :wagoe/user-service)
                               :group-service (ig/ref :wagoe/user-group-service)
                               :config        config}

    :wagoe/user-data-exporter {:user-repository     (ig/ref :wagoe/user-repository)
                               :session-repository  (ig/ref :wagoe/session-repository)
                               :audit-repository    (ig/ref :wagoe/audit-repository)
                               :identity-repository (ig/ref :wagoe/user-identity-repository)
                               :group-repository    (ig/ref :wagoe/user-group-repository)}
    :wagoe/user-audit-retention {:audit-repository (ig/ref :wagoe/audit-repository)}
    ;; :wagoe/data-export is platform's, over every module's :data-export.
    :wagoe/user-export-routes {:user-service (ig/ref :wagoe/user-service)
                               :data-export  (ig/ref :wagoe/data-export)
                               :audit-repository (ig/ref :wagoe/audit-repository)
                               ;; Storage's, with the jobs module; empty without.
                               :export-jobs  (ig/refset :wagoe/export-jobs)
                               :config       config}

    :wagoe/user-http-middleware {:user-service (ig/ref :wagoe/user-service)}
    :wagoe/user-home-middleware {:config config}
    :wagoe/admin-only-middleware {:user-service (ig/ref :wagoe/user-service)}}

   ;; :user-service stays named: the handler passes it to the test-reset
   ;; endpoint and the readiness check. Routes are a collection (BOU-330).
   ;;
   ;; :auth-middleware is its own key rather than part of :extra-middleware
   ;; because platform decides its position — outermost, ahead of the tenant
   ;; middleware that reads [:user :id]. Contributions merge by key and modules
   ;; iterate sorted, and :wagoe/tenant sorts before :wagoe/user, so ordering
   ;; could not have come from here (BOU-373).
   :http   {:user-service     (ig/ref :wagoe/user-service)
            ;; Effective rights for the authorization interceptor (ADR-041 §5).
            :rights           (ig/ref :wagoe/user-group-service)
            :auth-middleware  (ig/ref :wagoe/user-http-middleware)
            :extra-middleware (ig/ref :wagoe/user-home-middleware)
            ;; The page in the app's layout a browser sees for an error on /web
            :error-page       web-handlers/error-page}
   :routes [(ig/ref :wagoe/user-routes) (ig/ref :wagoe/user-group-routes)
            (ig/ref :wagoe/user-export-routes)]
   :data-export {:user (ig/ref :wagoe/user-data-exporter)}
   :retention   {:user/audit-log (ig/ref :wagoe/user-audit-retention)}})

(defn ig-config
  "This module's Integrant entries, for `wagoe.platform.shell.system.config`.

   Called with the module's `:active` settings — nil when the user module is
   switched on in code rather than in config — and the context the application
   supplies: `:config` and `:validation-config`. OIDC sign-in is wired only
   when the settings name a provider under `[:oidc :providers]`. `:auth` becomes
   the auth service's config, its `:mfa-required?` going through
   `:wagoe/auth-hooks`, where platform adds the hooks modules contribute.

   `:login-redirect`, `:login-layout`, `:profile-layout`, `:dashboard`, `:home`, `:landing` and `:registration` are checked and resolved here,
   once, so a bad value fails boot whichever components start, as are the
   `:groups` schemas."
  [settings {:keys [config validation-config]}]
  (let [config (landing/prepare config)
        groups (group-service/prepare-settings (:groups settings))]
    (cond-> (assoc (base-graph config validation-config (:auth settings {}) groups)
                   :authorization (merge (group-service/declared-permissions groups)
                                         (group-service/declared-scopes groups)))
      (seq (get-in settings [:oidc :providers]))
      (-> (update :components assoc
                  :wagoe/user-oidc        {:settings            (:oidc settings)
                                           :auth-service        (ig/ref :wagoe/auth-service)
                                           :audit-repository    (ig/ref :wagoe/audit-repository)
                                           :identity-repository (ig/ref :wagoe/user-identity-repository)}
                  :wagoe/user-oidc-routes {:oidc   (ig/ref :wagoe/user-oidc)
                                           :hooks  (ig/ref :wagoe/auth-hooks)
                                           :config config})
          (update :routes conj (ig/ref :wagoe/user-oidc-routes))))))
