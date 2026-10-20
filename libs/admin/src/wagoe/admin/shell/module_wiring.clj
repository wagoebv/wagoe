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
   [wagoe.admin.core.permissions :as permissions]
   [wagoe.admin.schema :as admin-schema]
   [wagoe.admin.shell.schema-repository :as schema-repo]
   [wagoe.admin.shell.service :as service]
   [wagoe.admin.shell.user-writer :as user-writer]
   [wagoe.admin.shell.http :as http]
   [wagoe.admin.shell.http.support :as support]
   [wagoe.platform.core.authorization :as authorization]
   [wagoe.platform.core.http.access :as access]
   [wagoe.platform.shell.authorization :as platform-authorization]))

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
  [_ {:keys [db-ctx schema-provider logger error-reporter config event-publisher workflows]
      users :user-service}]
  (service/create-admin-service db-ctx schema-provider logger error-reporter config
                                event-publisher workflows
                                ;; An entity config says `:writer :user` to be written
                                ;; through the user service (BOU-665).
                                (when users
                                  {:user (user-writer/create-user-writer users)})))

(defmethod ig/halt-key! :wagoe/admin-service
  [_ _admin-service]
  ; No cleanup needed - stateless component
  nil)

;; =============================================================================
;; Admin Routes Component
;; =============================================================================

(defn- access-fn
  "A fn from a signed-in user to what `wagoe.admin.core.permissions` decides
   on: their admin.* rights, resolved, read once per call."
  [mode registry rights-source]
  (fn [user]
    (let [platform-admin? (access/platform-admin? user)]
      {:mode            mode
       :platform-admin? platform-admin?
       :rights          (if platform-admin?
                          []
                          (platform-authorization/rights-for registry rights-source user
                                                             authorization/admin-permission?))})))

(defmethod ig/init-key :wagoe/admin-routes
  [_ {:keys [admin-service schema-provider user-service config workflows permission-registry rights]}]
  ;; The module's contribution: web routes, mounted under :web-prefix
  (http/admin-routes admin-service schema-provider
                     (cond-> config
                       workflows           (assoc :workflows workflows)
                       permission-registry (assoc :access (access-fn (:authorization-mode config)
                                                                     permission-registry rights)))
                     user-service))

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

(defn- with-defaults
  "`settings` over `default-admin-config`, one level deep so a partial
   `:pagination` keeps the other key. `wagoe add admin` writes no
   `:entity-discovery`, and its nil mode 500ed every admin page (BOU-637)."
  [settings]
  (merge-with (fn [default v]
                (cond (nil? v)                         default
                      (and (map? default) (map? v))    (merge default v)
                      :else                            v))
              admin-schema/default-admin-config
              settings))

(defn- entity-ns [entity] (namespace (permissions/entity-permission entity :read)))

(defn- entity-permissions
  "`admin.<entity>/read` and `admin.<entity>/write`."
  [entity]
  (authorization/read-write-permissions (entity-ns entity) (str (name entity) " in the admin")))

(defn- entity-roles
  "`admin.<entity>/viewer` and `admin.<entity>/editor`. Only the admin's code
   roles may hold admin.* permissions (ADR-041 §2)."
  [entity]
  (authorization/derived-roles (entity-ns entity)))

(defn- resolve-condition
  "`v`, a scope's field or relation condition; a symbol, how config.edn names
   a condition, resolved to its fn. Throws :configuration-error on a symbol
   that resolves to nothing."
  [v]
  (if (symbol? v)
    (or (some-> (try (requiring-resolve v) (catch Exception _ nil)) deref)
        (throw (ex-info (str "Admin entity :scope names " v ", which resolves to nothing")
                        {:type :configuration-error :symbol v})))
    v))

(defn- resolve-scope-fns
  "`settings` with every entity's `:scope` conditions resolved."
  [settings]
  (cond-> settings
    (map? (:entities settings))
    (update :entities update-vals
            (fn [entity]
              (cond-> entity
                (map? (:scope entity)) (update :scope update-vals resolve-condition))))))

(defn- authorization-mode
  "`:wagoe/authorization :mode`, `:off` when unset. The platform refuses any
   other value at boot."
  [ctx]
  (or (get-in ctx [:config :active :wagoe/authorization :mode]) :off))

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
  (let [mode          (authorization-mode ctx)
        ;; Before the defaults, which carry :require-role themselves
        _             (when (and (:require-role settings) (not= :off mode))
                        (log/warn (str "DEPRECATED :require-role in the admin config — under :wagoe/authorization"
                                       " :mode " mode " the admin reads admin.<entity>/read|write instead."
                                       " Drop it; removed in 2.0.")))
        settings      (resolve-scope-fns (with-defaults settings))
        ;; Optional and one-way: wagoe.workflow depends on the admin, and
        ;; builds the port only when both are on (BOU-563).
        workflows?    (contains? (:enabled ctx) :wagoe/workflow)
        app-settings  (get-in ctx [:config :active :wagoe/settings])
        routes-config (-> (merge (select-keys app-settings [:date-format :date-time-format :time-zone])
                                 settings)
                          (check-date-patterns)
                          (check-time-zone)
                          (assoc :authorization-mode mode))]
    {:components
     {:wagoe/admin-schema-provider {:db-ctx (ig/ref :wagoe/db-context)
                                    :config settings}
      :wagoe/admin-service         (cond-> {:db-ctx          (ig/ref :wagoe/db-context)
                                            :schema-provider (ig/ref :wagoe/admin-schema-provider)
                                            :logger          (ig/ref :wagoe/logging)
                                            :error-reporter  (ig/ref :wagoe/error-reporting)
                                            :user-service    (ig/ref :wagoe/user-service)
                                            :config          settings}
                                     ;; Only when configured: a ref to an absent
                                     ;; component fails the boot (BOU-492).
                                     (contains? (:enabled ctx) :wagoe/events)
                                     (assoc :event-publisher (ig/ref :wagoe/events))
                                     workflows? (assoc :workflows (ig/ref :wagoe/workflow-admin)))
      :wagoe/admin-routes          (cond-> {:admin-service   (ig/ref :wagoe/admin-service)
                                            :schema-provider (ig/ref :wagoe/admin-schema-provider)
                                            :user-service    (ig/ref :wagoe/user-service)
                                            :config          routes-config}
                                     workflows? (assoc :workflows (ig/ref :wagoe/workflow-admin))
                                     ;; Only when checked, so :off wires what it always did
                                     (not= :off mode)
                                     (assoc :permission-registry (ig/ref :wagoe/permission-registry)
                                            :rights (ig/ref :wagoe/user-group-service)))}
     ;; A ref in a collection, not a named slot on the handler: platform holds
     ;; no list of which modules may contribute routes (BOU-330).
     :routes [(ig/ref :wagoe/admin-routes)]
     ;; admin.<entity>/read and /write for every entity the admin manages.
     :authorization (let [entities (get-in settings [:entity-discovery :allowlist])]
                      {:permissions (into {} (map entity-permissions) entities)
                       :roles       (into {} (map entity-roles) entities)})}))
