(ns wagoe.system-config
  "Assembles this application's Integrant system from its configuration.

   Reading configuration is `wagoe.config`; assembling the components every
   Wagoe app has, plus whichever modules the config switches on, is
   `wagoe.platform.shell.system.config`. What is left here is what only this
   application knows — which entities its admin UI manages, and the way back
   into this namespace that the devtools dashboard's config editor needs.

   Until BOU-326 this file enumerated the Integrant graph of every framework
   module by hand, 553 lines of it, and the generated `config.clj` carried a
   second copy. Both were wrong in the same place: neither wired
   `:wagoe/workflow-db-schema`, which creates the workflow module's tables.

   The returned map is data. Initialising it needs the `init-key` methods, and
   the unconditional ones are registered by the namespace that starts the
   system — `wagoe.main` or `dev/user.clj`. A module's own methods are loaded
   by `system-config` when the config asks for that module.

   Usage:
     (require 'wagoe.main)                 ; registers the unconditional init-keys
     (ig-config (wagoe.config/load-config))"
  (:require [wagoe.config :as config]
            [wagoe.platform.shell.system.config :as system]
            [wagoe.user.schema :as user-schema]))

;; The devtools dashboard is handed a thunk that rebuilds this map.
(declare ig-config)

(defn- with-dashboard-rebuild
  "Hand the dashboard a way back to this application's own Integrant config.

   devtools assembles the component itself from `:wagoe/dashboard` in the
   config (BOU-477); this is the one part of it a library cannot supply, since
   which components an application runs is the application's to know. The
   dashboard reports the absence rather than failing, so an application that
   does not want the config editor leaves this out."
  [system-cfg]
  (cond-> system-cfg
    (contains? system-cfg :wagoe/dashboard)
    (assoc-in [:wagoe/dashboard :ig-config-fn] #(ig-config (config/load-config)))))

(defn- dev-http-extras
  "What the dashboard needs from the HTTP handler, in dev only.

   `:request-capture?` makes the handler keep the last requests so the
   dashboard can show them. It costs memory per request, so it is not on
   anywhere else."
  [config]
  (when (= (:wagoe/profile config) :dev)
    {:request-capture? true}))

(defn ig-config
  "The Integrant configuration for `config`."
  [config]
  (-> (system/system-config config {:extra-modules #{:wagoe/user}})
      ;; Which of this application's entities the admin UI manages. The admin
      ;; module does not guess; the schemas are ours to name.
      (cond-> (get-in config [:active :wagoe/admin])
        (assoc-in [:wagoe/admin-schema-provider :malli-schemas]
                  {:users user-schema/User}))
      with-dashboard-rebuild
      (update :wagoe/http-handler merge (dev-http-extras config))))

;; =============================================================================
;; Service catalogue (BOU-91)
;; =============================================================================

(def default-service-catalogue
  "Which Integrant keys belong to which module, for `service` launch mode.

   Only the framework's own modules. A key listed nowhere here is treated as
   platform and runs in every service — see
   `wagoe.platform.core.system-selection/core-keys` — so the failure mode of an
   omission is a service that is larger than it needs to be, not one missing a
   component.

   `:rpc` says which protocol a service offers to the rest of the deployment.
   Without it a module can be booted alone but nothing can call it, which is
   the half of BOU-90 that needed this ticket. The protocol is a symbol so the
   catalogue stays plain data: it is resolved when the endpoint starts.

   An application overrides or extends this with `:wagoe/services` in its
   config.edn, and `service-catalogue` merges the two. Kept in code rather than
   copied into the four profile files because a copy in each is a copy to
   forget: the keys change when modules change, and nothing would notice."
  {:user     {:keys [:wagoe/user-db-schema :wagoe/user-repository
                     :wagoe/session-repository :wagoe/audit-repository
                     :wagoe/mfa-service :wagoe/auth-service
                     :wagoe/user-service :wagoe/user-routes
                     :wagoe/user-http-middleware :wagoe/admin-only-middleware
                     ;; Its timer belongs to whoever owns user_sessions, so a
                     ;; service that does not run the user module does not also
                     ;; prune its table (BOU-429).
                     :wagoe/session-pruner]
              ;; What this module offers the rest of a split deployment. Only
              ;; served when it is run as a service *and* :wagoe/rpc is
              ;; configured — a `server` boot never starts the listener.
              :rpc  {:protocol  'wagoe.user.ports/IUserService
                     :component :wagoe/user-service}}

   ;; Offered over RPC: another service asks which tenant a request belongs to
   ;; rather than reading the tenant tables itself.
   :tenant   {:rpc  {:protocol  'wagoe.tenant.ports/ITenantService
                     :component :wagoe/tenant-service}
              :keys [:wagoe/tenant-db-schema :wagoe/tenant-repository
                     :wagoe/tenant-service :wagoe/tenant-routes
                     :wagoe/tenant-http-middleware
                     :wagoe/membership-repository :wagoe/membership-service
                     :wagoe/membership-routes
                     :wagoe/invite-repository :wagoe/invite-service]}

   :admin    {:keys [:wagoe/admin-schema-provider :wagoe/admin-service
                     :wagoe/admin-routes]}

   ;; :wagoe/workflow and :wagoe/search, not :wagoe/*-service — the service
   ;; component carries the bare module name here, and the invented names left
   ;; the real components unclaimed, which meant `core-keys` counted them as
   ;; platform and ran them in every service. `main-test` now asserts every key
   ;; the config emits is claimed or listed as platform.
   :workflow {:keys [:wagoe/workflow :wagoe/workflow-db-schema
                     :wagoe/workflow-routes]}

   :search   {:keys [:wagoe/search :wagoe/search-routes]}

   ;; Its routes carry an admin-only guard that lives in the user module, so
   ;; `service audience` alone refuses to start them — run `service audience
   ;; user`. Duplicating user's keys here would give this service its own copy
   ;; of the auth stack, which is what :rpc exists to avoid (BOU-419 review).
   :audience {:keys [:wagoe/audience-db-schema :wagoe/audience-user-source
                     :wagoe/audience :wagoe/audience-routes]}

   ;; Which of these offers a protocol over RPC is decided in `rpc-offered`
   ;; below, not by whether someone got round to it: seven modules *cannot*
   ;; offer one as they stand, because the component this catalogue claims is a
   ;; map of parts rather than something implementing the module's own service
   ;; protocol (BOU-426).
   ;;
   ;; Nine modules whose components no entry claimed, so `core-keys` counted
   ;; them as platform and kept them in every service — and `service push`
   ;; answered "unknown module" while push ran inside `service user` (BOU-424).
   ;;
   ;; What stayed platform is in `platform-keys` in service_launch_test with
   ;; its reason: the sending adapters, the asset bundle, and the parts of jobs
   ;; and storage any service legitimately uses. What is here is the surface a
   ;; module owns — including the two that must not run everywhere,
   ;; `:wagoe/job-workers` and `:wagoe/storage-routes`.
   :jobs     {:keys [:wagoe/job-workers]}

   :storage  {:keys [:wagoe/storage-routes]}

   ;; Offered over RPC: sending a notification is a thing other services do,
   ;; and the alternative is every one of them holding FCM and APNs
   ;; credentials.
   :push     {:rpc  {:protocol  'wagoe.push.ports/IPushService
                     :component :wagoe.push/service}
              :keys [:wagoe.push/device-store :wagoe.push/analytics-store
                     :wagoe.push/fcm-provider :wagoe.push/apns-provider
                     :wagoe.push/service :wagoe.push/routes
                     :wagoe.push/job-handlers]}

   :calendar {:keys [:wagoe/calendar]}

   :geo      {:keys [:wagoe/geo-service]}

   :realtime {:keys [:wagoe/realtime]}

   :reports  {:keys [:wagoe/reports]}

   :ai       {:keys [:wagoe/ai-service]}

   :payments {:keys [:wagoe/payment-provider]
              :rpc  {:protocol  'wagoe.payments.ports/IPaymentProvider
                     :component :wagoe/payment-provider}}})

(def rpc-not-offered
  "Modules with a catalogue entry and deliberately no `:rpc`, and why.

   Without one a module can be booted alone and nothing else in the deployment
   can call it — `start-service!` says so at boot. That is the right answer for
   most of these, and for seven of them it is the only available one today.

   Measured by booting each module and asking whether the component this
   catalogue claims satisfies its own service protocol. Seven answered with a
   `PersistentArrayMap`: their init-key returns `{:store … :engine …}`, a bag of
   parts. `wagoe.platform.shell.rpc` needs an implementation, so giving those an
   `:rpc` means first giving the implementation an Integrant key of its own —
   a change to each module, not to this table (BOU-426)."
  {:admin    "a UI. Nothing in a deployment calls the admin service; people call its pages"
   :jobs     "this entry claims :wagoe/job-workers, a worker pool. The queue other services enqueue on is platform, in-process everywhere (BOU-424)"
   :storage  "this entry claims :wagoe/storage-routes. :wagoe/storage itself is platform, so a service that stores a file already has one"
   :ai       "its provider talks to an external API; a second hop through RPC buys nothing"
   :workflow "component is a map of {:store :registry :engine}; IWorkflowEngine has no key of its own"
   :search   "component is a map; ISearchEngine has no key of its own"
   :audience "component is a map of {:store :resolver :cache}; IAudienceResolver has no key of its own"
   :calendar "component is a map; CalendarAdapterProtocol has no key of its own"
   :geo      "component is a map; GeoProviderProtocol has no key of its own"
   :realtime "component is a map; IRealtimeService has no key of its own"
   :reports  "component is a map; ReportGeneratorProtocol has no key of its own"})

(defn service-catalogue
  "The service catalogue for `config`: the framework's, plus the app's own.

   An entry in config.edn replaces the default one of the same name outright
   rather than merging into it, so an application that has taken a module apart
   is not left with the framework's idea of its keys.

   Read from `:active` — where everything else in these files lives — and, for
   an application that put it at the top level next to `:test/reset-endpoint-
   enabled?`, from there too. Accepting only one placement meant a catalogue
   written in the obvious spot was silently ignored, and the symptom was
   `service my-module` reporting the module as unknown, which reads as a typo."
  [config]
  (merge default-service-catalogue
         (:wagoe/services config)
         (get-in config [:active :wagoe/services])))

(defn rpc-config
  "Settings for the RPC endpoint a service exposes, or nil if none configured."
  [config]
  (get-in config [:active :wagoe/rpc]))
