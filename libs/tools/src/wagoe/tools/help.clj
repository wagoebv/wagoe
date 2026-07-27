#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/help.clj
;;
;; Contextual Help — state-aware guidance and reference for Boundary projects.
;;
;; Usage (via bb.edn task):
;;   bb guide                    # General help listing all commands
;;   bb guide next               # State-aware guidance (what to do next)
;;   bb guide <topic>            # Detailed help for a topic
;;   bb guide error BND-xxx      # Look up an error code

(ns wagoe.tools.help
  (:require [wagoe.tools.ansi :refer [bold green red yellow dim cyan]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; Error code catalog — loaded from shared EDN (single source of truth)
;; =============================================================================

(defn- read-catalog
  "Parse the error catalogue from a classpath resource (or nil).

   Returns {} when the resource is absent instead of throwing, so that
   `wagoe.tools.help` loads — and every unrelated bb task (doctor,
   scaffold, …) starts — even in a consumer project that depends on
   boundary-tools without boundary-devtools on the classpath. The catalogue
   ships inside the boundary-tools jar (see build.clj), so this fallback only
   triggers on a broken classpath. (BOU-76)"
  [resource]
  (if resource
    (-> resource slurp edn/read-string)
    {}))

(def error-catalog
  "Delay of {BND-xxx code → {:code :category :title :description :fix}}.

   Loaded lazily (not at namespace-load time) and degrades gracefully to {}
   when the resource is missing — see read-catalog. Deref with @error-catalog.
   The EDN is packaged into the boundary-tools jar and also resolves from
   libs/devtools/resources via bb.edn :paths inside the monorepo."
  (delay (read-catalog (io/resource "wagoe/devtools/error_catalog.edn"))))

(def ^:private category-order
  "Display order matching BND-1xx..7xx numerical range scheme."
  [:config :validation :persistence :auth :interceptor :fcis :tooling])

(def ^:private category-label
  {:config      "Configuration"
   :validation  "Validation"
   :persistence "Persistence"
   :auth        "Auth"
   :interceptor "Interceptor"
   :fcis        "FC/IS"
   :tooling     "Tooling"})

;; =============================================================================
;; Topic help content
;; =============================================================================

(defn- help-topic-scaffold []
  (println (bold "Scaffolding Guide"))
  (println)
  (println "Boundary includes a scaffolding wizard to generate modules, fields, endpoints, and adapters.")
  (println)
  (println (cyan "Interactive mode:"))
  (println "  bb scaffold                                           # Launch interactive wizard")
  (println)
  (println (cyan "AI-powered scaffolding:"))
  (println "  bb scaffold ai \"product module with name, price\"      # Generate from description")
  (println "  bb scaffold ai \"product module\" --yes                 # Skip confirmation prompts")
  (println)
  (println (cyan "Integration:"))
  (println "  bb scaffold integrate product                         # Wire module into deps/tests/wiring")
  (println "  bb scaffold integrate product --dry-run               # Preview changes only")
  (println)
  (println (dim "After scaffolding, always run `bb scaffold integrate <module>` to wire it up."))
  (println (dim "See libs/scaffolder/AGENTS.md for full documentation.")))

(defn- help-topic-testing []
  (println (bold "Testing Guide"))
  (println)
  (println (cyan "Run tests:"))
  (println "  clojure -M:test                                 # All tests")
  (println "  clojure -M:test :core                           # Single library")
  (println "  clojure -M:test --focus-meta :unit              # By metadata")
  (println "  clojure -M:test --focus ns-name-test            # Single namespace")
  (println "  clojure -M:test --watch :core                   # Watch mode")
  (println)
  (println (cyan "Test categories:"))
  (println "  ^:unit          Pure core functions, no mocks needed")
  (println "  ^:integration   Shell services with mocked adapters")
  (println "  ^:contract      Adapters against real DB (H2 in-memory)")
  (println "  ^:security      Security-focused tests (error mapping, CSRF, XSS, SQL)")
  (println)
  (println (cyan "AI-assisted:"))
  (println "  bb ai gen-tests libs/user/src/wagoe/user/core/validation.clj")
  (println)
  (println (cyan "Quality gates:"))
  (println "  bb check:fcis                                         # FC/IS enforcement")
  (println "  bb check:placeholder-tests                            # Detect placeholder tests")
  (println "  bb check:deps                                         # Dependency direction check")
  (println)
  (println (dim "Test suites are defined in tests.edn. Each library has its own :id.")))

(defn- help-topic-database []
  (println (bold "Database Guide"))
  (println)
  (println (cyan "Migrations:"))
  (println "  clojure -M:migrate up                                 # Run pending migrations")
  (println)
  (println (cyan "SQL generation:"))
  (println "  bb ai sql \"find active users with orders\"             # Generate HoneySQL from NL")
  (println)
  (println (cyan "Case convention:"))
  (println "  Clojure code    kebab-case   :password-hash, :created-at")
  (println "  Database        snake_case   password_hash, created_at")
  (println "  API boundary    camelCase    passwordHash, createdAt")
  (println)
  (println (cyan "Adding a new field:"))
  (println "  1. Add Malli schema in schema.clj")
  (println "  2. Create database migration")
  (println "  3. Update persistence layer in shell/persistence.clj")
  (println)
  (println (dim "Use wagoe.shared.core.utils.case-conversion for conversions.")))

(defn- help-topic-fcis []
  (println (bold "Functional Core / Imperative Shell (FC/IS)"))
  (println)
  (println "Every library in libs/ follows the FC/IS architecture pattern:")
  (println)
  (println (cyan "Structure:"))
  (println "  libs/{library}/src/wagoe/{library}/")
  (println "  +-- core/        Pure functions ONLY (no I/O, no logging, no exceptions)")
  (println "  +-- shell/       All side effects (persistence, services, HTTP handlers)")
  (println "  +-- ports.clj    Protocol definitions (interfaces)")
  (println "  +-- schema.clj   Malli validation schemas")
  (println)
  (println (cyan "Dependency rules (strictly enforced):"))
  (println (green  "  Shell -> Core     allowed"))
  (println (green  "  Core  -> Ports    allowed"))
  (println (red    "  Core  -> Shell    NEVER (violates FC/IS)"))
  (println)
  (println (cyan "Enforcement:"))
  (println "  bb check:fcis                                         # Check for violations")
  (println)
  (println (dim "Core functions should be pure: same input always gives same output.")))

(defn- help-topic-config []
  (println (bold "Configuration Guide"))
  (println)
  (println (cyan "Config files:"))
  (println "  resources/conf/dev/config.edn     Development")
  (println "  resources/conf/test/config.edn    Testing")
  (println "  resources/conf/prod/config.edn    Production")
  (println "  resources/conf/acc/config.edn     Acceptance")
  (println)
  (println (cyan "Setup:"))
  (println "  bb setup                                              # Interactive wizard")
  (println "  bb setup ai \"PostgreSQL with Stripe\"                  # AI-powered setup")
  (println "  bb setup --database postgresql --payment stripe       # Non-interactive")
  (println)
  (println (cyan "Validation:"))
  (println "  bb doctor                                             # Check dev config")
  (println "  bb doctor --env all                                   # Check all environments")
  (println "  bb doctor --env all --ci                              # CI mode (exit on error)")
  (println)
  (println (cyan "Key concepts:"))
  (println "  Aero         Config reader with #env, #or, #profile tags")
  (println "  Integrant    Dependency injection and lifecycle management")
  (println "  :active      Map of modules that are enabled")
  (println)
  (println (dim "See libs/platform/AGENTS.md for HTTP/system configuration details.")))

(def topic-fns
  {"scaffold" help-topic-scaffold
   "testing"  help-topic-testing
   "database" help-topic-database
   "fcis"     help-topic-fcis
   "config"   help-topic-config})

;; =============================================================================
;; General help
;; =============================================================================

(defn- help-general []
  (println)
  (println (bold "Boundary CLI — Available Commands"))
  (println)
  (println (cyan "Scaffolding:"))
  (println "  bb scaffold                          Interactive module scaffolding wizard")
  (println "  bb scaffold ai \"description\"          AI-powered scaffolding from NL")
  (println "  bb scaffold integrate <module>        Wire scaffolded module into project")
  (println)
  (println (cyan "AI Tools:"))
  (println "  bb ai explain --file stacktrace.txt   Explain a Clojure/Boundary error")
  (println "  bb ai gen-tests <file>                Generate test namespace")
  (println "  bb ai sql \"description\"               Generate HoneySQL from NL")
  (println "  bb ai docs --module <lib> --type agents  Generate AGENTS.md")
  (println "  bb ai admin-entity \"description\"      Generate admin entity config")
  (println)
  (println (cyan "Project Setup:"))
  (println "  bb quickstart                         Zero-to-running-app setup")
  (println "  bb quickstart --preset minimal        Non-interactive (minimal/standard/sqlite/mysql)")
  (println "  bb setup                              Interactive config setup wizard")
  (println "  bb setup ai \"description\"             AI-powered config setup")
  (println "  bb create-admin                       Create first admin user")
  (println)
  (println (cyan "Quality & Validation:"))
  (println "  bb check                              Run ALL quality checks (recommended)")
  (println "  bb check --quick                      Fast subset (FC/IS + deps only)")
  (println "  bb check --fix                        Auto-fix what can be fixed")
  (println "  bb doctor                             Validate config for common mistakes")
  (println "  bb doctor:env                         Check environment prerequisites")
  (println "  bb doctor --all                       Run both config + environment checks")
  (println "  bb check:fcis                         FC/IS enforcement check")
  (println "  bb check:placeholder-tests            Detect placeholder test assertions")
  (println "  bb check:deps                         Verify library dependency direction")
  (println "  bb check-links                        Validate local markdown links")
  (println "  bb smoke-check                        Verify deps.edn aliases and tools")
  (println)
  (println (cyan "Database:"))
  (println "  bb db:status                          Show config and migration status")
  (println "  bb db:reset                           Drop + recreate + migrate (with confirmation)")
  (println "  bb db:seed                            Seed database from dev.edn")
  (println)
  (println (cyan "Deployment:"))
  (println "  bb deploy --all                       Deploy all libraries to Clojars")
  (println "  bb deploy --missing                   Deploy only missing libraries")
  (println "  bb deploy core platform user          Deploy specific libraries")
  (println)
  (println (cyan "Utilities:"))
  (println "  bb install-hooks                      Configure git hooks")
  (println)
  (println (cyan "Help:"))
  (println "  bb guide                               This listing")
  (println "  bb guide next                          State-aware guidance (what to do next)")
  (println "  bb guide <topic>                       Detailed help for a topic")
  (println "  bb guide error BND-xxx                 Look up an error code")
  (println)
  (println (dim (str "Topics: " (str/join ", " (sort (keys topic-fns)))))))

;; =============================================================================
;; State-aware guidance (help next)
;; =============================================================================

(defn- root-dir [] (System/getProperty "user.dir"))

(defn- lib-dirs
  "List subdirectory names under libs/."
  []
  (let [d (io/file (root-dir) "libs")]
    (when (.exists d)
      (->> (.listFiles d)
           (filter #(.isDirectory %))
           (map #(.getName %))
           sort))))

(defn- integrated?
  "Check if a library is referenced in deps.edn."
  [deps-text module]
  (str/includes? deps-text (str "libs/" module "/src")))

(def ^:private non-module-libs
  "Directories under libs/ that are not application modules and should not
   be checked for integration in deps.edn. These are consumed through other
   mechanisms (e.g., bb.edn for tools, dev alias for devtools, e2e alias)."
  #{"tools" "devtools" "e2e"})

(defn- check-unintegrated-modules
  "Find libs/ directories that are not referenced in deps.edn.
   Excludes known non-module libraries (tools, devtools, e2e)."
  []
  (let [deps-file (io/file (root-dir) "deps.edn")]
    (if-not (.exists deps-file)
      [{:level :warn :msg "deps.edn not found — cannot check module integration"}]
      (let [deps-text    (slurp deps-file)
            modules      (remove non-module-libs (lib-dirs))
            unintegrated (remove #(integrated? deps-text %) modules)]
        (if (seq unintegrated)
          [{:level :warn
            :msg   (str "Unintegrated modules (in libs/ but not in deps.edn): "
                        (str/join ", " unintegrated))
            :fix   "Run `bb scaffold integrate <module>` to wire them in, or verify they are standalone libraries."}]
          [{:level :pass
            :msg   (str "All " (count modules) " modules are integrated in deps.edn")}])))))

(defn- check-migrations
  "Check for pending migration files."
  []
  (let [migration-dir (io/file (root-dir) "resources" "migrations")]
    (if-not (.exists migration-dir)
      [{:level :warn
        :msg   "No resources/migrations/ directory found"
        :fix   "Create resources/migrations/ and add migration files, or run `clojure -M:migrate up`."}]
      (let [files (->> (.listFiles migration-dir)
                       (filter #(.isFile %))
                       vec)]
        (if (empty? files)
          [{:level :warn
            :msg   "No migration files found in resources/migrations/"
            :fix   "Add migration SQL files if you have database tables to create."}]
          [{:level :pass
            :msg   (str (count files) " migration file(s) found in resources/migrations/")}])))))

(defn- check-seeds
  "Check if dev seed data file exists."
  []
  (let [seed-file (io/file (root-dir) "resources" "seeds" "dev.edn")]
    (if (.exists seed-file)
      [{:level :pass
        :msg   "Dev seed file exists (resources/seeds/dev.edn)"}]
      [{:level :warn
        :msg   "No dev seed file found at resources/seeds/dev.edn"
        :fix   "Create resources/seeds/dev.edn with sample data for local development."}])))

(defn- check-config-exists
  "Check that at least dev config exists."
  []
  (let [dev-config (io/file (root-dir) "resources" "conf" "dev" "config.edn")]
    (if (.exists dev-config)
      [{:level :pass
        :msg   "Dev config exists (resources/conf/dev/config.edn)"}]
      [{:level :warn
        :msg   "No dev config found at resources/conf/dev/config.edn"
        :fix   "Run `bb setup` to create your configuration."}])))

(defn- format-next-result [{:keys [level msg fix]}]
  (let [icon (case level
               :pass (green "✓")
               :warn (yellow "⚠")
               :error (red "✗"))]
    (str "  " icon " " msg
         (when fix
           (str "\n" (dim (str "    Fix: " fix)))))))

(defn- help-next []
  (println)
  (println (bold "Boundary — What To Do Next"))
  (println)
  (let [results (concat (check-config-exists)
                        (check-unintegrated-modules)
                        (check-migrations)
                        (check-seeds))
        passes (count (filter #(= :pass (:level %)) results))
        warns  (count (filter #(= :warn (:level %)) results))]
    (doseq [r results]
      (println (format-next-result r)))
    (println)
    (if (zero? warns)
      (println (green "Everything looks good! Run `bb doctor` for deeper config validation."))
      (println (str (yellow (str warns " item" (when (not= warns 1) "s") " need attention"))
                    ", " (green (str passes " OK"))
                    ". " (dim "Fix the warnings above to get started."))))))

;; =============================================================================
;; Error code lookup
;; =============================================================================

(defn- catalog-unavailable []
  (println)
  (println (yellow "Error catalogue not available on the classpath."))
  (println (dim "  The boundary-tools jar ships boundary/devtools/error_catalog.edn;"))
  (println (dim "  inside the monorepo it loads from libs/devtools/resources via bb.edn :paths."))
  (println (dim "  Reinstall/upgrade boundary-tools, or add libs/devtools/resources to bb.edn :paths.")))

(defn- help-error [code]
  (let [catalog @error-catalog]
    (cond
      (empty? catalog) (catalog-unavailable)

      (not code)
      (do
        (println)
        (println (bold "Error Code Reference"))
        (println)
        (println (dim "Ranges:"))
        (println "  BND-1xx   Configuration (missing env vars, invalid providers, bad config)")
        (println "  BND-2xx   Validation (Malli schema failures, type mismatches)")
        (println "  BND-3xx   Persistence (SQL errors, migration issues, connection problems)")
        (println "  BND-4xx   Auth (JWT failures, session issues, permission denied)")
        (println "  BND-5xx   Interceptor pipeline (missing interceptors, execution errors)")
        (println "  BND-6xx   FC/IS violations (core importing shell, side effects in core)")
        (println "  BND-7xx   Tooling (circular deps, admin config, wiring issues)")
        (println)
        (let [by-cat (group-by :category (vals catalog))]
          (doseq [cat category-order
                  :let [codes (sort-by :code (get by-cat cat []))]
                  :when (seq codes)]
            (println (str "  " (bold (get category-label cat (name cat))) ":"))
            (doseq [{:keys [code title]} codes]
              (println (str "    " (cyan code) "  " title)))))
        (println)
        (println (dim "Usage: bb guide error BND-xxx")))

      :else
      (let [upper-code (str/upper-case code)
            entry      (get catalog upper-code)]
        (println)
        (if-not entry
          (do
            (println (red (str "Unknown error code: " upper-code)))
            (println)
            (println (dim "Known ranges: BND-1xx config, BND-2xx validation, BND-3xx persistence,"))
            (println (dim "              BND-4xx auth, BND-5xx interceptor, BND-6xx FC/IS, BND-7xx tooling"))
            (println (dim "Run `bb guide error` (no code) for the full listing.")))
          (do
            (println (bold (str upper-code " — " (:title entry))))
            (println)
            (println (cyan "What happened:"))
            (println (str "  " (:description entry)))
            (println)
            (println (cyan "How to fix:"))
            (println (str "  " (:fix entry)))))))))

;; =============================================================================
;; Topic help dispatcher
;; =============================================================================

(defn- help-topic [topic]
  (let [t (str/lower-case topic)]
    (if-let [f (get topic-fns t)]
      (do (println) (f))
      (do
        (println)
        (println (red (str "Unknown topic: " topic)))
        (println)
        (println (str "Available topics: " (str/join ", " (sort (keys topic-fns)))))
        (println (dim "Usage: bb guide <topic>"))))))

;; =============================================================================
;; Main entry point
;; =============================================================================

(defn -main [& args]
  (case (first args)
    "next"  (help-next)
    "error" (help-error (second args))
    nil     (help-general)
    (help-topic (first args))))

;; Run when executed directly (not via bb.edn task)
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
