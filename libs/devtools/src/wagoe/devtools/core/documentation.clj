(ns wagoe.devtools.core.documentation
  "Pure documentation catalog for in-REPL exploration.
   Provides (doc :topic) data for Wagoe concepts with examples.
   No I/O, no logging — pure data and transformations."
  (:require [clojure.string :as str]))

;; =============================================================================
;; Documentation catalog
;; =============================================================================

(def ^:private catalog
  "Map of topic keywords to documentation entries."
  {:scaffold
   {:title    "Scaffolding"
    :body     "Generate a full module skeleton with schema, persistence, HTTP handlers and tests.\n\n  bb scaffold generate          # Interactive wizard\n  bb scaffold generate product  # Non-interactive\n  bb scaffold ai \"product module with name, price, stock\"  # AI-powered\n\nPost-scaffold steps:\n  1. Review schema:  libs/{module}/src/wagoe/{module}/schema.clj\n  2. Wire module:    bb scaffold integrate {module}\n  3. Add migration:  bb migrate create add-{module}-table\n  4. Run tests:      clojure -M:test :{module}\n\nREPL exploration after wiring:\n  (routes :module)    ; Show all routes for the scaffolded module"
    :see-also [:fcis :testing :routes]}

   :interceptors
   {:title    "HTTP Interceptors"
    :body     "Interceptors wrap every HTTP request/response cycle with :enter/:leave/:error phases.\n\nDefault interceptors on every route:\n  - correlation-id   Attach a unique request ID\n  - logging          Structured request/response logging\n  - exception        Catch and normalize errors\n  - coercion         Coerce and validate request/response data\n\nContext keys available inside interceptors:\n  :request        Ring request map\n  :response       Ring response map (set in :leave)\n  :route          Reitit route match data\n  :system         Integrant system component map\n  :correlation-id Unique request identifier string\n  :started-at     System timestamp at request start\n\nExample interceptor:\n  {:name    ::my-interceptor\n   :enter   (fn [ctx] (assoc ctx :my-key \"value\"))\n   :leave   (fn [ctx] ctx)\n   :error   (fn [ctx ex] ctx)}"
    :see-also [:routes :fcis]}

   :fcis
   {:title    "Functional Core / Imperative Shell (FC/IS)"
    :body     "Strict architectural boundary separating pure logic from side effects.\n\n  core/   Pure functions ONLY — no I/O, no logging, no exceptions, no DB\n  shell/  All side effects — persistence, HTTP handlers, services, logging\n  ports.clj  Protocol definitions (interfaces between layers)\n\nDependency direction (strictly enforced):\n  Shell -> Core    OK — shell may call core functions\n  Core  -> Ports   OK — core may depend on protocol abstractions\n  Core  -> Shell   NEVER — this violates FC/IS\n\nEnforce and verify:\n  bb check:fcis                   # Detect violations in CI or locally\n  clojure -M:clj-kondo --lint src # General linting including import checks\n\nBenefits:\n  - Core functions are trivially unit-testable (no mocks needed)\n  - Shell can be swapped without touching business logic\n  - Clear separation makes the codebase easier to reason about"
    :see-also [:testing :interceptors]}

   :testing
   {:title    "Testing Strategy"
    :body     "Three test tiers differentiated by metadata tags:\n\n  ^:unit         Pure core functions — no mocks, no DB, fast\n  ^:integration  Shell services with mocked adapters\n  ^:contract     Adapters against real DB (H2 in-memory)\n\nRun commands:\n  clojure -M:test                                   # All tests\n  clojure -M:test :user                             # Single library\n  clojure -M:test --focus-meta :unit                # By metadata tag\n  clojure -M:test --focus wagoe.user.core.validation-test  # Single ns\n  clojure -M:test --watch :user                     # Watch mode\n\nREPL shorthand (after system start):\n  (test-module :user)    ; Run all tests for the user library"
    :see-also [:fcis :scaffold]}

   :config
   {:title    "Configuration"
    :body     "Aero-based configuration with per-environment profiles.\n\n  resources/conf/dev/config.edn   Development defaults\n  resources/conf/test/config.edn  Test overrides (H2 DB, mocked services)\n  resources/conf/prod/config.edn  Production values (secrets via env vars)\n  resources/conf/acc/config.edn   Acceptance/staging environment\n\nProfile selection:\n  WAG_ENV=prod  # Environment variable selects the active profile\n\nREPL exploration:\n  (config)           ; Full resolved config map\n  (config :database) ; Just the database section\n  (config :http)     ; HTTP server settings\n\nKey config sections:\n  :database   JDBC connection, pool settings\n  :http       Host, port, middleware options\n  :jwt        Secret, expiry for auth tokens\n  :email      SMTP or provider settings\n  :storage    Local path or S3 bucket config\n\nSetup wizard:\n  bb setup                                  # Interactive\n  bb setup ai \"PostgreSQL with Stripe\"       # AI-powered\n  bb setup --database postgresql --payment stripe  # Non-interactive"
    :see-also [:scaffold :routes]}

   :routes
   {:title    "HTTP Routes"
    :body     "Routes are defined per module as Reitit route data, and mounted as a contribution:\n\n  {:api [..] :web [..] :static [..]}\n\nA route is [path data & children]:\n  [\"/users\"\n   {:get  {:handler user-http/list-users\n           :summary \"List all users\"}\n    :post {:handler user-http/create-user\n           :summary \"Create a user\"}}]\n\nPaths must NOT include the prefix — :api routes are served under\n/api/v1 and :web routes under /web.\n\nREPL exploration:\n  (routes)              ; All registered routes\n  (routes :user)        ; Routes for the :user module\n  (routes \"/api/users\") ; Find route by path prefix\n\nTesting endpoints from the REPL:\n  (simulate :get  \"/api/v1/users\")\n  (simulate :post \"/api/v1/users\" {:body {:name \"Alice\" :email \"a@example.com\"}})"
    :see-also [:interceptors :config]}})

;; =============================================================================
;; Public API
;; =============================================================================

(defn lookup
  "Return the catalog entry for `topic` keyword, or nil if not found."
  [topic]
  (get catalog topic))

(defn format-doc
  "Format a catalog entry map as a human-readable string.
   Entry must have :title, :body, and :see-also keys."
  [{:keys [title body see-also]}]
  (str "== " title " ==\n\n"
       body
       "\n\nSee also: "
       (str/join ", " (map #(str "(guide " % ")") see-also))))

(defn list-topics
  "Return a sorted sequence of all topic keywords in the catalog."
  []
  (sort (keys catalog)))
