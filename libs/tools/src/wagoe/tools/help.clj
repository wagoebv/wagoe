#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/help.clj
;;
;; Contextual Help — state-aware guidance and reference for Wagoe projects.
;;
;; Usage (via bb.edn task):
;;   bb guide                    # General help listing all commands
;;   bb guide next               # State-aware guidance (what to do next)
;;   bb guide <topic>            # Detailed help for a topic
;;   bb guide error BND-xxx      # Look up an error code

(ns wagoe.tools.help
  (:require [wagoe.tools.check :as check]
            [wagoe.tools.db :as db]
            [wagoe.tools.report :as report]
            [wagoe.tools.ansi :refer [bold green red yellow dim cyan]]
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
   wagoe-tools without wagoe-devtools on the classpath. The catalogue
   ships inside the wagoe-tools jar (see build.clj), so this fallback only
   triggers on a broken classpath. (BOU-76)"
  [resource]
  (if resource
    (-> resource slurp edn/read-string)
    {}))

(def error-catalog
  "Delay of {BND-xxx code → {:code :category :title :description :fix}}.

   Loaded lazily (not at namespace-load time) and degrades gracefully to {}
   when the resource is missing — see read-catalog. Deref with @error-catalog.
   The EDN is packaged into the wagoe-tools jar and also resolves from
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
  (println "The scaffolder writes modules, entities, fields and endpoints in the FC/IS layout, with tests and migrations.")
  (println)
  (println (cyan "Generate:"))
  (println "  bb scaffold                                           # Interactive wizard")
  (println "  bb scaffold generate --module-name billing --entity Invoice --field number:string:required")
  (println "  bb scaffold entity --module-name billing --entity InvoiceLineItem --belongs-to invoice \\")
  (println "                     --field description:string:required")
  (println "  bb scaffold field --module-name billing --entity Invoice --name due --type date")
  (println "  bb scaffold endpoint --module-name billing --path /invoices/export --method get --handler-name export")
  (println "  bb scaffold ai \"product module with name, price\"      # From a description; --yes skips the prompt")
  (println)
  (println (cyan "Then:"))
  (println "  bb scaffold integrate billing                         # Write its config key (--dry-run to preview)")
  (println "  bb migrate up                                         # Create its tables")
  (println)
  (println (dim "generate and entity add a commented example per entity to resources/seeds/dev.edn (bb guide seed)."))
  (println (dim "bb scaffold <command> --help lists the flags.")))

(defn- help-topic-testing []
  (println (bold "Testing Guide"))
  (println)
  (println (cyan "Run tests:"))
  (println "  clojure -M:test                                 # Every suite in tests.edn")
  (println "  clojure -M:test --focus-meta :unit              # One tier")
  (println "  clojure -M:test --focus my-app.billing.core.invoice-test   # One namespace")
  (println "  clojure -M:test --watch                         # Re-run on change")
  (println)
  (println (cyan "Test categories:"))
  (println "  ^:unit          Pure core functions, no mocks needed")
  (println "  ^:integration   Shell services with mocked adapters")
  (println "  ^:contract      Adapters against real DB (H2 in-memory)")
  (println "  ^:security      Security-focused tests (error mapping, CSRF, XSS, SQL)")
  (println)
  (println (cyan "AI-assisted (experimental):"))
  (println "  bb ai gen-tests src/my_app/billing/core/invoice.clj  # Draft a test namespace")
  (println)
  (println (cyan "Quality gates:"))
  (println "  bb check                                              # Every gate that applies here")
  (println "  bb check:fcis                                         # FC/IS enforcement")
  (println "  bb check:placeholder-tests                            # Detect placeholder tests")
  (println)
  (println (dim "Suites are defined in tests.edn; each test carries one tier tag.")))

(defn- help-topic-database []
  (println (bold "Database Guide"))
  (println)
  (println (cyan "Migrations:"))
  (println "  bb migrate up                                         # Run pending migrations")
  (println "  bb migrate status                                     # What has run")
  (println "  bb migrate create add-due-date                        # A new up/down migration pair")
  (println)
  (println (cyan "Development database:"))
  (println "  bb db:status                                          # Config and migration files")
  (println "  bb db:reset                                           # Drop the app's tables, migrate (dev, test, acc)")
  (println "  bb db:seed                                            # Insert resources/seeds/dev.edn (bb guide seed)")
  (println "  bb create-admin                                       # After a reset, which drops the users")
  (println)
  (println (cyan "Case convention:"))
  (println "  Clojure and API   kebab-case   :password-hash, :created-at")
  (println "  Database          snake_case   password_hash, created_at")
  (println)
  (println (cyan "Adding a field:"))
  (println "  bb scaffold field --module-name billing --entity Invoice --name due --type date")
  (println "  It writes the migration and the schema entry; then run bb migrate up.")
  (println)
  (println (cyan "SQL generation (experimental):"))
  (println "  bb ai sql \"find active users with orders\"             # HoneySQL from a description")
  (println)
  (println (dim "Convert at the database boundary with wagoe.core.utils.case-conversion.")))

(defn- help-topic-fcis []
  (println (bold "Functional Core / Imperative Shell (FC/IS)"))
  (println)
  (println "Every module, yours under src/ and the framework's, has the same layout:")
  (println)
  (println (cyan "Structure:"))
  (println "  <module>/")
  (println "  +-- core/        Pure functions: no I/O, no logging, no throw, no atoms")
  (println "  +-- shell/       Side effects: persistence, services, HTTP handlers")
  (println "  +-- ports.clj    Protocol definitions (interfaces)")
  (println "  +-- schema.clj   Malli validation schemas")
  (println)
  (println (cyan "Dependency rules (enforced):"))
  (println (green  "  Shell -> Core     allowed"))
  (println (green  "  Shell -> Ports    allowed"))
  (println (green  "  Core  -> Ports    allowed"))
  (println (red    "  Core  -> Shell    never"))
  (println)
  (println "  core/ requires only pure libraries (clojure.string, malli, honey.sql, ...) and its own")
  (println "  core, schema and ports. It returns {:error {:type ... :message ...}} instead of throwing.")
  (println)
  (println (cyan "Enforcement:"))
  (println "  bb check:fcis                                         # Check for violations")
  (println "  bb check:ports                                        # Every module has a ports.clj")
  (println)
  (println (dim "Core functions are pure: the same input always gives the same output.")))

(defn- help-topic-config []
  (println (bold "Configuration Guide"))
  (println)
  (println (cyan "Config files:"))
  (println "  resources/conf/dev/config.edn     Development (WAG_ENV=dev, the default)")
  (println "  resources/conf/test/config.edn    Tests")
  (println "  resources/conf/prod/config.edn    Production; `bb setup --prod true` writes it")
  (println "  resources/conf/<profile>/         Any other profile, acc for one: WAG_ENV=<profile>")
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
  (println "  Aero         Config reader: #env, #or and #include tags")
  (println "  Integrant    Dependency injection and lifecycle management")
  (println "  :active      The modules that are on; a key under :inactive is off")
  (println)
  (println (dim "AGENTS.md describes each module's configuration.")))

(defn- help-topic-seed []
  (println (bold "Seed Data Guide"))
  (println)
  (println "bb db:seed inserts resources/seeds/dev.edn in one transaction: all of it or none.")
  (println "It runs where bb db:reset does: dev, test and acc (development, testing, acceptance).")
  (println "--force overrides that; bb db:seed path/to/other.edn seeds another file.")
  (println)
  (println (cyan "The file — tables in order, parents first:"))
  (println "  [[:invoices")
  (println "    [{:id :invoice/acme :number \"INV-1\" :status :entered}]]")
  (println "   [:invoice-line-items")
  (println "    [{:invoice-id :invoice/acme :description \"Consulting\" :quantity 3}]]]")
  (println)
  (println (cyan "What the seeder fills in:"))
  (println "  :id                      Left out: a random uuid, when the table's id is a uuid.")
  (println "  :id :invoice/acme        A symbolic id: the row gets a new uuid, and an id column")
  (println "                           (:invoice-id, any *-id) holding :invoice/acme gets it. A child")
  (println "                           names its parent this way; elsewhere a keyword is text.")
  (println "  :created-at :updated-at  Left out: now, when the table has the column.")
  (println "  :status :entered         A keyword is stored as text; a row with a workflow starts it")
  (println "                           in that state.")
  (println "  Explicit ids and timestamps still work: \"11111111-0000-4000-8000-000000000001\".")
  (println)
  (println (cyan "Names:"))
  (println "  Tables and columns are kebab-case; they are stored snake_case.")
  (println "  Past 8 tables, use the vector form shown above: a larger map loses its order.")
  (println)
  (println (cyan "Getting started:"))
  (println "  bb scaffold generate and bb scaffold entity add a commented example per entity.")
  (println "  Uncomment it, edit it, then bb db:seed.")
  (println "  After bb db:reset, run bb create-admin: the reset drops the users."))

(def topic-fns
  {"scaffold" help-topic-scaffold
   "testing"  help-topic-testing
   "database" help-topic-database
   "fcis"     help-topic-fcis
   "config"   help-topic-config
   "seed"     help-topic-seed})

;; =============================================================================
;; General help
;; =============================================================================

(defn- help-general []
  (println)
  (println (bold "Wagoe CLI — Available Commands"))
  (println)
  (println (cyan "Scaffolding:"))
  (println "  bb scaffold                          Interactive module scaffolding wizard")
  (println "  bb scaffold ai \"description\"          AI-powered scaffolding from NL")
  (println "  bb scaffold integrate <module>        Write a scaffolded module's config key")
  (println)
  (println (cyan "AI Tools:"))
  (println "  bb ai explain --file stacktrace.txt   Explain a Clojure/Wagoe error (experimental)")
  (println "  bb ai gen-tests <file>                Generate test namespace (experimental)")
  (println "  bb ai sql \"description\"               Generate HoneySQL from NL (experimental)")
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
  (println "  bb check                              Every gate that applies here")
  (println "  bb check --quick                      Fast subset (FC/IS, ports, dependency direction)")
  (println "  bb check --fix                        Auto-fix what can be fixed")
  (println "  bb doctor                             Validate config for common mistakes")
  (println "  bb doctor:env                         Check environment prerequisites")
  (println "  bb doctor --all                       Run both config + environment checks")
  (println "  bb check:fcis                         FC/IS enforcement check")
  (println "  bb check:placeholder-tests            Detect placeholder test assertions")
  (println "  bb check-links                        Validate local markdown links")
  (println "  bb smoke-check                        Verify deps.edn aliases and tools")
  (println)
  (println (cyan "Database:"))
  (println "  bb db:status                          Show config and migration status")
  (println "  bb db:reset                           Drop the app's tables + migrate (dev, test, acc)")
  (println "  bb db:seed                            Seed database from dev.edn")
  (println)
  ;; Publishing is a maintainer's job in the Wagoe repository. A generated
  ;; project has no `bb deploy` task — it was removed in BOU-325, because it
  ;; publishes *Wagoe's* libraries to Clojars — so printing it here told users
  ;; to run a command they do not have, about artifacts that are not theirs.
  (when (check/framework-repo?)
    (println (cyan "Deployment:"))
    (println "  bb deploy --all                       Deploy all libraries to Clojars")
    (println "  bb deploy --missing                   Deploy only missing libraries")
    (println "  bb deploy core platform user          Deploy specific libraries")
    (println))
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

(defn- check-unintegrated-modules
  "Find libs/ directories the project's deps.edn never mentions.

   Only meaningful in a generated project, where libs/ holds modules you
   scaffolded and a module has to be in deps.edn to be used. The framework
   repository puts its 31 libraries on `:paths`, not in `:deps`, so asking this
   question there reported every standalone library as unintegrated — it named
   wagoe-cli and wagoe-mcp and told the reader to run `bb scaffold integrate` on
   them, which is not a thing you should do to either (BOU-324).

   A hand-maintained list of libraries to skip is what produced that: it had
   \"tools\", \"devtools\" and \"e2e\" and had never heard of the two added since."
  []
  (let [deps-file (io/file (root-dir) "deps.edn")]
    (cond
      (check/framework-repo?)
      [{:level :pass
        :msg   "Framework repository — libraries are on :paths, not :deps"}]

      (not (.exists deps-file))
      [{:level :warn :msg "deps.edn not found — cannot check module integration"}]

      :else
      (let [deps-text    (slurp deps-file)
            modules      (lib-dirs)
            unintegrated (remove #(integrated? deps-text %) modules)]
        (cond
          (empty? modules)
          [{:level :pass :msg "No modules under libs/ yet"}]

          (seq unintegrated)
          [{:level :warn
            :msg   (str "Modules in libs/ that deps.edn does not mention: "
                        (str/join ", " unintegrated))
            :fix   "Run `bb scaffold integrate <module>` to wire them in."}]

          :else
          [{:level :pass
            :msg   (str "All " (count modules) " modules are integrated in deps.edn")}])))))

(defn- check-migrations
  "Report the project's migration files.

   Absence is not a problem to fix. `wagoe new` writes no migrations/,
   and the user module creates its four tables through
   `:wagoe/user-db-schema` rather than a migration — so a fresh project has
   nothing here and is working correctly. Warning about it gave every new
   project an item it could only clear by creating a directory it did not need,
   and the suggested fix (`clojure -M:migrate up`) does nothing without one
   (BOU-324)."
  ([] (check-migrations (root-dir)))
  ([root]
   (let [{:keys [dir files shadowed]} (db/migration-layout root)]
     (cond
       shadowed
       [{:level :error
         :msg   (str (count shadowed) " file(s) in migrations/ are never read — " dir " captures the name")
         :fix   "Keep every migration in one directory; `clojure -M:migrate up` refuses this split."}]

       (seq files)
       [{:level :pass
         :msg   (str (count files) " migration file(s) found in " dir)}]

       files
       [{:level :pass
         :msg   (str dir " is empty (add .sql files, then `clojure -M:migrate up`)")}]

       :else
       [{:level :pass
         :msg   (str "No migrations yet (optional — put .sql files in " dir ")")}]))))

(defn- check-seeds
  "Check if dev seed data file exists."
  []
  (let [seed-file (io/file (root-dir) "resources" "seeds" "dev.edn")]
    (if (.exists seed-file)
      [{:level :pass
        :msg   "Dev seed file exists (resources/seeds/dev.edn)"}]
      ;; A pass, not a warning. Seed data is optional — `bb db:seed` reads this
      ;; file if it is there — so a project without one is healthy, and a
      ;; warning that can never be cleared is what teaches a reader to skim
      ;; past the ones that matter (BOU-324).
      [{:level :pass
        :msg   "No dev seed file (optional — `bb db:seed` reads resources/seeds/dev.edn)"}])))

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

(defn- next-results []
  (concat (check-config-exists)
          (check-unintegrated-modules)
          (check-migrations)
          (check-seeds)))

(defn- help-next-text []
  (println)
  (println (bold "Wagoe — What To Do Next"))
  (println)
  (let [results (next-results)
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

(defn- help-next [& {:keys [edn?]}]
  (if edn?
    (report/print-edn :project (next-results))
    (help-next-text)))

;; =============================================================================
;; Error code lookup
;; =============================================================================

(defn- catalog-unavailable []
  (println)
  (println (yellow "Error catalogue not available on the classpath."))
  (println (dim "  The wagoe-tools jar ships wagoe/devtools/error_catalog.edn;"))
  (println (dim "  inside the monorepo it loads from libs/devtools/resources via bb.edn :paths."))
  (println (dim "  Reinstall/upgrade wagoe-tools, or add libs/devtools/resources to bb.edn :paths.")))

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
    "next"  (help-next :edn? (report/edn-requested? args))
    "error" (help-error (second args))
    nil     (help-general)
    (help-topic (first args))))

;; Run when executed directly (not via bb.edn task)
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
