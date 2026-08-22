(ns wagoe.platform.shell.modules
  "Module registry and composition helpers for the Wagoe shell.

  This namespace provides utilities to:
  - Determine which modules are enabled based on configuration.
  - Compose route definitions from multiple modules.
  - Dispatch CLI commands to module-specific runners.

  For now, only the `:user` module is supported."
  (:require [clojure.tools.logging :as log]
            [integrant.core :as ig]))

(defn enabled-modules
  "Return the vector of enabled modules based on app config.

  Looks at [:active :wagoe/settings :modules] in the configuration map.
  If no modules are configured, defaults to [:user] for backwards compatibility."
  [config]
  (let [mods (get-in config [:active :wagoe/settings :modules])]
    (if (seq mods)
      (vec mods)
      [:user])))

(defn compose-module-routes
  "Compose route definitions from multiple modules (new pattern).

  Arguments:
    route-maps: collection of route maps from modules.
                Each route map has keys: :api, :web, :static.

  Returns:
    Combined route map with merged routes:
    {:api    [all-api-routes]
     :web    [all-web-routes]
     :static [all-static-routes]}

  Example:
    (compose-module-routes [{:api [['/users' {...}]] :web [] :static []}
                            {:api [['/billing' {...}]] :web [] :static []}])
    ;=> {:api [['/users' {...}] ['/billing' {...}]]
    ;    :web []
    ;    :static []}"
  [route-maps]
  (reduce
   (fn [acc route-map]
     {:api    (vec (concat (:api acc []) (:api route-map [])))
      :web    (vec (concat (:web acc []) (:web route-map [])))
      :static (vec (concat (:static acc []) (:static route-map [])))})
   {:api [] :web [] :static []}
   route-maps))

(defn dispatch-cli
  "Dispatch CLI based on enabled modules.

  Arguments:
    enabled-modules: vector of module keywords from config
    module->runner: map of module keyword to a function (fn [args] -> exit-code)
    args: raw CLI arguments vector

  CLI convention:
    wagoe <module> <command> [options]

  - <module> is a keyword name like user or billing.
  - If <module> is omitted, and only one enabled module exists, that module
    is used by default.
  - If <module> is omitted and multiple modules are enabled, an error is
    reported and exit code 1 is returned.
  - If the selected module is not enabled or has no runner, exit code 1
    is returned."
  [enabled-modules module->runner args]
  (let [[mod-token & rest-args] args
        mod-kw (when mod-token (keyword mod-token))
        enabled-set (set enabled-modules)
        ;; Determine target module and remaining args
        [target-module cli-args]
        (cond
          ;; Explicit module token
          (and mod-kw (enabled-set mod-kw))
          [mod-kw rest-args]

          ;; Explicit module token but not enabled
          mod-kw
          (do
            (log/error "Requested CLI module is not enabled" {:module mod-kw
                                                              :enabled-modules enabled-modules})
            [nil args])

          ;; No module token, single enabled module: default to it
          (= 1 (count enabled-set))
          [(first enabled-set) args]

          ;; No module token, multiple enabled modules
          :else
          (do
            (log/error "Multiple modules enabled; please specify <module> explicitly"
                       {:enabled-modules enabled-modules})
            [nil args]))]
    (cond
      (and target-module (contains? module->runner target-module))
      (let [status ((get module->runner target-module) cli-args)]
        ;; Ensure we always return an integer status code
        (if (integer? status) status 1))

      target-module
      (do
        (log/error "No CLI runner registered for module" {:module target-module})
        1)

      :else
      1)))

;; =============================================================================
;; Scaffolded module discovery (BOU-311)
;; =============================================================================
;;
;; `ig-config` builds from a fixed list of known module keys. A key the list has
;; never heard of — `:wagoe/tasks`, from `bb scaffold generate tasks` — was
;; skipped: no init-key, no route, no warning. `bb quickstart` reported 8/8 and
;; left the module on disk, compiled and unreachable.
;;
;; The framework's own modules go through `framework-module-config` below, which
;; asks each module for its own graph. This function covers what the scaffolder
;; generates, which is exactly the four keys `module_wiring.clj` defines.

(defn scaffolded-module-keys
  "The `:active` keys that declare a scaffolded module and are not already
   wired by the caller.

   A module is a `:wagoe/<name>` whose value carries `:enabled?`. Most of
   `:active` is configuration — `:wagoe/h2` names a database, `:wagoe/http` a
   port, `:wagoe/logging` an adapter — and treating every key as a module made
   the boot demand a `wagoe.h2.shell.module-wiring`. `:enabled?` is what
   `bb scaffold integrate` prints, so declaring a module and being discovered as
   one are the same act.

   A different namespace (`:wagoe.external/smtp`) is a provider's settings."
  [active known-keys]
  (->> active
       (filter (fn [[k v]]
                 (and (= "wagoe" (namespace k))
                      (map? v)
                      (contains? v :enabled?)
                      ;; Filtered here rather than only where the entries are
                      ;; built: `discovered-route-refs` reads this too, and a
                      ;; disabled module was still handed a ref to a routes key
                      ;; the config does not contain — a dangling ref, which
                      ;; fails the boot.
                      (not (false? (:enabled? v))))))
       (map key)
       (remove (set known-keys))
       sort))

(defn discover-module-config
  "Integrant entries for scaffolded modules named under `:active`.

   `wiring-loadable?` is injected so this stays testable without a module on the
   classpath; `-main` passes a function that requires the namespace.

   Throws when a module key names a wiring namespace that will not load. The
   silent skip is the whole defect: a typo in a key — `:wagoe/tsaks`, or the
   `:active`/`:inactive` mix-up — produced no signal at all."
  ([active known-keys base-ns wiring-loadable?]
   (discover-module-config active known-keys base-ns wiring-loadable? (constantly nil)))
  ([active known-keys base-ns wiring-loadable? resolve-var]
   (reduce
   (fn [acc k]
     (let [module    (name k)
           settings  (get active k)
           wiring    (symbol (str base-ns "." module ".shell.module-wiring"))
           loadable? (and (not (false? (:enabled? settings)))
                          (wiring-loadable? wiring))
           ;; A module that describes its own graph gets it built, exactly as a
           ;; framework module does. Without this a library outside
           ;; `framework-modules` could have only the four keys the scaffolder
           ;; writes — so a 31st library with a graph of its own still needed an
           ;; entry in a platform table, which is the coupling BOU-330 removes.
           ;;
           ;; Its routes need no special case: `discovered-route-refs` refs
           ;; `:wagoe/<name>-routes` by convention, and a module that has routes
           ;; names its component that.
           own-graph (when loadable?
                       (when-let [build (resolve-var (symbol (str wiring) "ig-config"))]
                         (build settings {:config {:active active}})))]
       (cond
         (false? (:enabled? settings))
         acc

         (not (wiring-loadable? wiring))
         (throw (ex-info
                 (str "No wiring for module " k ". Looked for " wiring ".\n"
                      "  Generate it:  bb scaffold generate --module-name " module " …\n"
                      "  Or remove " k " from :active in resources/conf/<env>/config.edn.\n"
                      "  A misspelled key looks exactly like this — check the spelling first.")
                 {:type       :wagoe/module-wiring-not-found
                  :module-key k
                  :namespace  (str wiring)}))

         own-graph
         (do (log/info "Discovered module with its own graph" {:module module})
             (merge acc (:components own-graph)))

         :else
         (do
           (log/info "Discovered scaffolded module" {:module module})
           (assoc acc
                  (keyword "wagoe" (str module "-repository"))
                  {:ctx (ig/ref :wagoe/db-context)}

                  (keyword "wagoe" (str module "-service"))
                  {:repository (ig/ref (keyword "wagoe" (str module "-repository")))}

                  (keyword "wagoe" (str module "-routes"))
                  {:service (ig/ref (keyword "wagoe" (str module "-service")))
                   :config  settings}

                  k
                  {:enabled? true
                   :service  (ig/ref (keyword "wagoe" (str module "-service")))
                   :routes   (ig/ref (keyword "wagoe" (str module "-routes")))})))))
    {}
    (scaffolded-module-keys active known-keys))))

(defn require-wiring!
  "Load `wiring-ns`. Returns true when it loaded, false when it does not exist,
   and rethrows anything else.

   The distinction matters: a wiring namespace that exists but throws on load —
   freshly generated code with a compile error, or a missing transitive dep — is
   not a missing module, and reporting it as one buries the real error under a
   suggestion to check the spelling."
  [wiring-ns]
  (try
    (require wiring-ns)
    true
    (catch java.io.FileNotFoundException _ false)
    (catch Exception e
      (throw (ex-info (str "Module wiring " wiring-ns " exists but failed to load: "
                           (.getMessage e))
                      {:type :wagoe/module-wiring-broken :namespace (str wiring-ns)}
                      e)))))

(defn discovered-route-refs
  "Integrant refs to the routes of every discovered module that has any.

   The HTTP handler cannot name a generated module, so it takes the routes as a
   collection — this is what the application puts in `:module-routes`.

   `built` is the config those modules produced, and the refs are filtered
   against it. Referencing `:wagoe/<name>-routes` by convention alone was safe
   only while every discovered module went through the scaffolder's four-key
   shape, which always builds one. A module with a graph of its own need not
   serve HTTP at all — a background-jobs library is exactly the kind of library
   this is meant to welcome — and a ref to a component it never built is a
   dangling ref, which Integrant refuses (BOU-330)."
  ([active known-keys] (discovered-route-refs active known-keys nil))
  ([active known-keys built]
   (into []
         (comp (map #(keyword "wagoe" (str (name %) "-routes")))
               (filter #(or (nil? built) (contains? built %)))
               (map ig/ref))
         (scaffolded-module-keys active known-keys))))

;; =============================================================================
;; Framework module assembly (BOU-326)
;; =============================================================================
;;
;; Every generated application used to carry the Integrant graph of every
;; framework module it enabled: 290 of the 404 lines of `config.clj` were
;; `:wagoe/tenant-repository {:ctx (ig/ref …)}` and its 40 siblings. This
;; repository kept a second copy of the same enumeration in
;; `src/wagoe/system_config.clj`.
;;
;; Two hand-maintained copies of a graph the module itself defines drift, and
;; both drifted the same way: `libs/workflow` documents and initialises
;; `:wagoe/workflow-db-schema`, which creates its tables — and neither copy
;; wired it. `wagoe add workflow` gave you a module whose tables never existed.
;;
;; So a module owns its own graph. `wagoe.<lib>.shell.module-wiring/ig-config`
;; returns it, this assembles what the config switches on, and an application
;; declares which modules it wants — not how they are built.

(def framework-modules
  "`:active` key -> the library that owns it.

   The wiring namespace is derived (`wagoe.<lib>.shell.module-wiring`) rather
   than listed, so a new module is one entry. Four keys predate the convention
   and cannot be derived from their own name; they are spelled out.

   These are keyword-to-string pairs, not namespace loads: platform still
   depends on none of these libraries, and `bb check:isolation` still passes."
  (into {:wagoe/payment-provider    "payments"
         :wagoe/ai-service          "ai"
         :wagoe/geo-service         "geo"
         :wagoe.external/smtp       "external"
         :wagoe.external/imap       "external"
         :wagoe.external/twilio     "external"
         :wagoe/dev-error-enricher  "devtools"}
        (map (juxt #(keyword "wagoe" %) identity))
        ["email" "i18n" "user" "cache" "tenant" "admin" "workflow" "search" "events"
         "push" "audience" "storage" "jobs" "realtime" "reports" "calendar"
         "ui-style"]))

(def always-on-modules
  "Modules every application gets whether or not its config mentions them.

   Both fall back to a working default — the logging email sender, an
   English-only catalogue — and the HTTP handler takes a ref to i18n's
   middleware unconditionally, so leaving them out is not an option an
   application has. They are modules rather than core components because the
   wiring lives in their own library, and platform depends on neither."
  #{:wagoe/email :wagoe/i18n})

(def optional-modules
  "Modules whose library may legitimately be absent at runtime.

   `:wagoe/dev-error-enricher` comes from wagoe-devtools, which lives in the
   `:repl` alias — so `clojure -M:run` against the dev config has the key and
   not the jar. A dev-only nicety must not stop the app from booting; every
   other missing library is a real error and throws."
  #{:wagoe/dev-error-enricher})

(defn- module-entries
  "The framework modules `active` switches on, as [key lib] pairs.

   `extra` names modules an application enables in code rather than in config —
   `wagoe new --no-user` is a generated literal, not a config key."
  [active extra]
  (->> framework-modules
       (filter (fn [[k _]] (or (contains? extra k)
                               (and (contains? active k)
                                    (not (false? (:enabled? (get active k))))))))
       (sort-by key)))

(defn- module-graph
  "Ask one module for its graph, or fall back to passing its settings through.

   A module with no components of its own — `:wagoe/storage`, `:wagoe/jobs` —
   defines no `ig-config`, and its `:active` value *is* its Integrant value.
   That default is why 13 of the 21 modules need no code at all."
  [resolve-var k lib settings ctx]
  (if-let [build (resolve-var (symbol (str "wagoe." lib ".shell.module-wiring") "ig-config"))]
    (build settings ctx)
    {:components {k settings}}))

(defn framework-module-config
  "Integrant entries for the framework modules `active` switches on.

   Returns `{:components {…} :http {…} :routes [..]}`. `:components` merges into
   the system config and `:http` into `:wagoe/http-handler` — that is how tenant
   contributes `:extra-middleware`. `:routes` is a vector of refs to modules'
   route components, which reach the handler as one collection rather than as a
   named slot per module (BOU-330).

   `ctx` is what a module may need from the application and cannot know:
   `:config` (the whole loaded map), `:validation-config`, and `:enabled` — the
   set of sibling modules that are on, so user-service can take a cache ref only
   when there is a cache.

   `load-wiring!` and `resolve-var` are injected so this is testable without a
   module on the classpath; `system-config` passes the real ones."
  [active {:keys [extra-modules] :as ctx} load-wiring! resolve-var]
  (let [enabled (into #{} (map key) (module-entries active extra-modules))
        ctx     (assoc ctx :enabled enabled)]
    (reduce
     (fn [acc [k lib]]
       (let [wiring (symbol (str "wagoe." lib ".shell.module-wiring"))]
         (cond
           (load-wiring! wiring)
           (let [{:keys [components http routes]} (module-graph resolve-var k lib (get active k) ctx)
                 clash (some (set (keys (:components acc))) (keys components))]
             (when clash
               (throw (ex-info (str "Two modules both wire " clash
                                    ". The later one would silently replace the earlier.")
                               {:type :wagoe/module-key-conflict :key clash :module k})))
             (-> acc
                 (update :components merge components)
                 (update :http merge http)
                 (update :routes into (or routes []))))

           (contains? optional-modules k)
           (do (log/info (str "Skipping " k ": " wiring " is not on this classpath.")
                         {:module k})
               acc)

           :else
           (throw (ex-info
                   (str "Module " k " is enabled but " wiring " is not on the classpath.\n"
                        "  Add the library to deps.edn, or remove " k
                        " from :active in resources/conf/<env>/config.edn.")
                   {:type :wagoe/module-library-missing :module-key k :namespace (str wiring)})))))
     {:components {} :http {} :routes []}
     (module-entries active extra-modules))))
