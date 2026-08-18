(ns wagoe.platform.shell.modules
  "Module registry and composition helpers for the Wagoe shell.

  This namespace provides utilities to:
  - Determine which modules are enabled based on configuration.
  - Compose route definitions from multiple modules.
  - Dispatch CLI commands to module-specific runners.

  For now, only the `:user` module is supported."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
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
;; The framework's own modules keep their bespoke wiring. Their keys do not all
;; follow the convention (`:wagoe/payment-provider`, `:wagoe.external/smtp`) and
;; several wire more than four components, so collapsing them is a separate job
;; (BOU-326). This covers what the scaffolder generates, which is exactly the
;; four keys `module_wiring.clj` defines.

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
                      (contains? v :enabled?))))
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
  [active known-keys base-ns wiring-loadable?]
  (reduce
   (fn [acc k]
     (let [module   (name k)
           settings (get active k)
           wiring   (symbol (str base-ns "." module ".shell.module-wiring"))]
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
   (scaffolded-module-keys active known-keys)))

(defn require-wiring!
  "Load `wiring-ns`, returning true when it exists. The default predicate for
   `discover-module-config`; separate so tests can substitute one."
  [wiring-ns]
  (try
    (require wiring-ns)
    true
    (catch Exception _ false)))
