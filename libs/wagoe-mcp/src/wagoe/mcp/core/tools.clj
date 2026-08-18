(ns wagoe.mcp.core.tools
  "Catalog of the MCP tools. Pure data: each tool declares its MCP wire fields
   (name, description, JSON-Schema inputSchema) and its capability tier.
   Execution lives in wagoe.mcp.shell.tools; the gate + audit live in the
   dispatch.

   Tier 0 (BOU-100): :read — zero mutation (explain-error, lint, validate-schema,
   describe-module, sql-preview).

   Tier 1 (BOU-101): :generate — write to disk, reversible via git
   (scaffold-module, add-field, gen-tests, gen-migration). Each runs the closed
   verify loop (generate → write → kondo → FC/IS → run affected tests →
   structured report) so the agent self-corrects. The `allow` flag requests an
   audited override of the *soft* guardrails (FC/IS BND-806, convention
   BND-807); kondo errors and test failures are never overridable.

   Tier 2 (BOU-102): :execute — the RCE surface, off by default (run-tests,
   eval, run-migration, query-db). The security gate denies :execute in every
   context except :full (local dev), so these refuse in prod/CI; the dispatch
   audits every call, and each executor additionally audits its payload. The
   real work is injected (test-runner / evaluator / migrator / db-query) so it
   targets the project, not the server, and is stubbable in tests.")

(def catalog
  [{:name        "explain-error"
    :description "Explain a Clojure/Wagoe error or stacktrace: a concise summary plus any matching BND error code (rule, principle, fix)."
    :capability  :read
    :inputSchema {:type       "object"
                  :properties {"error" {:type "string"
                                        :description "The error message or full stacktrace text."}}
                  :required   ["error"]}}
   {:name        "lint"
    :description "Run clj-kondo over the given file paths and return structured findings (file, row, level, message)."
    :capability  :read
    :inputSchema {:type       "object"
                  :properties {"paths" {:type        "array"
                                        :items       {:type "string"}
                                        :description "Files or directories to lint (relative to the project root)."}}
                  :required   ["paths"]}}
   {:name        "validate-schema"
    :description "Validate a value against a Malli schema (given as EDN) and return humanized errors, or {:valid? true}."
    :capability  :read
    :inputSchema {:type       "object"
                  :properties {"schema" {:type "string"
                                         :description "Malli schema as an EDN string, e.g. [:map [:name :string]]."}
                               "value"  {:description "The value to validate (any JSON value)."}}
                  :required   ["schema" "value"]}}
   {:name        "describe-module"
    :description "Describe a module from the running project: its dependencies, ports presence, external libs, and schema when reflected."
    :capability  :read
    :inputSchema {:type       "object"
                  :properties {"module" {:type "string"
                                         :description "Module name, e.g. \"user\"."}}
                  :required   ["module"]}}
   {:name        "sql-preview"
    :description "Generate HoneySQL (and raw SQL) from a natural-language query — generated only, never executed. Requires an AI provider."
    :capability  :read
    :inputSchema {:type       "object"
                  :properties {"query" {:type "string"
                                        :description "Natural-language description of the query."}}
                  :required   ["query"]}}

   ;; --- Tier 1 (:generate) — write to disk, reversible via git -------------
   {:name        "scaffold-module"
    :description "Scaffold a complete FC/IS module (schema, ports, core, shell, tests, migration) from a structured spec, then run the closed verify loop (kondo → FC/IS → tests) and return a structured report. Reversible via git."
    :capability  :generate
    :inputSchema {:type       "object"
                  :properties {"module"   {:type        "string"
                                           :description "Module name, lowercase kebab-case (e.g. \"invoice\")."}
                               "entities" {:type  "array"
                                           :description "Entities to generate."
                                           :items {:type       "object"
                                                   :properties {"name"   {:type "string" :description "Entity name, PascalCase (e.g. \"Invoice\")."}
                                                                "plural" {:type "string" :description "Optional plural form."}
                                                                "fields" {:type  "array"
                                                                          :items {:type       "object"
                                                                                  :properties {"name"     {:type "string" :description "Field name, kebab-case."}
                                                                                               "type"     {:type "string" :description "One of: string, text, int, uuid, boolean, email, enum, inst, json, decimal."}
                                                                                               "required" {:type "boolean"}
                                                                                               "unique"   {:type "boolean"}}
                                                                                  :required   ["name" "type"]}}}
                                                   :required   ["name" "fields"]}}
                               "interfaces" {:type       "object"
                                             :description "Which interfaces to generate (all default false)."
                                             :properties {"http" {:type "boolean"} "cli" {:type "boolean"} "web" {:type "boolean"}}}
                               "preview"  {:type "boolean" :description "Dry-run: return the file plan without writing or verifying."}
                               "force"    {:type "boolean" :description "Overwrite an existing module. Without it, scaffolding a module that already exists is refused and the files it would replace are listed — so re-invoking after a failed verify needs this."}
                               "allow"    {:type "boolean" :description "Audited override of soft (FC/IS, convention) guardrails."}}
                  :required   ["module" "entities"]}}
   {:name        "add-field"
    :description "Add a field to an existing module's entity: generates a migration and schema-update instructions, then verifies. Reversible via git."
    :capability  :generate
    :inputSchema {:type       "object"
                  :properties {"module" {:type "string" :description "Module name (lowercase)."}
                               "entity" {:type "string" :description "Entity name (PascalCase)."}
                               "field"  {:type       "object"
                                         :properties {"name"     {:type "string" :description "Field name, kebab-case."}
                                                      "type"     {:type "string" :description "Field type (string, int, uuid, ...)."}
                                                      "required" {:type "boolean"}
                                                      "unique"   {:type "boolean"}}
                                         :required   ["name" "type"]}
                               "allow"  {:type "boolean" :description "Audited override of soft guardrails."}}
                  :required   ["module" "entity" "field"]}}
   {:name        "gen-tests"
    :description "Generate a test namespace for an existing source file (AI-assisted), write it, then run the verify loop. Requires an AI provider; returns :unavailable otherwise. Reversible via git."
    :capability  :generate
    :inputSchema {:type       "object"
                  :properties {"source-path" {:type "string" :description "Path to the source file (relative to the project root)."}
                               "allow"       {:type "boolean" :description "Audited override of soft guardrails."}}
                  :required   ["source-path"]}}
   {:name        "gen-migration"
    :description "Generate a SQL migration creating the table for a module's entity from its fields, write it, then verify. Reversible via git."
    :capability  :generate
    :inputSchema {:type       "object"
                  :properties {"module"   {:type "string" :description "Module name (lowercase)."}
                               "entity"   {:type "string" :description "Entity name (PascalCase)."}
                               "fields"   {:type  "array"
                                           :description "Entity fields."
                                           :items {:type       "object"
                                                   :properties {"name" {:type "string"} "type" {:type "string"}}
                                                   :required   ["name" "type"]}}
                               "allow"    {:type "boolean" :description "Audited override of soft guardrails."}}
                  :required   ["module" "entity" "fields"]}}

   ;; --- Tier 2 (:execute) — RCE surface, off by default --------------------
   ;; Denied in every context except :full (local dev). Every invocation is
   ;; audited (dispatch :tool-call + executor :execute payload).
   {:name        "run-tests"
    :description "Run the project's test suite for a module via the injected test-runner and return a structured pass/fail report. Tier 2 (:execute) — local dev only; denied in prod/CI."
    :capability  :execute
    :inputSchema {:type       "object"
                  :properties {"module" {:type        "string"
                                         :description "Kaocha suite / module id to run (e.g. \"user\")."}}
                  :required   ["module"]}}
   {:name        "eval"
    :description "Evaluate Clojure code via the injected evaluator and return the value and captured stdout. RCE surface — Tier 2 (:execute), local dev only; denied in prod/CI."
    :capability  :execute
    :inputSchema {:type       "object"
                  :properties {"code" {:type        "string"
                                       :description "Clojure source to evaluate (one or more forms; the last value is returned)."}}
                  :required   ["code"]}}
   {:name        "run-migration"
    :description "Run database migrations for the project via the injected migrator. Tier 2 (:execute), local dev only; denied in prod/CI."
    :capability  :execute
    :inputSchema {:type       "object"
                  :properties {"direction" {:type        "string"
                                            :enum        ["up" "status"]
                                            :description "Migration action: \"up\" applies pending migrations, \"status\" reports state. Defaults to \"up\"."}}
                  :required   []}}
   {:name        "query-db"
    :description "Run a single read-only SQL query (SELECT/WITH/EXPLAIN/SHOW/VALUES/TABLE/PRAGMA only) against the project's database via a read-only role, with an enforced row limit. Writes/DDL are refused. Tier 2 (:execute), local dev only; denied in prod/CI."
    :capability  :execute
    :inputSchema {:type       "object"
                  :properties {"sql"   {:type        "string"
                                        :description "A single read-only SQL statement."}
                               "limit" {:type        "integer"
                                        :description "Max rows to return (default 100, capped at 1000)."}}
                  :required   ["sql"]}}])

(def tool-names
  (into #{} (map :name) catalog))

(defn capability
  "Capability tier for tool `name` (nil if unknown). All Tier 0 tools are :read."
  [name]
  (some #(when (= name (:name %)) (:capability %)) catalog))
