(ns wagoe.admin.shell.module-wiring
  "Integrant lifecycle management for admin module.

   This namespace defines Integrant initialization and shutdown methods
   for all admin module components:
   - Schema provider (ISchemaProvider implementation)
   - Admin service (IAdminService implementation)
   - Admin routes (HTTP routes)

   Dependencies are injected via Integrant refs, maintaining FC/IS separation
   and enabling testability with mock implementations."
  (:require
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [wagoe.admin.shell.schema-repository :as schema-repo]
   [wagoe.admin.shell.service :as service]
   [wagoe.admin.shell.http :as http]
   [wagoe.admin.shell.http.support :as support]))

;; =============================================================================
;; Admin Config Component (pass-through holder referenced by other components)
;; =============================================================================

;; =============================================================================
;; Schema Provider Component
;; =============================================================================

;; DEPRECATED (BOU-346): this *component* is a settings passthrough with no
;; consumer — admin's three components take the settings map directly. Kept
;; for one release because it works when wired by hand, and the stability
;; policy does not remove a working key in the release that deprecates it.
;;
;; The `:wagoe/admin` key in `:active` is a different thing and is not
;; deprecated: it is how an application switches the admin module on.
(defmethod ig/init-key :wagoe/admin [_ config]
  ;; The module contributes admin-schema-provider/-service/-routes and not this
  ;; key, so init only runs for a hand-wired one. Switching the module on with
  ;; `:wagoe/admin` under `:active` does not reach here.
  (log/warn "DEPRECATED :wagoe/admin component — a settings passthrough with no"
            "consumer; admin's components take the settings map directly."
            "Drop it from your wiring; removed in 2.0.")
  config)

(defmethod ig/halt-key! :wagoe/admin [_ _] nil)

(defmethod ig/init-key :wagoe/admin-schema-provider
  [_ {:keys [db-ctx config malli-schemas]}]
  (schema-repo/create-schema-repository db-ctx config malli-schemas))

(defmethod ig/halt-key! :wagoe/admin-schema-provider
  [_ _schema-provider]
  ; No cleanup needed - stateless component
  nil)

;; =============================================================================
;; Admin Service Component
;; =============================================================================

(defmethod ig/init-key :wagoe/admin-service
  [_ {:keys [db-ctx schema-provider logger error-reporter config]}]
  (service/create-admin-service db-ctx schema-provider logger error-reporter config))

(defmethod ig/halt-key! :wagoe/admin-service
  [_ _admin-service]
  ; No cleanup needed - stateless component
  nil)

;; =============================================================================
;; Admin Routes Component
;; =============================================================================

(defmethod ig/init-key :wagoe/admin-routes
  [_ {:keys [admin-service schema-provider user-service config]}]
  ;; The module's contribution: web routes, mounted under :web-prefix
  (http/admin-routes admin-service schema-provider config user-service))

(defmethod ig/halt-key! :wagoe/admin-routes
  [_ _routes]
  ; No cleanup needed - routes are just data
  nil)

;; =============================================================================
;; Module graph
;; =============================================================================

(defn- check-date-patterns
  "Drop the configured date patterns that `DateTimeFormatter` rejects, warning
   once here at boot rather than on every admin page load."
  [config]
  (reduce (fn [cfg k]
            (cond-> cfg
              (contains? cfg k) (assoc k (support/valid-date-pattern (get cfg k)))))
          config
          [:date-format :date-time-format]))

(defn- check-time-zone
  "Refuse to boot on a `:time-zone` the JVM does not know. It is the zone the
   admin shows and reads timestamps in when the browser has not said which it
   is in (BOU-523); falling back to the server's zone on a typo would make
   every timestamp quietly depend on where the app happens to run."
  [config]
  (if-let [tz (:time-zone config)]
    (try (java.time.ZoneId/of (str tz))
         config
         (catch java.time.DateTimeException _
           (throw (ex-info (str "`:time-zone` in :wagoe/settings is not a known time zone: "
                                (pr-str tz) " — use an IANA name such as \"Europe/Amsterdam\"")
                           {:type :configuration-error :time-zone tz}))))
    config))

(defn ig-config
  "This module's Integrant entries, for `wagoe.platform.shell.system.config`.

   `:malli-schemas` is deliberately absent: which of its entities the admin UI
   manages is the application's decision, and it merges them in. Two helpers
   that used to live here built the same graph against `:wagoe/database-context`
   and `:wagoe/logger` — keys no application wires — so they had never run
   (BOU-326)."
  [settings ctx]
  ;; A module's `settings` is its own `:active` section, so `:date-format` and
  ;; `:date-time-format` — which live under `:wagoe/settings` — were out of reach
  ;; of every admin handler. Nothing read them anywhere and the list table
  ;; rendered raw database timestamps (BOU-382). Copied in rather than handing
  ;; the whole application config to the routes, which would let any handler
  ;; reach anything.
  (let [app-settings  (get-in ctx [:config :active :wagoe/settings])
        routes-config (-> (merge (select-keys app-settings [:date-format :date-time-format :time-zone])
                                 settings)
                          (check-date-patterns)
                          (check-time-zone))]
    {:components
     {:wagoe/admin-schema-provider {:db-ctx (ig/ref :wagoe/db-context)
                                    :config settings}
      :wagoe/admin-service         {:db-ctx          (ig/ref :wagoe/db-context)
                                    :schema-provider (ig/ref :wagoe/admin-schema-provider)
                                    :logger          (ig/ref :wagoe/logging)
                                    :error-reporter  (ig/ref :wagoe/error-reporting)
                                    :config          settings}
      :wagoe/admin-routes          {:admin-service   (ig/ref :wagoe/admin-service)
                                    :schema-provider (ig/ref :wagoe/admin-schema-provider)
                                    :user-service    (ig/ref :wagoe/user-service)
                                    :config          routes-config}}
     ;; A ref in a collection, not a named slot on the handler: platform holds
     ;; no list of which modules may contribute routes (BOU-330).
     :routes [(ig/ref :wagoe/admin-routes)]}))
