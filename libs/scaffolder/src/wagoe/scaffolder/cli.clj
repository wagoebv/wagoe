(ns wagoe.scaffolder.cli
  "CLI commands for module scaffolding.

   This is the SHELL layer in Functional Core / Imperative Shell architecture.
   Responsibilities:
   - Parse command-line arguments using tools.cli
   - Orchestrate scaffolder service calls
   - Format output (list files, success/error messages)
   - Handle errors and exit codes"
  (:require [wagoe.scaffolder.ports :as ports]
            [wagoe.scaffolder.core.template :as template]
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
   [nil "--field SPEC" "Field specification: name:type[:values=a,b,c][:required][:unique][:default=v] (can be repeated)"
    :multi true
    :default []
    :update-fn conj]
   [nil "--base-ns NS" "Base namespace + path for the module (default: the project's own)"]
   ;; `--[no-]x`, not `--x`: a bare boolean flag with `:default true` has no
   ;; way to say no — `--web false` set it to true and left "false" as a stray
   ;; argument. `--cli` is gone entirely; the scaffolder has never had a CLI
   ;; generator, so it promised an interface no value of the flag produced
   ;; (BOU-479).
   [nil "--[no-]http" "Generate the HTTP (REST API) interface (default: true)"
    :default true]
   [nil "--[no-]web" "Generate the Web UI interface (default: true)"
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
                             "email" "uuid" "enum" "date" "datetime" "inst" "json"
                             "relation"} %)
               "Must be a valid field type"]]
   [nil "--enum-values LIST" "Comma-separated values, required when --type enum"]
   ;; A relation reachable only while a module is being created is a relation
   ;; you cannot add once you have a module — and `field` is the command for
   ;; an entity that already exists (BOU-480 review).
   [nil "--references ENTITY" "Entity a relation points at, required when --type relation"]
   [nil "--references-table TABLE" "The target's table, when it is not the default plural"]
   [nil "--on-delete ACTION" "cascade (default), restrict, set-null or no-action"]
   [nil "--required" "Field cannot be null"
    :default false]
   [nil "--unique" "Field must be unique"
    :default false]
   [nil "--default VALUE" "Column DEFAULT; a required enum defaults to its first value"]
   ;; `generate` has always offered this, and `field` writes migrations and
   ;; edits schema.clj just as it does. Without it the service's output-dir
   ;; support was unreachable from the command users actually run, and
   ;; `--output-dir` failed with "Unknown option".
   [nil "--output-dir DIR" "Output directory (default: current directory)"
    :default "."]
   [nil "--dry-run" "Show what would be generated without creating files"
    :default false]
   [nil "--base-ns NS" "Base namespace + path for the module (default: the project's own)"]])

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
   ;; Same reason `field` has it: the service resolves the module's http.clj
   ;; through output-dir since BOU-364, so a user pointing at another project
   ;; needs a way to say which one.
   [nil "--output-dir DIR" "Output directory (default: current directory)"
    :default "."]
   [nil "--dry-run" "Show what would be generated without creating files"
    :default false]
   [nil "--base-ns NS" "Base namespace + path for the module (default: the project's own)"]])

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
    :default false]
   [nil "--base-ns NS" "Base namespace + path for the module (default: the project's own)"]])

;; =============================================================================
;; Field Parsing
;; =============================================================================

(defn parse-enum-values
  "Parse a comma-separated enum value list into keywords.

   Returns a vector of keywords, or nil when `s` is blank."
  [s]
  (when-not (str/blank? s)
    (->> (str/split s #",")
         (map str/trim)
         (remove str/blank?)
         (mapv keyword))))

(defn- modifier? [part]
  (or (#{"required" "unique" "optional" "indexed"} part)
      (some #(str/starts-with? part %)
            ["values=" "references=" "references-table=" "on-delete=" "default="])))

(defn- rejoin-default
  "A default may hold colons — a URL, a timestamp — which the spec split cut
   apart. Glue back every piece after `default=` that is not a modifier."
  [flags]
  (reduce (fn [acc part]
            (if (and (some-> (peek acc) (str/starts-with? "default="))
                     (not (modifier? part)))
              (conj (pop acc) (str (peek acc) ":" part))
              (conj acc part)))
          []
          flags))

(def ^:private quoted-default
  "`default='...'`: everything between the quotes is the value, so a colon or
   a word like `unique` in it is not taken for a modifier."
  #"(^|:)default='(.*?)'(?=:|$)")

(defn parse-field-spec
  "Parse a field specification string into a field map.

   Format: name:type[:values=a,b,c][:required][:unique][:default=v]
   A default holding a modifier word after a colon is quoted: default='a:unique'.

   Examples:
     email:email:required:unique
     name:string:required
     age:integer
     status:enum:values=draft,sent,paid:required
     status:enum:values=entered,paid:required:default=entered

   An `enum` needs its values: without them the generated schema was
   `[:enum]`, which matches nothing, so every write of that field failed
   validation (BOU-447).

   Returns:
     Map with keys: :name, :type, :required, :unique, :enum-values,
     or error map"
  [field-spec]
  (let [[_ _ quoted] (re-find quoted-default field-spec)
        field-spec (if quoted (str/replace-first field-spec quoted-default "") field-spec)
        parts (str/split field-spec #":")
        [name-str type-str & raw-flags] parts
        flags (rejoin-default raw-flags)
        valid-types #{"string" "text" "integer" "int" "decimal" "boolean" "email" "uuid" "enum" "date" "datetime" "inst" "json" "relation"}
        ;; Map CLI type names to schema type names
        type-mapping {"integer" :int
                      "int" :int
                      "date" :inst
                      "datetime" :inst
                      "text" :text
                      "json" :json}
        type-kw (get type-mapping type-str (keyword type-str))
        flag-value (fn [prefix]
                     (some #(when (str/starts-with? % prefix)
                              (subs % (count prefix)))
                           flags))
        values-flag (flag-value "values=")
        enum-values (parse-enum-values values-flag)
        ;; Longest prefix first: "references=" is a prefix of nothing, but
        ;; `flag-value "references="` would happily match "references-table=x"
        ;; if the order were reversed.
        references-table (flag-value "references-table=")
        references (some #(when (and (str/starts-with? % "references=")
                                     (not (str/starts-with? % "references-table=")))
                            (subs % (count "references=")))
                         flags)
        on-delete-flag (flag-value "on-delete=")
        on-delete (if on-delete-flag (keyword on-delete-flag) :cascade)
        default (or quoted (flag-value "default="))]
    (cond
      (< (count parts) 2)
      {:error (str "Invalid field spec: " field-spec " (expected format: name:type[:required][:unique])")}

      (not (re-matches #"^[a-z][a-z0-9-]*$" name-str))
      {:error (str "Invalid field name: " name-str " (must be lowercase kebab-case)")}

      (not (contains? valid-types type-str))
      {:error (str "Invalid field type: " type-str " (must be one of: " (str/join ", " (sort valid-types)) ")")}

      (and (= :enum type-kw) (empty? enum-values))
      {:error (str "Enum field " name-str " needs its values: "
                   name-str ":enum:values=first,second,third")}

      (and (seq enum-values) (not= :enum type-kw))
      {:error (str "values= is only meaningful on an enum field, and " name-str
                   " is a " type-str)}

      ;; A relation needs its target for the same reason an enum needs its
      ;; values: without one there is nothing to generate but a bare UUID
      ;; column, which is the hand-written foreign key this type exists to
      ;; remove (BOU-480).
      (and (= :relation type-kw) (str/blank? references))
      {:error (str "Relation field " name-str " needs the entity it references: "
                   name-str ":relation:references=invoice")}

      (and references (not= :relation type-kw))
      {:error (str "references= is only meaningful on a relation field, and "
                   name-str " is a " type-str)}

      (and references-table (not= :relation type-kw))
      {:error (str "references-table= is only meaningful on a relation field, and "
                   name-str " is a " type-str)}

      ;; The target goes into DDL, so it is allowlisted rather than merely
      ;; non-blank (BOU-480 review).
      (and (= :relation type-kw)
           (not (template/valid-entity-name? references)))
      {:error (str "Invalid references= on " name-str ": " (pr-str references)
                   ". It names an entity — letters, digits and single hyphens, "
                   "e.g. references=invoice or references=InvoiceLineItem.")}

      (and (= :relation type-kw)
           (some? references-table)
           (not (template/valid-table-name? references-table)))
      {:error (str "Invalid references-table= on " name-str ": " (pr-str references-table)
                   ". It is a table name — lowercase letters, digits and "
                   "underscores, e.g. references-table=people.")}

      (and on-delete-flag (not= :relation type-kw))
      {:error (str "on-delete= is only meaningful on a relation field, and "
                   name-str " is a " type-str)}

      (and (= :relation type-kw)
           (not (contains? template/on-delete-clauses on-delete)))
      {:error (str "Unknown on-delete " on-delete-flag " on " name-str
                   " (must be one of: "
                   (str/join ", " (sort (map name (keys template/on-delete-clauses))))
                   ")")}

      ;; A NOT NULL column whose foreign key sets it to null on a parent
      ;; delete: the database takes the DDL and then refuses every such
      ;; delete. Both halves were asked for, so neither is dropped silently
      ;; (BOU-480 review).
      (and (= :relation type-kw)
           (= :set-null on-delete)
           (some #(= % "required") flags))
      {:error (str "Field " name-str " is required and on-delete=set-null, which contradict: "
                   "the column cannot be NOT NULL and be set to null when "
                   references " is deleted. Drop `required`, or use "
                   "on-delete=restrict to refuse the delete instead.")}

      (and (not quoted) (some-> default (str/starts-with? "'")))
      {:error (str "Unclosed quote in default= on " name-str
                   ": write default='value' with the closing quote before the next colon")}

      (and (= :inst type-kw) default (template/date-only? default))
      {:error (str "Invalid default= on " name-str ": " (pr-str default)
                   " has no time or offset. A " type-str " column resolves it in the "
                   "database session's time zone; write an offset timestamp, e.g. "
                   default "T00:00:00Z")}

      ;; The value goes into DDL, so it has to suit the column (BOU-494).
      (and default
           (not (template/valid-default? {:type type-kw :enum-values enum-values
                                          :default default})))
      {:error (str "Invalid default= on " name-str ": " (pr-str default)
                   " does not suit a " type-str " field"
                   (when (= :enum type-kw)
                     (str " (must be one of: " (str/join ", " (map name enum-values)) ")")))}

      :else
      (cond-> {:name (keyword name-str)
               :type type-kw
               :required (boolean (some #(= % "required") flags))
               :unique (boolean (some #(= % "unique") flags))}
        enum-values           (assoc :enum-values enum-values)
        default               (assoc :default default)
        (= :relation type-kw) (assoc :references references :on-delete on-delete)
        references-table      (assoc :references-table references-table)))))

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
  ;; Fallback only — the service supplies these, because it knows --base-ns.
  ;; The old "[:active :wagoe/settings :modules]" line named a path nothing
  ;; reads (BOU-309).
  ["Review the generated files"
   "Print the config it needs: bb scaffold integrate <module>"])

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

(defn- field-command-type
  "The field type `--type` names."
  [type-str]
  (get {"integer" :int "int" :int "date" :inst
        "datetime" :inst "text" :text "json" :json}
       type-str
       (keyword type-str)))

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
                 (conj "Missing required option: --type")
                 ;; `--type enum` without values generated `[:enum]`, a schema
                 ;; nothing validates against (BOU-447).
                 (and (= "enum" (:type opts))
                      (empty? (parse-enum-values (:enum-values opts))))
                 (conj "Missing required option: --enum-values (e.g. --enum-values draft,sent,paid)")

                 ;; The relation rules, the same ones `parse-field-spec`
                 ;; applies to `--field x:relation:...` (BOU-480 review).
                 (and (= "relation" (:type opts)) (str/blank? (:references opts)))
                 (conj "Missing required option: --references (e.g. --references invoice)")

                 (and (not= "relation" (:type opts)) (:references opts))
                 (conj (str "--references is only meaningful on --type relation, and "
                            (:name opts) " is a " (:type opts)))

                 (and (not= "relation" (:type opts)) (:references-table opts))
                 (conj (str "--references-table is only meaningful on --type relation, and "
                            (:name opts) " is a " (:type opts)))

                 (and (not= "relation" (:type opts)) (:on-delete opts))
                 (conj (str "--on-delete is only meaningful on --type relation, and "
                            (:name opts) " is a " (:type opts)))

                 (and (= "relation" (:type opts))
                      (not (str/blank? (:references opts)))
                      (not (template/valid-entity-name? (:references opts))))
                 (conj (str "Invalid --references " (pr-str (:references opts))
                            ". It names an entity — letters, digits and single hyphens."))

                 (and (= "relation" (:type opts))
                      (:references-table opts)
                      (not (template/valid-table-name? (:references-table opts))))
                 (conj (str "Invalid --references-table " (pr-str (:references-table opts))
                            ". It is a table name — lowercase letters, digits and underscores."))

                 (and (= "relation" (:type opts))
                      (:on-delete opts)
                      (not (contains? template/on-delete-clauses (keyword (:on-delete opts)))))
                 (conj (str "Unknown --on-delete " (pr-str (:on-delete opts)) " (must be one of: "
                            (str/join ", " (sort (map name (keys template/on-delete-clauses)))) ")"))

                 ;; NOT NULL with a foreign key that nulls the column: the
                 ;; database takes the DDL and refuses every parent delete.
                 (and (= "relation" (:type opts))
                      (= "set-null" (:on-delete opts))
                      (:required opts))
                 (conj (str "--required and --on-delete set-null contradict: the column cannot be "
                            "NOT NULL and be set to null when the parent is deleted. "
                            "Drop --required, or use --on-delete restrict."))

                 (and (:default opts)
                      (not (template/valid-default?
                            {:type        (field-command-type (:type opts))
                             :enum-values (parse-enum-values (:enum-values opts))
                             :default     (:default opts)})))
                 (conj (str "Invalid --default " (pr-str (:default opts))
                            ": it does not suit a " (:type opts) " field")))]
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
              ;; :existing-files carried alongside the prose so a machine
              ;; consumer does not have to string-parse :errors for the paths.
              (cond-> {:status 1 :errors (:errors result)}
                (seq (:existing-files result))
                (assoc :existing-files (:existing-files result))))))))))

(defn execute-field
  "Execute field command - add a field to an existing entity."
  [service opts]
  (let [[valid? errors] (validate-field-options opts)]
    (if-not valid?
      {:status 1
       :errors errors}
      (let [field-type (field-command-type (:type opts))
            request {:module-name (:module-name opts)
                     :entity (:entity opts)
                     :field (cond-> {:name (keyword (:name opts))
                                     :type field-type
                                     :required (:required opts false)
                                     :unique (:unique opts false)}
                              (= :enum field-type)
                              (assoc :enum-values (parse-enum-values (:enum-values opts)))

                              (:default opts)
                              (assoc :default (:default opts))

                              (= :relation field-type)
                              (assoc :references (:references opts)
                                     :on-delete (if (:on-delete opts)
                                                  (keyword (:on-delete opts))
                                                  :cascade))

                              (and (= :relation field-type) (:references-table opts))
                              (assoc :references-table (:references-table opts)))
                     :output-dir (:output-dir opts)
                     :dry-run (:dry-run opts)
                     :base-ns (:base-ns opts)}
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
                     :output-dir (:output-dir opts)
                     :dry-run (:dry-run opts)
                     :base-ns (:base-ns opts)}
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
                     :dry-run (:dry-run opts)
                     :base-ns (:base-ns opts)}
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
  - Database migrations

Required Options:
  --module-name NAME   Module name (lowercase, e.g., 'product', 'billing')
  --entity NAME        Entity name (PascalCase, e.g., 'Product', 'Customer')
  --field SPEC         Field specification (can be repeated)

Field Specification Format:
  name:type[:values=a,b,c][:references=entity][:references-table=t][:on-delete=x][:required][:unique][:default=v]

Field Types:
  string     - Text field
  integer    - Integer number
  decimal    - Decimal number
  boolean    - True/false
  email      - Email address (validated)
  uuid       - UUID value
  enum       - Enumeration (values= is required)
  date       - Date only
  datetime   - Date and time
  relation   - Foreign key to another entity (references= is required)

Field Flags:
  values=a,b,c      Allowed values, required on an enum field
  references=entity The entity a relation points at; the column is <name>_id,
                    and it gets an index
  references-table=t The target's table, when it is not the default plural of
                    the entity — a Person whose table is people, not persons
  on-delete=x       cascade (default), restrict, set-null or no-action.
                    set-null needs a nullable column, so not with `required`
  required          Field cannot be null
  unique            Field must be unique across all records
  default=v         Column DEFAULT, e.g. default=entered. Quoted for text and
                    enums, bare for numbers and booleans. A required enum
                    without one defaults to its first value. A datetime needs
                    an offset: default=2026-01-01T00:00:00Z. Quote a value
                    holding a colon and a modifier word: default='a:unique'

  Example — an invoice line that dies with its invoice:
    --field invoice:relation:references=invoice:required

  Example — a status an admin form may leave out:
    --field status:enum:values=entered,paid:required:default=entered

Interface Options (default: all enabled):
  --no-http            Skip the HTTP (REST API) routes
  --no-web             Skip the Web UI: core/ui.clj, shell/web_handlers.clj
                       and the module's :web route contribution

Feature Options (default: all enabled):
  --audit              Enable audit logging
  --pagination         Enable pagination support

Other Options:
  --force              Overwrite an existing module (refused without it)
  --output-dir DIR     Write somewhere other than the current directory
  --dry-run            Show what would be generated without creating files

Examples:
  # Generate a product module
  wagoe scaffolder generate \\
    --module-name product \\
    --entity Product \\
    --field name:string:required \\
    --field sku:string:required:unique \\
    --field price:decimal:required \\
    --field active:boolean:required \\
    --field status:enum:values=draft,live,archived:required

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
  --enum-values LIST   Comma-separated values, required when --type enum
  --references ENTITY  The entity a relation points at, required when
                       --type relation. The column is <name>_id, and it gets
                       a REFERENCES clause and an index
  --references-table T The target's table, when it is not the default plural
  --on-delete ACTION   cascade (default), restrict, set-null or no-action.
                       set-null needs a nullable column, so not with --required
  --required           Field cannot be null
  --unique             Field must be unique
  --default VALUE      Column DEFAULT; a required enum without one defaults
                       to its first value
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
