(ns wagoe.ai.core.prompts
  "Pure prompt-building functions for AI features.

   FC/IS rule: no I/O, no logging, no exceptions here.
   All functions are pure transformations from data to prompt strings/messages."
  (:require [clojure.string :as str]))

;; =============================================================================
;; System prompt — shared framework context
;; =============================================================================

(def ^:private framework-system-context
  "You are an AI assistant integrated into Wagoe, a Clojure monorepo framework.

Wagoe follows the Functional Core / Imperative Shell (FC/IS) pattern strictly:
- core/ namespaces: pure functions only — no I/O, no logging, no side effects
- shell/ namespaces: all side effects — HTTP handlers, DB, logging, external APIs
- ports.clj: protocol definitions (interfaces)
- schema.clj: Malli validation schemas

Key naming conventions:
- Clojure code: kebab-case (:password-hash, :created-at, my-function)
- Database boundary: snake_case (password_hash, created_at)
- API boundary: camelCase (passwordHash, createdAt)
- Always use wagoe.shared.core.utils.case-conversion for conversions

Key technologies:
- Clojure 1.12.4, Integrant (DI/lifecycle), Aero (config)
- Ring/Reitit (HTTP), next.jdbc + HoneySQL (DB), Malli (validation)
- Buddy (auth/JWT), Hiccup + HTMX (UI), Kaocha (tests)

Testing strategy:
- ^:unit   — pure core functions, no mocks
- ^:integration — shell services with mocked adapters
- ^:contract — adapters against real DB (H2 in-memory)")

;; =============================================================================
;; Feature 1: NL Scaffolding
;; =============================================================================

(defn build-scaffolding-system-prompt
  "Build the system prompt for natural-language module scaffolding.

   Returns a string."
  []
  (str framework-system-context "

Your task: parse a natural language description of a Wagoe module into a structured JSON specification.

Output ONLY valid JSON with this exact structure:
{
  \"module-name\": \"kebab-case-name\",
  \"entity\": \"PascalCaseName\",
  \"fields\": [
    {\"name\": \"field-name\", \"type\": \"string|text|int|decimal|boolean|email|uuid|enum|date|json\", \"required\": true|false, \"unique\": false}
  ],
  \"http\": true,
  \"web\": true
}

Rules:
- module-name MUST be kebab-case (e.g. product, order-item, user-profile)
- entity MUST be PascalCase (e.g. Product, OrderItem, UserProfile)
- field names MUST be kebab-case
- valid field types: string, text, int, decimal, boolean, email, uuid, enum, date, json
- default required=true, unique=false unless stated otherwise
- default http=true, web=true unless stated otherwise
- respond with ONLY the JSON object, no explanation, no markdown fences"))

(defn build-scaffolding-user-prompt
  "Build the user message for NL scaffolding.

   Args:
     description     - natural language description string
     existing-modules - seq of existing module name strings (for context)

   Returns a string."
  [description existing-modules]
  (str "Parse this module description into the JSON specification:\n\n"
       description
       (when (seq existing-modules)
         (str "\n\nExisting modules in this project (for context, avoid name clashes): "
              (str/join ", " existing-modules)))))

(defn scaffolding-messages
  "Return a messages vector for the scaffolding feature.

   Args:
     description      - NL description string
     existing-modules - seq of existing module name strings

   Returns:
     [{:role :system :content str} {:role :user :content str}]"
  [description existing-modules]
  [{:role :system :content (build-scaffolding-system-prompt)}
   {:role :user   :content (build-scaffolding-user-prompt description existing-modules)}])

;; =============================================================================
;; Feature 2: Error Explainer
;; =============================================================================

(defn build-error-explainer-system-prompt
  "Build the system prompt for the error explainer feature.

   Returns a string."
  []
  (str framework-system-context "

Your task: explain a Clojure/Wagoe stack trace and suggest a fix.

Structure your response as:
1. Root cause (1-2 sentences)
2. Affected code (reference the file:line from the trace)
3. Fix suggestion (concrete code change)
4. FC/IS relevance (only if the error relates to a boundary violation)

Known common error patterns in Wagoe:
- Malli validation failures: ExceptionInfo with :type :malli/validation
- HoneySQL key errors: keywords must be qualified (e.g. :users/id not :id in joins)
- Integrant init failures: usually missing ref or bad config key
- Jedis connection errors: Redis not running or wrong host/port
- Case mismatch: kebab-case in Clojure but snake_case expected by DB adapter"))

(defn build-error-explainer-user-prompt
  "Build the user message for error explaining.

   Args:
     stacktrace   - stack trace string
     source-files - map of {file-path content-str} for referenced files

   Returns a string."
  [stacktrace source-files]
  (str "Explain this Clojure/Wagoe error and suggest a fix:\n\n"
       "```\n" stacktrace "\n```"
       (when (seq source-files)
         (str "\n\nReferenced source files:\n"
              (str/join "\n\n"
                        (map (fn [[path content]]
                               (str "--- " path " ---\n" content))
                             source-files))))))

(defn error-explainer-messages
  "Return a messages vector for the error explainer feature.

   Args:
     stacktrace   - stack trace string
     source-files - map of {file-path content-str}

   Returns:
     [{:role :system :content str} {:role :user :content str}]"
  [stacktrace source-files]
  [{:role :system :content (build-error-explainer-system-prompt)}
   {:role :user   :content (build-error-explainer-user-prompt stacktrace source-files)}])

;; =============================================================================
;; Feature 3: Test Generator
;; =============================================================================

(defn build-test-generator-system-prompt
  "Build the system prompt for the test generator feature.

   Returns a string."
  []
  (str framework-system-context "

Your task: generate a complete Kaocha-compatible test namespace for a Wagoe source file.

Rules:
- For core/ files: use ^:unit metadata on the deftest forms
- For shell/ files: use ^:integration metadata
- For adapter tests: use ^:contract metadata
- Use clojure.test (deftest, is, testing)
- Test each public function with: happy path, edge cases, nil/empty inputs
- For pure functions: no mocking needed
- For shell services: mock protocols using reify
- Namespace: replace src/ with test/ and add -test suffix to ns name
- Follow existing test patterns: (deftest function-name-test (testing \"description\" (is (= expected (f args)))))

Output ONLY valid Clojure code — no markdown fences, no explanation."))

(defn build-test-generator-user-prompt
  "Build the user message for test generation.

   Args:
     source-file - path string of the source file
     source-code - content of the source file
     test-type   - :unit, :integration, or :contract

   Returns a string."
  [source-file source-code test-type]
  (str "Generate a complete test namespace for this Wagoe source file.\n"
       "Test type: " (name test-type) "\n"
       "Source file: " source-file "\n\n"
       "```clojure\n" source-code "\n```"))

(defn test-generator-messages
  "Return a messages vector for the test generator feature.

   Args:
     source-file - path string
     source-code - file content string
     test-type   - :unit, :integration, or :contract

   Returns:
     [{:role :system :content str} {:role :user :content str}]"
  [source-file source-code test-type]
  [{:role :system :content (build-test-generator-system-prompt)}
   {:role :user   :content (build-test-generator-user-prompt source-file source-code test-type)}])

;; =============================================================================
;; Feature 4: SQL Copilot
;; =============================================================================

(defn build-sql-copilot-system-prompt
  "Build the system prompt for the SQL copilot feature.

   Returns a string."
  []
  (str framework-system-context "

Your task: convert a natural language database query description into HoneySQL format.

Output JSON with this structure:
{
  \"honeysql\": \"the HoneySQL map as a Clojure-readable EDN string\",
  \"explanation\": \"brief explanation of the query logic\",
  \"raw-sql\": \"equivalent raw SQL for reference\"
}

HoneySQL rules for Wagoe:
- Use keyword qualifications for ambiguous columns: :table/column
- Use {:select [:col1 :col2] :from [:table] :where [...] :order-by [...] :limit n}
- Date functions: [:> :created-at [:raw \"NOW() - INTERVAL '7 days'\"]] for PostgreSQL
- Joins: {:join [:other-table [:= :table/id :other-table/foreign-id]]}
- Always use kebab-case keywords (HoneySQL converts to snake_case automatically)
- is/is-not for NULL checks: [:is :column nil] or [:is-not :column nil]

Output ONLY the JSON object, no markdown fences."))

(defn build-sql-copilot-user-prompt
  "Build the user message for SQL copilot.

   Args:
     description    - NL description of the query
     schema-context - string describing available tables and fields

   Returns a string."
  [description schema-context]
  (str "Convert this description to HoneySQL:\n\n"
       description
       (when (seq schema-context)
         (str "\n\nAvailable schema context:\n" schema-context))))

(defn sql-copilot-messages
  "Return a messages vector for the SQL copilot feature.

   Args:
     description    - NL description string
     schema-context - string describing tables/fields

   Returns:
     [{:role :system :content str} {:role :user :content str}]"
  [description schema-context]
  [{:role :system :content (build-sql-copilot-system-prompt)}
   {:role :user   :content (build-sql-copilot-user-prompt description schema-context)}])

;; =============================================================================
;; Feature 5: Documentation Wizard
;; =============================================================================

(defn build-docs-system-prompt
  "Build the system prompt for the documentation wizard.

   Args:
     doc-type - :agents, :openapi, or :readme

   Returns a string."
  [doc-type]
  (str framework-system-context "\n\n"
       (case doc-type
         :agents
         "Your task: generate an AGENTS.md developer guide for a Wagoe library module.

Structure:
1. ## 1. Purpose — what the module does, what problem it solves
2. ## 2. Key Namespaces — markdown table with Namespace | Layer | Responsibility
3. ## 3. Ports & Protocols — document ports.clj as a REQUIRED layer: list the protocols it defines, and state that shell services depend on these protocols (not concrete records), cross-module calls go through service ports, and web/HTTP layers never require *.shell.persistence directly
4. ## 4. Integrant Configuration — edn config example
5. ## 5. Public API — usage examples with (require ...) and example calls
6. ## 6. Common Pitfalls — numbered list of known gotchas
7. ## 7. Testing Commands — bash code block with test commands

Output ONLY the markdown content."

         :openapi
         "Your task: generate an OpenAPI 3.x YAML specification for a Wagoe HTTP module.

Extract endpoints from the Reitit route definitions and Malli schemas.
Use application/json for all request/response bodies.
Include example values where possible.

Output ONLY valid YAML starting with 'openapi: \"3.0.3\"'."

         :readme
         "Your task: generate a README.md for a Wagoe library module.

Structure: module name as h1, short description, Installation (deps.edn snippet), Quick Start, Configuration, API Reference, License.

Output ONLY the markdown content.")))

(defn build-docs-user-prompt
  "Build the user message for documentation generation.

   Args:
     module-path  - path string to the module (e.g. 'libs/user')
     source-files - map of {relative-path content-str}
     doc-type     - :agents, :openapi, or :readme

   Returns a string."
  [module-path source-files doc-type]
  (str "Generate " (name doc-type) " documentation for the Wagoe module at: " module-path "\n\n"
       "Source files:\n"
       (str/join "\n\n"
                 (map (fn [[path content]]
                        (str "--- " path " ---\n```clojure\n" content "\n```"))
                      source-files))))

(defn docs-messages
  "Return a messages vector for the documentation wizard.

   Args:
     module-path  - module root path string
     source-files - map of {relative-path content-str}
     doc-type     - :agents, :openapi, or :readme

   Returns:
     [{:role :system :content str} {:role :user :content str}]"
  [module-path source-files doc-type]
  [{:role :system :content (build-docs-system-prompt doc-type)}
   {:role :user   :content (build-docs-user-prompt module-path source-files doc-type)}])

;; =============================================================================
;; Feature 6: Admin Entity Generator
;; =============================================================================

(defn build-admin-entity-system-prompt
  "Build the system prompt for the admin entity generator.

   Args:
     existing-entities - seq of EDN strings for existing entity configs

   Returns a string."
  [existing-entities]
  (str framework-system-context "

Your task: generate a Wagoe admin entity configuration in EDN format.

Admin entity configs define how entities appear in the admin UI. They are EDN maps stored
in resources/conf/{dev,test}/admin/<entity>.edn.

Output ONLY valid EDN with this structure:

{:<entity-name>
 {:label           \"Human Label\"
  :table-name      :<entity-name>
  :list-fields     [:field1 :field2 :status :created-at]
  :search-fields   [:field1 :field2]
  :hide-fields     #{:deleted-at}
  :readonly-fields #{:id :created-at :updated-at}
  :fields
  {:sku        {:type :string :label \"SKU\" :width 1}
   :status     {:type :enum :label \"Status\"
                :options [[:active \"Active\"] [:archived \"Archived\"]]
                :filterable true}
   :created-at {:type :instant :label \"Created\" :filterable true}}
  :field-order [:field1 :field2 :status :created-at :updated-at]
  :field-groups
  [{:id :identity :label \"Identity\" :fields [:field1 :field2]}
   {:id :state    :label \"State\"    :fields [:status]}]}}

Rules:
- All keywords MUST be kebab-case
- Always include :id, :created-at, :updated-at in :readonly-fields
- Always include :deleted-at in :hide-fields
- Always include :created-at with {:type :instant :label \"Created\" :filterable true}
- For enum fields, provide :options as vectors of [keyword label] pairs
- Field types: :string, :text, :int, :decimal, :boolean, :enum, :instant, :email, :uuid, :json
- Group related fields logically into :field-groups
- Output ONLY the EDN map, no explanation, no markdown fences

Column width — :width is an optional positive integer weight for list-view columns.
The UI already applies a type/name heuristic (layer 1); omit :width when the heuristic is correct:
  :boolean                                              → 1  (narrow badge)
  :enum                                                 → 2  (short label)
  :int / :decimal / :uuid / :json                       → 2  (numbers / opaque)
  :date / :instant                                      → 3  (date string)
  :text                                                 → 6  (long-form)
  :string with name containing description/bio/body/address/notes/comment/summary/excerpt/content/message
                                                        → 6  (long-form semantics)
  :string with name containing name/email/title/label/slug/url/subject
                                                        → 4  (sentence-length label)
  :string (all other cases)                             → 3  (default)
Only add :width when field semantics differ from these defaults:
  - Short identifiers (sku, code, ref, barcode, pin, zip, iso, ticker) → :width 1 or 2
  - Long free-text :string fields not caught by name heuristic         → :width 6
  - :width must be a positive integer (minimum 1); omit when the default is already correct"
       (when (seq existing-entities)
         (str "\n\nExisting entity configurations for reference:\n"
              (str/join "\n---\n" existing-entities)))))

(defn build-admin-entity-user-prompt
  "Build the user message for admin entity generation.

   Args:
     description - NL description of the entity

   Returns a string."
  [description]
  (str "Generate an admin entity EDN configuration for:\n\n" description))

(defn admin-entity-messages
  "Return a messages vector for the admin entity generator.

   Args:
     description       - NL entity description
     existing-entities - seq of EDN strings for existing entity configs

   Returns:
     [{:role :system :content str} {:role :user :content str}]"
  [description existing-entities]
  [{:role :system :content (build-admin-entity-system-prompt existing-entities)}
   {:role :user   :content (build-admin-entity-user-prompt description)}])

;; =============================================================================
;; Feature 7: Setup Parse (NL to setup spec)
;; =============================================================================

(defn build-setup-parse-system-prompt
  "Build the system prompt for parsing NL config descriptions into setup specs.

   Returns a string."
  []
  (str framework-system-context "

Your task: parse a natural language description of a Wagoe project setup into a JSON configuration spec.

Output ONLY valid JSON with this exact structure:
{
  \"project-name\": \"kebab-case-name\",
  \"database\": \"postgresql|sqlite|h2|mysql\",
  \"ai-provider\": \"ollama|anthropic|openai|none\",
  \"payment\": \"none|mock|stripe|mollie\",
  \"cache\": \"none|redis|in-memory\",
  \"email\": \"none|smtp\",
  \"admin-ui\": true
}

Rules:
- project-name defaults to \"my-app\" if not mentioned
- database defaults to \"postgresql\" if not mentioned
- ai-provider defaults to \"none\" if not mentioned
- payment defaults to \"none\" if not mentioned
- cache defaults to \"none\" if not mentioned
- email defaults to \"none\" if not mentioned
- admin-ui defaults to true if not mentioned
- Output ONLY the JSON object, no explanation, no markdown fences"))

(defn build-setup-parse-user-prompt
  "Build the user message for setup parsing.

   Args:
     description - NL description of the desired setup

   Returns a string."
  [description]
  (str "Parse this project setup description into the JSON spec:\n\n" description))

(defn setup-parse-messages
  "Return a messages vector for the setup parser.

   Args:
     description - NL setup description

   Returns:
     [{:role :system :content str} {:role :user :content str}]"
  [description]
  [{:role :system :content (build-setup-parse-system-prompt)}
   {:role :user   :content (build-setup-parse-user-prompt description)}])

;; =============================================================================
;; Feature 8: Code Review
;; =============================================================================

(defn review-messages
  "Build messages for AI code review of a namespace."
  [ns-name source-code]
  [{:role :system
    :content (str framework-system-context "

Your task: review the given Clojure namespace for:
1. FC/IS violations (core importing shell, side effects in core)
2. Code quality issues (naming, complexity, missing edge cases)
3. Malli schema mismatches or missing validations
4. Potential bugs or race conditions
5. Adherence to Wagoe conventions (kebab-case, case conversion boundaries)

Be specific and actionable. Reference line numbers when possible.
Format: list each issue with severity (critical/warning/info) and suggested fix.")}
   {:role :user
    :content (str "Review this namespace: " ns-name "\n\n```clojure\n" source-code "\n```")}])

;; =============================================================================
;; Feature 9: Test Ideas
;; =============================================================================

(defn test-ideas-messages
  "Build messages for suggesting missing test cases."
  [ns-name source-code existing-tests]
  [{:role :system
    :content (str framework-system-context "

Your task: suggest missing test cases for the given namespace.

Consider:
- Edge cases: nil inputs, empty collections, boundary values
- Error paths: what should fail and how
- Property-based test opportunities
- For core namespaces: pure function tests (^:unit)
- For shell namespaces: integration tests with mocked adapters (^:integration)

Output: a numbered list of test ideas, each with:
- Test name (descriptive, in test-that-something format)
- What it tests and why it matters
- Brief code sketch showing the assertion")}
   {:role :user
    :content (str "Suggest missing tests for: " ns-name "\n\nSource:\n```clojure\n" source-code "\n```"
                  (when existing-tests
                    (str "\n\nExisting tests:\n```clojure\n" existing-tests "\n```")))}])

;; =============================================================================
;; Feature 10: FC/IS Refactoring Guide
;; =============================================================================

(defn refactor-fcis-messages
  "Build messages for FC/IS violation refactoring guidance."
  [ns-name source-code violations]
  [{:role :system
    :content (str framework-system-context "

Your task: guide the developer through refactoring FC/IS violations.

FC/IS rules:
- core/ namespaces MUST be pure: no I/O, no logging, no database, no HTTP
- shell/ namespaces handle all side effects
- core/ CAN depend on ports.clj (protocols)
- shell/ implements ports and calls core

For each violation, provide:
1. Why it violates FC/IS
2. Step-by-step refactoring plan
3. Code examples showing before/after
4. Where to add the port protocol if needed")}
   {:role :user
    :content (str "Refactor FC/IS violations in: " ns-name "\n\n"
                  "Violations detected:\n"
                  (str/join "\n" (map #(str "  " (:from %) " \u2192 " (:to %)) violations))
                  "\n\nSource:\n```clojure\n" source-code "\n```")}])
