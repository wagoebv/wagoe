(ns wagoe.scaffolder.cli
  "CLI commands for module scaffolding.

   This is the SHELL layer in Functional Core / Imperative Shell architecture.
   Responsibilities:
   - Parse command-line arguments using tools.cli
   - Orchestrate scaffolder service calls
   - Format output (list files, success/error messages)
   - Handle errors and exit codes"
  (:require [wagoe.scaffolder.ports :as ports]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]))

;; =============================================================================
;; Global CLI Options
;; =============================================================================

(def global-options
  [["-f" "--format FORMAT" "Output format: text (default) or json"
    :default "text"
    :validate [#(contains? #{"text" "json"} %) "Must be 'text' or 'json'"]]
   ["-h" "--help" "Show help"]])

;; =============================================================================
;; Generate Command Options
;; =============================================================================

(def generate-options
  [[nil "--module-name NAME" "Module name (lowercase, kebab-case) (required)"
    :validate [#(re-matches #"^[a-z][a-z0-9-]*$" %)
               "Must be lowercase with hyphens only"]]
   [nil "--entity NAME" "Entity name (PascalCase) (required)"
    :validate [#(re-matches #"^[A-Z][a-zA-Z0-9]*$" %)
               "Must be PascalCase"]]
   [nil "--field SPEC" "Field specification: name:type[:required][:unique] (can be repeated)"
    :multi true
    :default []
    :update-fn conj]
   [nil "--base-ns NS" "Base namespace + path for the module (default: wagoe)"]
   [nil "--http" "Enable HTTP (REST API) interface (default: true)"
    :default true]
   [nil "--cli" "Enable CLI interface (default: true)"
    :default true]
   [nil "--web" "Enable Web UI interface (default: true)"
    :default true]
   [nil "--audit" "Enable audit logging (default: true)"
    :default true]
   [nil "--pagination" "Enable pagination support (default: true)"
    :default true]
   [nil "--output-dir DIR" "Output directory (default: current directory)"
    :default "."]
   [nil "--force" "Overwrite existing files"
    :default false]
   [nil "--dry-run" "Show what would be generated without creating files"
    :default false]])

;; =============================================================================
;; Field Command Options (add field to existing entity)
;; =============================================================================

(def field-options
  [[nil "--module-name NAME" "Module name (lowercase, kebab-case) (required)"
    :validate [#(re-matches #"^[a-z][a-z0-9-]*$" %)
               "Must be lowercase with hyphens only"]]
   [nil "--entity NAME" "Entity name (PascalCase) (required)"
    :validate [#(re-matches #"^[A-Z][a-zA-Z0-9]*$" %)
               "Must be PascalCase"]]
   [nil "--name NAME" "Field name (kebab-case) (required)"
    :validate [#(re-matches #"^[a-z][a-z0-9-]*$" %)
               "Must be lowercase with hyphens only"]]
   [nil "--type TYPE" "Field type (required)"
    :validate [#(contains? #{"string" "text" "integer" "int" "decimal" "boolean"
                             "email" "uuid" "enum" "date" "datetime" "inst" "json"} %)
               "Must be a valid field type"]]
   [nil "--required" "Field cannot be null"
    :default false]
   [nil "--unique" "Field must be unique"
    :default false]
   ;; `generate` has always offered this, and `field` writes migrations and
   ;; edits schema.clj just as it does. Without it the service's output-dir
   ;; support was unreachable from the command users actually run, and
   ;; `--output-dir` failed with "Unknown option".
   [nil "--output-dir DIR" "Output directory (default: current directory)"
    :default "."]
   [nil "--dry-run" "Show what would be generated without creating files"
    :default false]])

;; =============================================================================
;; Endpoint Command Options (add endpoint to existing module)
;; =============================================================================

(def endpoint-options
  [[nil "--module-name NAME" "Module name (lowercase, kebab-case) (required)"
    :validate [#(re-matches #"^[a-z][a-z0-9-]*$" %)
               "Must be lowercase with hyphens only"]]
   [nil "--path PATH" "URL path for the endpoint (required)"
    :validate [#(re-matches #"^/.*$" %)
               "Must start with /"]]
   [nil "--method METHOD" "HTTP method (GET, POST, PUT, DELETE, PATCH) (required)"
    :parse-fn str/upper-case
    :validate [#(contains? #{"GET" "POST" "PUT" "DELETE" "PATCH"} %)
               "Must be GET, POST, PUT, DELETE, or PATCH"]]
   [nil "--handler-name NAME" "Handler function name (kebab-case) (required)"
    :validate [#(re-matches #"^[a-z][a-z0-9-]*$" %)
               "Must be lowercase with hyphens only"]]
   [nil "--dry-run" "Show what would be generated without creating files"
    :default false]])

;; =============================================================================
;; Adapter Command Options (generate new adapter implementation)
;; =============================================================================

(def adapter-options
  [[nil "--module-name NAME" "Module name (lowercase, kebab-case) (required)"
    :validate [#(re-matches #"^[a-z][a-z0-9-]*$" %)
               "Must be lowercase with hyphens only"]]
   [nil "--port NAME" "Protocol/port name (PascalCase) (required)"
    :validate [#(re-matches #"^I?[A-Z][a-zA-Z0-9]*$" %)
               "Must be PascalCase (optionally prefixed with I)"]]
   [nil "--adapter-name NAME" "Adapter name (kebab-case) (required)"
    :validate [#(re-matches #"^[a-z][a-z0-9-]*$" %)
               "Must be lowercase with hyphens only"]]
   [nil "--method SPEC" "Method specification: name:arg1,arg2,... (can be repeated)"
    :multi true
    :default []
    :update-fn conj]
   [nil "--dry-run" "Show what would be generated without creating files"
    :default false]])

;; =============================================================================
;; Field Parsing
;; =============================================================================

(defn parse-field-spec
  "Parse a field specification string into a field map.
  
   Format: name:type[:required][:unique]
   
   Examples:
     email:email:required:unique
     name:string:required
     age:integer
     status:enum
     
   Returns:
     Map with keys: :name, :type, :required, :unique, or error map"
  [field-spec]
  (let [parts (str/split field-spec #":")
        [name-str type-str & flags] parts
        valid-types #{"string" "text" "integer" "int" "decimal" "boolean" "email" "uuid" "enum" "date" "datetime" "inst" "json"}
        ;; Map CLI type names to schema type names
        type-mapping {"integer" :int
                      "int" :int
                      "date" :inst
                      "datetime" :inst
                      "text" :text
                      "json" :json}
        type-kw (get type-mapping type-str (keyword type-str))]
    (cond
      (< (count parts) 2)
      {:error (str "Invalid field spec: " field-spec " (expected format: name:type[:required][:unique])")}

      (not (re-matches #"^[a-z][a-z0-9-]*$" name-str))
      {:error (str "Invalid field name: " name-str " (must be lowercase kebab-case)")}

      (not (contains? valid-types type-str))
      {:error (str "Invalid field type: " type-str " (must be one of: " (str/join ", " (sort valid-types)) ")")}

      :else
      {:name (keyword name-str)
       :type type-kw
       :required (boolean (some #(= % "required") flags))
       :unique (boolean (some #(= % "unique") flags))})))

(defn parse-all-fields
  "Parse all field specifications.
  
   Returns:
     [success? fields-or-errors]"
  [field-specs]
  (let [parsed (map parse-field-spec field-specs)
        errors (filter :error parsed)
        fields (remove :error parsed)]
    (if (seq errors)
      [false (mapv :error errors)]
      [true (vec fields)])))

;; =============================================================================
;; Output Formatting
;; =============================================================================

(defn format-file-list
  "Format the touched files as a text list.

   `:note` is printed where present. A bare `skip: …/schema.clj` says something
   did not happen without saying what to do instead, which is barely better
   than the false `update:` it replaced."
  [files]
  (str/join "\n"
            (map (fn [{:keys [path action note]}]
                   (if note
                     (format "  %s: %s — %s" action path note)
                     (format "  %s: %s" action path)))
                 files)))

(def ^:private module-next-steps
  ["Review the generated files"
   "Add module to config: [:active :wagoe/settings :modules]"
   "Wire module into Integrant system configuration"])

(defn- numbered [steps]
  (str/join "\n" (map-indexed #(format "  %d. %s" (inc %1) %2) steps)))

(defn format-success-text
  "Format a successful result as text.

   The heading and the next steps come from the result, not from a constant.
   Every command shared one block of module-generation wording, so `bb scaffold
   field` announced \"Successfully generated module\" and then told the user to
   register a module in Integrant — advice for a different command, printed
   after adding a column (BOU-275)."
  [result]
  (let [field?   (= :field (:command result))
        heading  (if field?
                   (str "✓ Added field to " (:module-name result))
                   (str "✓ Successfully generated module: " (:module-name result)))
        steps    (or (seq (:next-steps result))
                     ;; Fallback only. Every command that knows its own
                     ;; namespace supplies :next-steps; this is what remains for
                     ;; one that does not, and deliberately says nothing it
                     ;; cannot know — the module namespace depends on --base-ns.
                     module-next-steps)
        warnings (seq (:warnings result))]
    (str heading "\n"
         "\n"
         (if field? "Files:\n" "Generated files:\n")
         (format-file-list (:files result))
         "\n"
         ;; Warnings were built by the service and then dropped on the floor
         ;; here — including "Dry run - no files were written", which is the one
         ;; line a dry run exists to print.
         (when warnings
           (str "\nWarnings:\n"
                (str/join "\n" (map #(str "  ! " %) warnings))
                "\n"))
         "\n"
         "Next steps:\n"
         (numbered steps))))

(defn format-error-text
  "Format error result as text."
  [result]
  (str "✗ Failed to generate module\n"
       "\n"
       "Errors:\n"
       (str/join "\n" (map #(str "  - " %) (:errors result)))))

(defn format-dry-run-text
  "Format dry-run result as text."
  [result]
  (str "Dry run - would generate module: " (:module-name result) "\n"
       "\n"
       "Would create files:\n"
       (format-file-list (:files result))
       "\n"
       "\n"
       "Run without --dry-run to create files."))

(defn format-success
  "Format successful result based on output format."
  [format-type result]
  (case format-type
    :json (json/generate-string result {:pretty true})
    :text (if (:dry-run result)
            (format-dry-run-text result)
            (format-success-text result))
    (format-success-text result)))

(defn format-error
  "Format error message based on output format."
  [format-type errors]
  (case format-type
    :json (json/generate-string {:success false :errors errors} {:pretty true})
    :text (if (string? errors)
            (str "Error: " errors)
            (str "Errors:\n" (str/join "\n" (map #(str "  - " %) errors))))
    (str "Error: " errors)))

;; =============================================================================
;; Command Execution
;; =============================================================================

(defn validate-generate-options
  "Validate required options for generate command.
  
   Returns:
     [valid? error-messages]"
  [opts]
  (let [errors (cond-> []
                 (not (:module-name opts))
                 (conj "Missing required option: --module-name")

                 (not (:entity opts))
                 (conj "Missing required option: --entity")

                 (empty? (:field opts))
                 (conj "At least one --field is required"))]
    [(empty? errors) errors]))

(defn validate-field-options
  "Validate required options for field command."
  [opts]
  (let [errors (cond-> []
                 (not (:module-name opts))
                 (conj "Missing required option: --module-name")
                 (not (:entity opts))
                 (conj "Missing required option: --entity")
                 (not (:name opts))
                 (conj "Missing required option: --name")
                 (not (:type opts))
                 (conj "Missing required option: --type"))]
    [(empty? errors) errors]))

(defn validate-endpoint-options
  "Validate required options for endpoint command."
  [opts]
  (let [errors (cond-> []
                 (not (:module-name opts))
                 (conj "Missing required option: --module-name")
                 (not (:path opts))
                 (conj "Missing required option: --path")
                 (not (:method opts))
                 (conj "Missing required option: --method")
                 (not (:handler-name opts))
                 (conj "Missing required option: --handler-name"))]
    [(empty? errors) errors]))

(defn validate-adapter-options
  "Validate required options for adapter command."
  [opts]
  (let [errors (cond-> []
                 (not (:module-name opts))
                 (conj "Missing required option: --module-name")
                 (not (:port opts))
                 (conj "Missing required option: --port")
                 (not (:adapter-name opts))
                 (conj "Missing required option: --adapter-name"))]
    [(empty? errors) errors]))

(defn parse-method-spec
  "Parse a method specification string into a method map.
   Format: name:arg1,arg2,...
   Returns: {:name \"name\" :args [\"arg1\" \"arg2\"]}"
  [method-spec]
  (let [parts (str/split method-spec #":" 2)
        [name-str args-str] parts
        args (if args-str
               (str/split args-str #",")
               [])]
    {:name name-str :args (vec args)}))

(defn execute-generate
  "Execute generate command."
  [service opts]
  (let [[valid? errors] (validate-generate-options opts)]
    (if-not valid?
      {:status 1
       :errors errors}
      (let [[fields-valid? fields-or-errors] (parse-all-fields (:field opts))]
        (if-not fields-valid?
          {:status 1
           :errors fields-or-errors}
          (let [request (cond-> {:module-name (:module-name opts)
                                 :entities [{:name (:entity opts)
                                             :fields fields-or-errors}]
                                 :interfaces {:http (:http opts)
                                              :cli (:cli opts)
                                              :web (:web opts)}
                                 :features {:audit (:audit opts)
                                            :pagination (:pagination opts)}
                                 :output-dir (:output-dir opts)
                                 :force (:force opts)
                                 :dry-run (:dry-run opts)}
                          (:base-ns opts) (assoc :base-ns (:base-ns opts)))
                result (ports/generate-module service request)]
            (if (:success result)
              {:status 0
               :result result}
              {:status 1
               :errors (:errors result)})))))))

(defn execute-field
  "Execute field command - add a field to an existing entity."
  [service opts]
  (let [[valid? errors] (validate-field-options opts)]
    (if-not valid?
      {:status 1
       :errors errors}
      (let [type-mapping {"integer" :int "int" :int "date" :inst
                          "datetime" :inst "text" :text "json" :json}
            field-type (get type-mapping (:type opts) (keyword (:type opts)))
            request {:module-name (:module-name opts)
                     :entity (:entity opts)
                     :field {:name (keyword (:name opts))
                             :type field-type
                             :required (:required opts false)
                             :unique (:unique opts false)}
                     :output-dir (:output-dir opts)
                     :dry-run (:dry-run opts)}
            result (ports/add-field service request)]
        (if (:success result)
          {:status 0
           :result result}
          {:status 1
           :errors (:errors result)})))))

(defn execute-endpoint
  "Execute endpoint command - add an endpoint to an existing module."
  [service opts]
  (let [[valid? errors] (validate-endpoint-options opts)]
    (if-not valid?
      {:status 1
       :errors errors}
      (let [request {:module-name (:module-name opts)
                     :path (:path opts)
                     :method (keyword (str/lower-case (:method opts)))
                     :handler-name (:handler-name opts)
                     :dry-run (:dry-run opts)}
            result (ports/add-endpoint service request)]
        (if (:success result)
          {:status 0
           :result result}
          {:status 1
           :errors (:errors result)})))))

(defn execute-adapter
  "Execute adapter command - generate a new adapter implementation."
  [service opts]
  (let [[valid? errors] (validate-adapter-options opts)]
    (if-not valid?
      {:status 1
       :errors errors}
      (let [methods (if (seq (:method opts))
                      (mapv parse-method-spec (:method opts))
                      [{:name "example-method" :args ["arg1"]}])
            request {:module-name (:module-name opts)
                     :port (:port opts)
                     :adapter-name (:adapter-name opts)
                     :methods methods
                     :dry-run (:dry-run opts)}
            result (ports/add-adapter service request)]
        (if (:success result)
          {:status 0
           :result result}
          {:status 1
           :errors (:errors result)})))))

;; =============================================================================
;; Command Dispatch
;; =============================================================================

(defn dispatch-command
  "Dispatch command to appropriate executor.
  
   Args:
     verb: :generate, :field, :endpoint, :adapter, :help
     opts: Parsed command options
     service: Scaffolder service instance

   Returns:
     Map with :status, :result, or :errors"
  [verb opts service]
  (case verb
    :generate (execute-generate service opts)
    :field (execute-field service opts)
    :endpoint (execute-endpoint service opts)
    :adapter (execute-adapter service opts)
    (throw (ex-info (str "Unknown scaffolder command: " (name verb))
                    {:type :unknown-command
                     :message (str "Unknown command: " (name verb))}))))

;; =============================================================================
;; Help Text
;; =============================================================================

(def root-help
  "Wagoe CLI - Module Scaffolding

Usage: wagoe scaffolder <command> [options]

Commands:
  generate    Generate a new module with full FC/IS structure
  field       Add a field to an existing entity (creates migration)
  endpoint    Add an endpoint to an existing module (shows instructions)
  adapter     Generate a new adapter implementation

The scaffolder works inside an existing project. To create a new one, use the
Wagoe CLI:

  wagoe new my-app

Global Options:
  -f, --format FORMAT  Output format: text (default) or json
  -h, --help           Show help

Examples:
  wagoe scaffolder generate --module-name product --entity Product \\
    --field name:string:required \\
    --field sku:string:required:unique \\
    --field price:decimal:required

  wagoe scaffolder field --module-name product --entity Product \\
    --name description --type text

  wagoe scaffolder endpoint --module-name product \\
    --path /products/export --method GET --handler-name export-products

  wagoe scaffolder adapter --module-name notifications \\
    --port INotificationSender --adapter-name slack

For command-specific help:
  wagoe scaffolder <command> --help")

;; BOU-259: `scaffolder new` had its own project generator, separate from the
;; `wagoe new` templates. It drifted until it produced a project with no
;; com.wagoe deps and no entry point. The verb is kept only to say where to go —
;; an "unknown command" would strand anyone following the old docs.
(def new-removed-help
  "`wagoe scaffolder new` has been removed.

Projects are created with the Wagoe CLI:

  wagoe new my-app

Don't have it?
  curl -fsSL https://raw.githubusercontent.com/wagoebv/wagoe/main/scripts/install.sh | bash

The scaffolder still handles modules, fields, endpoints and adapters inside an
existing project.")

(def generate-help
  "Generate Module Command

Usage: wagoe scaffolder generate [options]

Generates a complete Wagoe module with Functional Core / Imperative Shell
architecture including:
  - Schema definitions (Malli)
  - Port protocols
  - Core business logic
  - Service orchestration
  - Persistence layer
  - HTTP routes (REST API + Web UI)
  - CLI commands
  - Database migrations

Required Options:
  --module-name NAME   Module name (lowercase, e.g., 'product', 'billing')
  --entity NAME        Entity name (PascalCase, e.g., 'Product', 'Customer')
  --field SPEC         Field specification (can be repeated)

Field Specification Format:
  name:type[:required][:unique]

Field Types:
  string     - Text field
  integer    - Integer number
  decimal    - Decimal number
  boolean    - True/false
  email      - Email address (validated)
  uuid       - UUID value
  enum       - Enumeration (specify values in code)
  date       - Date only
  datetime   - Date and time

Field Flags:
  required   - Field cannot be null
  unique     - Field must be unique across all records

Interface Options (default: all enabled):
  --http               Enable HTTP (REST API) interface
  --cli                Enable CLI interface
  --web                Enable Web UI interface

Feature Options (default: all enabled):
  --audit              Enable audit logging
  --pagination         Enable pagination support

Other Options:
  --dry-run            Show what would be generated without creating files

Examples:
  # Generate a product module
  wagoe scaffolder generate \\
    --module-name product \\
    --entity Product \\
    --field name:string:required \\
    --field sku:string:required:unique \\
    --field price:decimal:required \\
    --field active:boolean:required

  # Generate a customer module with email
  wagoe scaffolder generate \\
    --module-name customer \\
    --entity Customer \\
    --field name:string:required \\
    --field email:email:required:unique \\
    --field phone:string

  # Dry run to preview files
  wagoe scaffolder generate \\
    --module-name billing \\
    --entity Invoice \\
    --field amount:decimal:required \\
    --dry-run")

(def field-help
  "Add Field Command

Usage: wagoe scaffolder field [options]

Adds a new field to an existing entity by generating:
  - An ALTER TABLE migration to add the column
  - Instructions for updating the schema.clj file

Required Options:
  --module-name NAME   Module name (lowercase, e.g., 'product')
  --entity NAME        Entity name (PascalCase, e.g., 'Product')
  --name NAME          Field name (kebab-case, e.g., 'description')
  --type TYPE          Field type (string, text, integer, etc.)

Optional Flags:
  --required           Field cannot be null
  --unique             Field must be unique
  --dry-run            Show what would be generated

Examples:
  # Add a description field
  wagoe scaffolder field \\
    --module-name product \\
    --entity Product \\
    --name description \\
    --type text

  # Add a required unique field
  wagoe scaffolder field \\
    --module-name customer \\
    --entity Customer \\
    --name tax-id \\
    --type string \\
    --required \\
    --unique")

(def endpoint-help
  "Add Endpoint Command

Usage: wagoe scaffolder endpoint [options]

Generates instructions for adding a new endpoint to an existing module.
You will need to manually add the code to the http.clj file.

Required Options:
  --module-name NAME     Module name (lowercase, e.g., 'product')
  --path PATH            URL path (e.g., '/products/export')
  --method METHOD        HTTP method (GET, POST, PUT, DELETE, PATCH)
  --handler-name NAME    Handler function name (e.g., 'export-products')

Optional:
  --dry-run              Show what would be generated

Examples:
  # Add a custom export endpoint
  wagoe scaffolder endpoint \\
    --module-name product \\
    --path /products/export \\
    --method GET \\
    --handler-name export-products

  # Add a bulk delete endpoint
  wagoe scaffolder endpoint \\
    --module-name customer \\
    --path /customers/bulk-delete \\
    --method POST \\
    --handler-name bulk-delete-customers")

(def adapter-help
  "Add Adapter Command

Usage: wagoe scaffolder adapter [options]

Generates a new adapter implementation for a port/protocol.
Useful for adding alternative implementations (e.g., different storage backends,
notification providers, etc.)

Required Options:
  --module-name NAME     Module name (lowercase, e.g., 'notifications')
  --port NAME            Protocol/port name (e.g., 'INotificationSender')
  --adapter-name NAME    Adapter name (kebab-case, e.g., 'slack')

Optional:
  --method SPEC          Method specification: name:arg1,arg2,... (can repeat)
  --dry-run              Show what would be generated

Examples:
  # Generate a Slack notification adapter
  wagoe scaffolder adapter \\
    --module-name notifications \\
    --port INotificationSender \\
    --adapter-name slack \\
    --method send-notification:user-id,message \\
    --method send-bulk:user-ids,message

  # Generate an S3 storage adapter
  wagoe scaffolder adapter \\
    --module-name storage \\
    --port IFileStorage \\
    --adapter-name s3 \\
    --method store-file:path,content \\
    --method retrieve-file:path")

;; =============================================================================
;; Main CLI Entry Point
;; =============================================================================

(defn run-cli!
  "Main CLI entry point for scaffolder module.
  
   Args:
     service: Scaffolder service instance
     args: Command-line arguments vector
     
   Returns:
     Exit status: 0 for success, 1 for error
     
   Side effects:
     Prints to stdout/stderr based on command and format"
  [service args]
  (try
    (let [;; Parse to extract verb
          parsed-for-verb (cli/parse-opts args global-options :in-order true)
          global-errors (:errors parsed-for-verb)
          verb-args (:arguments parsed-for-verb)
          [verb-str] verb-args
          verb (when verb-str (keyword verb-str))

          ;; Check for help flags early
          has-help-flag? (or (:help (:options parsed-for-verb))
                             (some #(= % "--help") args))]
      (cond
        ;; No args -> show root help
        (empty? args)
        (do
          (println root-help)
          0)

        ;; Global option errors
        (seq global-errors)
        (do
          (binding [*out* *err*]
            (println (format-error :text global-errors)))
          1)

        ;; Removed command — redirect rather than "unknown command", and fail
        ;; so a script that invokes it does not read silence as success.
        ;;
        ;; Must precede the global-help branch: with `new --help`, has-help-flag?
        ;; is true, so root help was printed with exit 0 while every other form
        ;; of `new` exited 1. Anything probing `--help` to decide whether a
        ;; command exists concluded it still did.
        (= verb :new)
        (do
          (println new-removed-help)
          1)

        ;; Global --help or no command
        (or has-help-flag? (nil? verb))
        (do
          (println root-help)
          0)

        ;; Command-specific help
        (and (= verb :generate) has-help-flag?)
        (do
          (println generate-help)
          0)

        (and (= verb :field) has-help-flag?)
        (do
          (println field-help)
          0)

        (and (= verb :endpoint) has-help-flag?)
        (do
          (println endpoint-help)
          0)

        (and (= verb :adapter) has-help-flag?)
        (do
          (println adapter-help)
          0)

        ;; Execute command
        :else
        (let [;; Get all args after the verb
              remaining-args (vec (drop 1 args))

              ;; Get command-specific options
              cmd-options (case verb
                            :generate generate-options
                            :field field-options
                            :endpoint endpoint-options
                            :adapter adapter-options
                            nil)]
          (if-not cmd-options
            (do
              (binding [*out* *err*]
                (println (format-error :text (str "Unknown command: " (name verb)))))
              1)
            (let [;; Merge global options with command options
                  all-options (into global-options cmd-options)
                  ;; Parse with merged options
                  parsed (cli/parse-opts remaining-args all-options)
                  opts (:options parsed)
                  errors (:errors parsed)
                  format-type (keyword (get opts :format "text"))]
              (if errors
                (do
                  (binding [*out* *err*]
                    (println (format-error format-type errors)))
                  1)
                (let [result (dispatch-command verb opts service)]
                  (if (:errors result)
                    (do
                      (binding [*out* *err*]
                        (println (format-error format-type (:errors result))))
                      (:status result))
                    (do
                      (println (format-success format-type (:result result)))
                      (:status result))))))))))
    (catch Exception e
      (binding [*out* *err*]
        (println "Fatal error:" (.getMessage e)))
      1)))
