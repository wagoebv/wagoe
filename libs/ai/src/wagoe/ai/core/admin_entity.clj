(ns wagoe.ai.core.admin-entity
  "Pure checks and corrections for a generated admin entity config (BOU-567).

   The model is asked for the right types and relations and does not reliably
   give them, so what the project already knows — its migrations — decides the
   types, and what the description asked for is checked before anything is
   written."
  (:require [wagoe.ai.schema :as schema]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

;; =============================================================================
;; Column types from migrations
;; =============================================================================

(defn sql-type->field-type
  "The admin field type for a SQL column type, or nil when there is none."
  [sql-type]
  (let [t (str/upper-case (str/trim (str sql-type)))]
    (condp re-find t
      #"^(TIMESTAMP|DATETIME)"                                :instant
      #"^DATE\b"                                              :date
      #"^UUID\b"                                              :uuid
      #"^BOOL"                                                :boolean
      #"^(INT|INTEGER|BIGINT|SMALLINT|TINYINT|MEDIUMINT|SERIAL|BIGSERIAL)\b" :int
      #"^(DECIMAL|NUMERIC|REAL|DOUBLE|FLOAT|MONEY)"           :decimal
      #"^JSONB?\b"                                            :json
      #"^(TEXT|CLOB|MEDIUMTEXT|LONGTEXT)\b"                   :text
      #"^(VARCHAR|NVARCHAR|CHAR|CHARACTER|CITEXT)"            :string
      #"^(BYTEA|BLOB|BINARY|VARBINARY)"                       :binary
      nil)))

(defn- split-top-level
  "`s` split at commas outside parentheses."
  [s]
  (loop [[c & more :as cs] (seq s) depth 0 cur [] out []]
    (cond
      (empty? cs)                 (conj out (apply str cur))
      (= c \()                    (recur more (inc depth) (conj cur c) out)
      (= c \))                    (recur more (dec depth) (conj cur c) out)
      (and (= c \,) (zero? depth)) (recur more depth [] (conj out (apply str cur)))
      :else                       (recur more depth (conj cur c) out))))

(defn- ident [s]
  (-> (str s) (str/replace #"[\"`\[\]]" "") (str/split #"\.") last str/lower-case))

(def ^:private constraint-item
  #"(?i)^(constraint|primary\s+key|foreign\s+key|unique|check|index|key)\b")

(defn- column-def
  "[name type] for a column definition, or nil for a constraint."
  [item]
  (let [item (str/trim item)]
    (when-let [[_ col type] (and (not (re-find constraint-item item))
                                 (re-matches #"(?s)([\w\"`]+)\s+(.+)" item))]
      [(ident col) (str/trim type)])))

(defn- apply-statement [tables stmt]
  (let [stmt (str/trim stmt)]
    (if-let [[_ table body] (re-matches #"(?is)create\s+(?:temporary\s+)?table\s+(?:if\s+not\s+exists\s+)?([\w.\"`\[\]]+)\s*\((.*)\)[^)]*" stmt)]
      (assoc tables (ident table) (into {} (keep column-def) (split-top-level body)))
      (if-let [[_ table actions] (re-matches #"(?is)alter\s+table\s+(?:if\s+exists\s+)?([\w.\"`\[\]]+)\s+(.*)" stmt)]
        (reduce (fn [ts action]
                  (let [action (str/trim action)]
                    (if-let [[_ col type] (re-matches #"(?is)add\s+(?:column\s+)?(?:if\s+not\s+exists\s+)?([\w\"`]+)\s+(.+)" action)]
                      (if (re-find constraint-item col)
                        ts
                        (assoc-in ts [(ident table) (ident col)] (str/trim type)))
                      (if-let [[_ col] (re-matches #"(?is)drop\s+column\s+(?:if\s+exists\s+)?([\w\"`]+).*" action)]
                        (update ts (ident table) dissoc (ident col))
                        ts))))
                tables
                (split-top-level actions))
        (if-let [[_ table] (re-matches #"(?is)drop\s+table\s+(?:if\s+exists\s+)?([\w.\"`\[\]]+).*" stmt)]
          (dissoc tables (ident table))
          tables)))))

(defn migration-columns
  "{table-name {column-name sql-type}} after applying `sqls`, the up migrations
   in the order they run. Names are lower-case snake_case, as in the SQL."
  [sqls]
  (reduce (fn [tables sql]
            (reduce apply-statement tables
                    (-> (str sql)
                        (str/replace #"--[^\n]*" "")
                        (str/replace #"(?s)/\*.*?\*/" "")
                        (str/split #";"))))
          {}
          sqls))

(defn column-types
  "A table's columns as {:kebab-field admin-type}, skipping unknown SQL types."
  [columns]
  (into {}
        (keep (fn [[col sql-type]]
                (when-let [t (sql-type->field-type sql-type)]
                  [(keyword (str/replace col "_" "-")) t])))
        columns))

;; =============================================================================
;; Type corrections
;; =============================================================================

(def ^:private carries
  "Model types a column's storage type legitimately holds: an enum lives in a
   VARCHAR, a boolean in SQLite's INTEGER. Anything else is a wrong guess."
  {:string #{:text :enum :uuid :json :date :instant}
   :text   #{:string :enum :json}
   :int    #{:boolean}})

(defn- correct-types [config type-for]
  (reduce-kv (fn [acc field fcfg]
               (let [from (:type fcfg)
                     to   (when from (type-for field from))]
                 (if (and to (not= from to))
                   (-> acc
                       (assoc-in [:config :fields field :type] to)
                       (update :corrections conj {:field field :from from :to to}))
                   acc)))
             {:config config :corrections []}
             (or (:fields config) {})))

(defn apply-column-types
  "`config` with each field's :type taken from the table's `col-types`.

   Returns {:config .. :corrections [{:field :from :to}]}."
  [config col-types]
  (correct-types config
                 (fn [field from]
                   (when-let [col (get col-types field)]
                     (if (contains? (get carries col #{}) from) from col)))))

(def ^:private date-name  #"(^|-)(date|birthday|birthdate|dob)(-|$)")
(def ^:private count-name #"(^|-)(quantity|qty|count|cents)(-|$)")

(defn infer-types
  "For a table that does not exist yet: a date-named :instant becomes :date,
   and a quantity, count or amount in cents typed :decimal becomes :int."
  [config]
  (correct-types config
                 (fn [field from]
                   (let [n (name field)]
                     (cond
                       (and (= :instant from) (re-find date-name n))  :date
                       (and (= :decimal from) (re-find count-name n)) :int
                       :else                                          from)))))

;; =============================================================================
;; Requested relations
;; =============================================================================

(def ^:private relation-patterns
  {:has-many       #"(?i):has-many|\bhas[ -]many\b"
   :sidebar-hidden #"(?i):sidebar-hidden|sidebar[ -]hidden|\bhid(?:e|den)\b[^.]{0,30}\b(?:sidebar|nav)"
   :parent-context #"(?i):parent-context|parent[ -]context"})

(defn requested-keys
  "The relation keys `description` asks for."
  [description]
  (into #{}
        (keep (fn [[k re]] (when (re-find re (str description)) k)))
        relation-patterns))

(defn missing-keys
  "The keys in `requested` that no entity in `entities` carries."
  [entities requested]
  (let [present (into #{} (mapcat keys) (vals entities))]
    (into #{} (remove present) requested)))

;; =============================================================================
;; Validation and rendering
;; =============================================================================

(def ^:private file-validator (m/validator schema/AdminEntityFile))

(defn validation-errors
  "Humanized schema errors for `entities`, or nil when they are valid."
  [entities]
  (when-not (file-validator entities)
    (me/humanize (m/explain schema/AdminEntityFile entities))))

(def ^:private key-order
  [:label :table-name :primary-key :sidebar-hidden :parent-context :soft-delete
   :permissions :list-fields :detail-fields :search-fields :editable-fields
   :hide-fields :readonly-fields :fields :field-order :field-groups
   :default-sort :default-sort-dir :has-many])

(defn- spaces [n] (apply str (repeat n \space)))

(defn- block [open close items indent]
  (str open (str/join (str "\n" (spaces indent)) items) close))

(defn- ordered-fields [fields field-order]
  (let [pos (zipmap field-order (range))]
    (sort-by (fn [[k _]] [(get pos k Long/MAX_VALUE) (name k)]) fields)))

(defn- render-entry [k v config col]
  (cond
    (and (= :fields k) (map? v) (seq v))
    (str (pr-str k) "\n" (spaces col)
         (block "{" "}" (map (fn [[fk fv]] (str (pr-str fk) " " (pr-str fv)))
                             (ordered-fields v (:field-order config)))
                (inc col)))

    (and (vector? v) (seq v) (every? map? v))
    (str (pr-str k) "\n" (spaces col) (block "[" "]" (map pr-str v) (inc col)))

    :else (str (pr-str k) " " (pr-str v))))

(defn- render-entity [config col]
  (let [pos     (zipmap key-order (range))
        entries (sort-by (fn [[k _]] [(get pos k Long/MAX_VALUE) (str k)]) config)]
    (block "{" "}" (map (fn [[k v]] (render-entry k v config (inc col))) entries) (inc col))))

(defn render
  "`entities` as EDN text, one key per line, in the order people read them."
  [entities]
  (block "{" "}"
         (map (fn [[ek ev]] (str (pr-str ek) "\n " (render-entity ev 1))) entities)
         1))

;; =============================================================================
;; The whole check
;; =============================================================================

(defn- table-key [config]
  (some-> (:table-name config) name (str/replace "-" "_") str/lower-case))

(defn- typed-entity [[k config] tables]
  (let [table (table-key config)
        cols  (get tables table)
        {:keys [config corrections]} (if cols
                                       (apply-column-types config (column-types cols))
                                       (infer-types config))]
    {:entity-key  k
     :config      config
     :corrections corrections
     :type-source (if cols {:source :migrations :table table} {:source :description})}))

(defn prepare
  "Check and correct a parsed answer: `entities` is the EDN value, `edn-text`
   its text, `tables` what `migration-columns` read, `description` the request.

   Returns {:entities [{:entity-name :text :type-source :corrections}]} with
   one file's text per entity, or {:error str}."
  [entities edn-text tables description]
  (let [errors  (validation-errors entities)
        missing (when-not errors (missing-keys entities (requested-keys description)))]
    (cond
      errors
      {:error (str "The answer is not a valid admin entity config: " (pr-str errors))}

      (seq missing)
      {:error (str "The answer dropped " (str/join " and " (sort missing))
                   ", which the description asks for.")}

      :else
      (let [typed   (map #(typed-entity % tables) entities)
            as-sent (and (= 1 (count typed)) (empty? (:corrections (first typed))))]
        {:entities (mapv (fn [{:keys [entity-key config] :as e}]
                           (-> (dissoc e :entity-key :config)
                               (assoc :entity-name (name entity-key)
                                      :text (if as-sent edn-text (render {entity-key config})))))
                         typed)}))))
