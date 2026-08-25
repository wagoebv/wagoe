(ns wagoe.system-config
  "Assembles this application's Integrant system from its configuration.

   Reading configuration is `wagoe.config`; assembling the components every
   Wagoe app has, plus whichever modules the config switches on, is
   `wagoe.platform.shell.system.config`. What is left here is what only this
   application knows — which entities its admin UI manages, and the devtools
   dashboard it runs in dev.

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
  (:require [integrant.core :as ig]
            [wagoe.config :as config]
            [wagoe.platform.shell.system.config :as system]
            [wagoe.user.schema :as user-schema]))

;; The devtools dashboard is handed a thunk that rebuilds this map.
(declare ig-config)

(defn- dashboard-config
  "The devtools dashboard, in dev only.

   Not a framework module: it needs a way to rebuild the very map it appears
   in, so it cannot be assembled by something that does not know this
   namespace. Wrapped in a try because devtools lives in the :repl alias, and
   `wagoe.main` boots without it."
  [config]
  (when-let [cfg (and (= (:wagoe/profile config) :dev)
                      (get-in config [:active :wagoe/dashboard]))]
    (try
      (require 'wagoe.devtools.shell.dashboard.server)
      {:wagoe/dashboard
       {:port         (:port cfg 9999)
        ;; The dashboard rebuilds the system when you edit a config value, so
        ;; it needs a way back here. Passed in rather than resolved: which
        ;; components an application runs is the application's to know.
        :ig-config-fn #(ig-config (config/load-config))
        :http-handler (ig/ref :wagoe/http-handler)
        :http-server  (ig/ref :wagoe/http-server)
        :db-context   (ig/ref :wagoe/db-context)
        :router       (ig/ref :wagoe/router)
        :logging      (ig/ref :wagoe/logging)}}
      (catch Exception _ nil))))

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
      (merge (dashboard-config config))
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
                     :wagoe/user-http-middleware]
              ;; What this module offers the rest of a split deployment. Only
              ;; served when it is run as a service *and* :wagoe/rpc is
              ;; configured — a `server` boot never starts the listener.
              :rpc  {:protocol  'wagoe.user.ports/IUserService
                     :component :wagoe/user-service}}

   :tenant   {:keys [:wagoe/tenant-db-schema :wagoe/tenant-repository
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

   :ai       {:keys [:wagoe/ai-service]}

   :payments {:keys [:wagoe/payment-provider]
              :rpc  {:protocol  'wagoe.payments.ports/IPaymentProvider
                     :component :wagoe/payment-provider}}})

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
