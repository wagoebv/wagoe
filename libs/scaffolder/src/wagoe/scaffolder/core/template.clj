(ns wagoe.scaffolder.core.template
  "Pure template rendering functions for module generation.
   
   This namespace provides pure functions for transforming entity definitions
   into code templates. All functions are deterministic and have no side effects."
  (:require [clojure.string :as str]))

;; =============================================================================
;; String Transformation Utilities
;; =============================================================================

(defn kebab->pascal
  "Convert kebab-case to PascalCase.
   
   Args:
     s - String in kebab-case
   
   Returns:
     String in PascalCase
   
   Pure: true
   
   Example:
     (kebab->pascal \"user-profile\") => \"UserProfile\""
  [s]
  (->> (str/split (name s) #"-")
       (map str/capitalize)
       (str/join "")))

(defn pascal->kebab
  "Convert PascalCase to kebab-case.

   The inverse of `kebab->pascal`, and the thing `str/lower-case` cannot do:
   lowercasing `InvoiceLineItem` gives `invoicelineitem`, and every name
   derived from that — the table, the core file, the protocol methods — lost
   the word boundaries the entity name carried (BOU-480).

   A run of capitals is one word, so `HTTPRequest` is `http-request` rather
   than `h-t-t-p-request`. Digits stay attached to the word they follow.

   Pure: true

   Example:
     (pascal->kebab \"InvoiceLineItem\") => \"invoice-line-item\"
     (pascal->kebab \"HTTPRequest\")     => \"http-request\"
     (pascal->kebab \"Product\")         => \"product\""
  [s]
  (-> (name s)
      ;; Split a capital run from the word it heads: HTTPRequest -> HTTP-Request
      (str/replace #"([A-Z]+)([A-Z][a-z])" "$1-$2")
      ;; Split a word from the capital that follows it: LineItem -> Line-Item
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      ;; Whitespace and underscores are word boundaries too: "Line Item"
      (str/replace #"[\s_]+" "-")
      (str/lower-case)))

(defn kebab->snake
  "Convert kebab-case to snake_case.
   
   Args:
     s - String in kebab-case
   
   Returns:
     String in snake_case
   
   Pure: true
   
   Example:
     (kebab->snake \"user-profile\") => \"user_profile\""
  [s]
  (str/replace (name s) #"-" "_"))

(defn ns->path
  "Filesystem path for a namespace: dots become slashes, hyphens underscores.

   The rule Clojure itself uses to find a namespace's file. A module called
   `invoice-line-item` has namespaces `<base>.invoice-line-item.*`, so its
   sources have to live in `invoice_line_item/` — written with the hyphen they
   were unloadable (BOU-447).

   Pure: true

   Example:
     (ns->path \"my-app.invoice-line-item\") => \"my_app/invoice_line_item\""
  [s]
  (-> (name s)
      (str/replace "." "/")
      (str/replace "-" "_")))

(defn pluralize
  "Simple pluralization (just adds 's' for now).
   
   Args:
     s - Singular string
   
   Returns:
     Plural string
   
   Pure: true
   
   Example:
     (pluralize \"customer\") => \"customers\""
  [s]
  (str s "s"))

;; =============================================================================
;; Field Type Mappings
;; =============================================================================

(defn field-type->malli
  "Convert scaffolder field type to Malli schema type.
   
   Args:
     field-def - Field definition map with :type key
   
   Returns:
     Malli schema keyword or vector
   
   Pure: true
   
   Example:
     (field-type->malli {:type :string}) => :string
     (field-type->malli {:type :enum :enum-values [:a :b]}) => [:enum :a :b]"
  [{:keys [type enum-values]}]
  (case type
    :string :string
    :text :string
    :int :int
    :uuid :uuid
    :boolean :boolean
    :email [:re {:error/message "Invalid email format"}
            #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9]+([.-][a-zA-Z0-9]+)*\.[a-zA-Z]{2,}$"]
    :enum (into [:enum] enum-values)
    :inst 'inst?
    :date [:re {:error/message "Must be an ISO date (YYYY-MM-DD)"}
           #"^\d{4}-\d{2}-\d{2}$"]
    :json :map
    ;; BigDecimal, not :double. `--field price:decimal` is what anyone reaches
    ;; for when scaffolding money, and this used to generate binary floating
    ;; point in both the schema and the column (BOU-477).
    :decimal 'decimal?
    ;; A relation is the referenced row's id, and every scaffolded table keys
    ;; on a UUID (BOU-480).
    :relation :uuid))

(defn field-type->sql
  "Convert scaffolder field type to SQL type.

   Generates PostgreSQL-compatible types. The production DDL generator
   in platform/utils/schema.clj handles dialect-specific conversions.

   Args:
     field-def - Field definition map with :type key

   Returns:
     SQL type string

   Pure: true

   Example:
     (field-type->sql {:type :string}) => \"VARCHAR(255)\"
     (field-type->sql {:type :uuid}) => \"UUID\""
  [{:keys [type]}]
  (case type
    :string "VARCHAR(255)"
    :text "TEXT"
    :int "INTEGER"
    :uuid "UUID"
    :boolean "BOOLEAN"
    :email "VARCHAR(255)"
    :enum "VARCHAR(50)"
    ;; The SQL-standard spelling, not TIMESTAMPTZ: one migration runs on
    ;; SQLite, H2 and PostgreSQL, and H2 rejects TIMESTAMPTZ (BOU-522).
    :inst "TIMESTAMP WITH TIME ZONE"
    :date "DATE"
    :json "JSONB"
    :decimal "DECIMAL(19,4)"
    :relation "UUID"))

(def ^:private uuid-pattern
  #"(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

(defn- parses? [parse s]
  (try (parse s) true
       (catch java.time.format.DateTimeParseException _ false)))

(defn date-only?
  "Whether `s` is a bare date, 2026-01-01.

   Pure: true"
  [s]
  (parses? #(java.time.LocalDate/parse %) (str s)))

(defn default-literal
  "The SQL literal for `value` as the DEFAULT of a column of the field's type,
   or nil when the value does not suit that type. It goes into DDL, so numbers
   and booleans must look like one and everything else is quoted. A relation
   takes none: a default foreign key points at a row nobody chose.

   Pure: true

   Example:
     (default-literal {:type :int} \"3\")        => \"3\"
     (default-literal {:type :string} \"it's\")  => \"'it''s'\""
  [{:keys [type enum-values]} value]
  (when (some? value)
    (let [s      (if (keyword? value) (name value) (str value))
          quoted (str "'" (str/replace s "'" "''") "'")]
      (case type
        :int      (when (re-matches #"-?\d+" s) s)
        :decimal  (when (re-matches #"-?\d+(\.\d+)?" s) s)
        :boolean  (when (#{"true" "false"} s) s)
        :relation nil
        ;; No portable JSON literal across H2, SQLite and PostgreSQL.
        :json     nil
        :enum     (when (some #{(keyword s)} enum-values) quoted)
        :uuid     (when (re-matches uuid-pattern s) quoted)
        ;; Not a bare date: the column would resolve it in the database
        ;; session's time zone, so 2026-01-01 became 2025-12-31T23:00Z on a
        ;; PostgreSQL in Amsterdam.
        :inst     (when (parses? #(java.time.OffsetDateTime/parse %) s) quoted)
        :date     (when (parses? #(java.time.LocalDate/parse %) s) quoted)
        quoted))))

(defn valid-default?
  "Whether the field's `:default`, if it has one, can be written as a DEFAULT.

   Pure: true"
  [field-def]
  (or (nil? (:default field-def))
      (some? (default-literal field-def (:default field-def)))))

(defn column-default
  "The DEFAULT literal for a field's column, or nil for none.

   A required enum with no explicit default takes its first value, so a form
   that leaves the column out — an admin `:readonly-fields` entry — can still
   create the row (BOU-494).

   Pure: true"
  [{:keys [type enum-values] :as field-def}]
  (cond
    (some? (:default field-def))
    (default-literal field-def (:default field-def))

    (and (= :enum type) (get field-def :required true) (seq enum-values))
    (default-literal field-def (first enum-values))))

(def on-delete-clauses
  "The `ON DELETE` actions a relation field may ask for.

   An unknown one is refused at parse time rather than pasted into the DDL,
   where it would fail at migration time in whatever words the database
   chooses."
  {:cascade  "CASCADE"
   :restrict "RESTRICT"
   :set-null "SET NULL"
   :no-action "NO ACTION"})

(def entity-name-pattern
  "What a relation may name as its target.

   `references` is interpolated into DDL, so it is an allowlist rather than a
   blocklist: letters, digits and single interior hyphens, starting with a
   letter. `invoice);drop/**/table/**/users;--` passed a blankness check, came
   through `pascal->kebab` intact, and closed the CREATE TABLE at `invoice)`
   with a DROP behind it (BOU-480 review)."
  #"^[A-Za-z][A-Za-z0-9]*(-[A-Za-z0-9]+)*$")

(def table-name-pattern
  "What `references-table` may be: a lowercase SQL identifier."
  #"^[a-z][a-z0-9_]*$")

(defn valid-entity-name?
  "Whether `s` is something a relation may reference.

   Pure: true"
  [s]
  (boolean (and (string? s) (re-matches entity-name-pattern s))))

(defn valid-table-name?
  "Whether `s` is something that may be written as a table identifier.

   Pure: true"
  [s]
  (boolean (and (string? s) (re-matches table-name-pattern s))))

(defn relation-table
  "The table a relation references.

   `references` names an entity — `invoice` and `InvoiceLineItem` both work —
   and the table is the default pluralisation of it, which is what the target
   module's own generator would have produced.

   `references-table` overrides that. An entity is free to declare a
   `:plural`, and the scaffolder generates one module at a time, so it cannot
   see that a `Person` in another module decided its table is `people` rather
   than `persons` (BOU-480 review).

   Returns nil for anything that is not a valid identifier; the request schema
   refuses those before generation, and core does not throw.

   Pure: true

   Example:
     (relation-table \"invoice\" nil)         => \"invoices\"
     (relation-table \"InvoiceLineItem\" nil) => \"invoice_line_items\"
     (relation-table \"person\" \"people\")     => \"people\""
  ([references] (relation-table references nil))
  ([references references-table]
   (cond
     (valid-table-name? references-table) references-table
     (some? references-table)             nil
     (valid-entity-name? references)      (kebab->snake (pluralize (pascal->kebab references)))
     :else                                nil)))

;; =============================================================================
;; Template Context Building
;; =============================================================================

(defn build-field-context
  "Build template context for a single field.
   
   Args:
     field-def - Field definition map
   
   Returns:
     Map with template placeholders for the field
   
   Pure: true"
  [field-def]
  (let [relation? (= :relation (:type field-def))
        ;; `--field invoice:relation:...` is about the relationship; the
        ;; column and the key are `invoice-id`. Naming it here means every
        ;; generator — schema, migration, persistence — sees the same name
        ;; without knowing the rule (BOU-480).
        field-name (cond-> (name (:name field-def))
                     relation? (str "-id"))]
    (cond-> {:field-name field-name
             :field-name-kebab field-name
             :field-name-snake (kebab->snake field-name)
             :field-name-pascal (kebab->pascal field-name)
             :field-type (:type field-def)
             :field-required (get field-def :required true)
             :field-unique (get field-def :unique false)
             :malli-type (field-type->malli field-def)
             :sql-type (field-type->sql field-def)}
      (column-default field-def)
      (assoc :sql-default (column-default field-def))

      relation?
      (assoc :references     (:references field-def)
             :relation-table (relation-table (:references field-def)
                                             (:references-table field-def))
             :on-delete      (get field-def :on-delete :cascade)))))

(defn belongs-to-field
  "The relation field `:belongs-to parent` stands for: required, cascading.

   Pure: true"
  [parent]
  (let [k (pascal->kebab parent)]
    {:name (keyword k) :type :relation :references k :required true :on-delete :cascade}))

(defn belongs-to-clash
  "The declared field that would also be the `:belongs-to` parent's column —
   `invoice` or `invoice-id` next to `:belongs-to \"Invoice\"` — or nil. Both
   become invoice_id: a duplicate column and duplicate schema keys.

   Pure: true"
  [{:keys [fields belongs-to]}]
  (when (valid-entity-name? belongs-to)
    (let [parent (pascal->kebab belongs-to)]
      (some #(when (#{parent (str parent "-id")} (name (:name %))) (:name %)) fields))))

(defn entity-fields
  "An entity's fields, with its `:belongs-to` parent as the first one. A field
   of the same name already declared wins.

   Pure: true"
  [{:keys [fields belongs-to]}]
  (let [field (when (valid-entity-name? belongs-to) (belongs-to-field belongs-to))]
    (if (and field (not-any? #(= (:name field) (keyword (name (:name %)))) fields))
      (into [field] fields)
      (vec fields))))

(defn repository-fns
  "Method names of an entity's repository port.

   The module's first entity keeps the short names. A later entity shares
   ports.clj with it, and defprotocol interns its methods as vars in that
   namespace, so a second `find-by-id` would silently replace the first
   (BOU-497).

   Pure: true"
  [entity-kebab entity-plural primary?]
  (if primary?
    {:find-by-id "find-by-id" :find-all "find-all" :create "create"
     :update "update-entity" :delete "delete"}
    {:find-by-id (str "find-" entity-kebab "-by-id")
     :find-all   (str "find-all-" entity-plural)
     :create     (str "create-" entity-kebab "-entity")
     :update     (str "update-" entity-kebab "-entity")
     :delete     (str "delete-" entity-kebab "-entity")}))

(defn build-entity-context
  "Build template context for an entity.

   Args:
     entity-def - Entity definition map
     module-name - Module name string
     opts - {:primary? bool}; true (the default) for the module's first
            entity, which owns shell/service.clj and shell/persistence.clj.
            Any other entity gets its own `shell/<entity>_service.clj` and
            `shell/<entity>_persistence.clj`.

   Returns:
     Map with template placeholders for the entity

   Pure: true"
  ([entity-def module-name] (build-entity-context entity-def module-name {:primary? true}))
  ([entity-def module-name {:keys [primary?] :or {primary? true}}]
   (let [entity-name (:name entity-def)
         ;; Through pascal->kebab, not str/lower-case. Everything below is
         ;; derived from this one value, so lowercasing here is what turned
         ;; InvoiceLineItem into the table `invoicelineitems` and the method
         ;; `create-invoicelineitem` (BOU-480).
         entity-kebab (pascal->kebab entity-name)
         entity-plural (or (:plural entity-def) (pluralize entity-kebab))
         shell-ns (fn [suffix] (if primary? (str "shell." suffix) (str "shell." entity-kebab "-" suffix)))]
     {:module-name module-name
      :entity-name entity-name
      ;; `:entity-lower` is the kebab form: it names Clojure vars
      ;; (`create-<entity-lower>`), where a run-together word is wrong and a
      ;; hyphen is right. It is not `str/lower-case` of anything any more.
      :entity-lower entity-kebab
      :entity-kebab entity-kebab
      :entity-snake (kebab->snake entity-kebab)
      :entity-plural entity-plural
      :entity-plural-snake (kebab->snake entity-plural)
      :entity-table (kebab->snake entity-plural)
      :fields (mapv build-field-context (entity-fields entity-def))
      :description (:description entity-def "")
      :primary? (boolean primary?)
      :repo-fns (repository-fns entity-kebab entity-plural primary?)
      :service-ns (shell-ns "service")
      :persistence-ns (shell-ns "persistence")
      :service-test-ns (shell-ns "service-test")})))

(defn build-module-context
  "Build complete template context for module generation.
   
   Args:
     request - Module generation request map
   
   Returns:
     Map with all template placeholders
   
   Pure: true"
  [request]
  (let [module-name (:module-name request)
        entities (:entities request)
        base-ns (or (:base-ns request) "wagoe")]
    {:module-name module-name
     :module-pascal (kebab->pascal module-name)
     ;; Namespace segment vs directory name: `:module-name` goes into the `ns`
     ;; form, `:module-path` on disk. They differ for any kebab-case module.
     :module-path (kebab->snake module-name)
     :base-ns base-ns
     :base-ns-path (ns->path base-ns)
     :entities (vec (map-indexed #(build-entity-context %2 module-name {:primary? (zero? %1)})
                                 entities))
     ;; Defaulted here rather than at each reader, so "absent means yes" is
     ;; decided once. Passed straight through before, and read by nobody
     ;; (BOU-479).
     :interfaces (merge {:http true :web true}
                        (:interfaces request {}))
     :features (merge {:audit false :soft-delete false :pagination true}
                      (:features request {}))}))
