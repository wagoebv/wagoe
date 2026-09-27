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

(defn- index-after
  "Index just past the next `needle` at or after `from`, or the end of `s`."
  [^String s ^String needle from]
  (let [j (.indexOf s needle (int from))]
    (if (neg? j) (count s) (+ j (count needle)))))

(defn- quoted-end
  "End of the string literal, quoted identifier or $tag$ body that opens at
   `i`, or nil when none does. Their contents are never SQL structure."
  [^String s i]
  (case (.charAt s i)
    \' (loop [j (inc i)]
         (let [k (.indexOf s "'" (int j))]
           (cond
             (neg? k)                                   (count s)
             (and (< (inc k) (count s))
                  (= \' (.charAt s (inc k))))           (recur (+ k 2))
             :else                                      (inc k))))
    \" (index-after s "\"" (inc i))
    \` (index-after s "`" (inc i))
    \$ (when-let [tag (re-find #"^\$\w*\$" (subs s i (min (count s) (+ i 64))))]
         (index-after s tag (+ i (count tag))))
    nil))

(defn- sql-statements
  "`sql` split at top-level `;`, comments dropped."
  [sql]
  (let [^String s (str sql)
        n         (count s)]
    (loop [i 0 start 0 chunks [] out []]
      (if (>= i n)
        (conj out (apply str (conj chunks (subs s start n))))
        (cond
          (.startsWith s "--" (int i))
          (let [j (index-after s "\n" i)] (recur j j (conj chunks (subs s start i) " ") out))

          (.startsWith s "/*" (int i))
          (let [j (index-after s "*/" (+ i 2))] (recur j j (conj chunks (subs s start i) " ") out))

          (= \; (.charAt s i))
          (recur (inc i) (inc i) [] (conj out (apply str (conj chunks (subs s start i)))))

          :else (recur (or (quoted-end s i) (inc i)) start chunks out))))))

(defn- split-top-level
  "`s` split at commas outside parentheses and quotes."
  [^String s]
  (let [n (count s)]
    (loop [i 0 depth 0 start 0 out []]
      (if (>= i n)
        (conj out (subs s start))
        (let [c (.charAt s i)]
          (cond
            (= c \()                     (recur (inc i) (inc depth) start out)
            (= c \))                     (recur (inc i) (dec depth) start out)
            (and (= c \,) (zero? depth)) (recur (inc i) depth (inc i) (conj out (subs s start i)))
            :else                        (recur (or (quoted-end s i) (inc i)) depth start out)))))))

(def ^:private id "(?:\"[^\"]+\"|`[^`]+`|\\[[^\\]]+\\]|[\\w$]+)")
(def ^:private qname (str "(" id "(?:\\s*\\.\\s*" id ")*)"))
(def ^:private ident-re (re-pattern id))

(defn- ident
  "The last name in a possibly qualified, possibly quoted identifier."
  [s]
  (-> (last (re-seq ident-re (str s)))
      (str/replace #"^[\"`\[]|[\"`\]]$" "")
      str/lower-case))

(def ^:private constraint-item
  #"(?i)^(constraint|primary\s+key|foreign\s+key|unique|check|index|key)\b")

(defn- column-def
  "[name type] for a column definition, or nil for a constraint."
  [item]
  (let [item (str/trim item)]
    (when-let [[_ col type] (and (not (re-find constraint-item item))
                                 (re-matches (re-pattern (str "(?s)(" id ")\\s+(.+)")) item))]
      [(ident col) (str/trim type)])))

(def ^:private create-re
  (re-pattern (str "(?is)create\\s+(?:temporary\\s+)?table\\s+(?:if\\s+not\\s+exists\\s+)?"
                   qname "\\s*\\((.*)\\)[^)]*")))
(def ^:private alter-re
  (re-pattern (str "(?is)alter\\s+table\\s+(?:if\\s+exists\\s+)?(?:only\\s+)?" qname "\\s+(.*)")))
(def ^:private drop-table-re
  (re-pattern (str "(?is)drop\\s+table\\s+(?:if\\s+exists\\s+)?" qname ".*")))

(def ^:private column-actions
  "ALTER TABLE actions as [regex f], where f takes the table's columns and the
   regex groups."
  [[(re-pattern (str "(?is)add\\s+(?:column\\s+)?(?:if\\s+not\\s+exists\\s+)?(" id ")\\s+(.+)"))
    (fn [cols col type]
      (if (re-find constraint-item (str col " " type)) cols (assoc cols (ident col) (str/trim type))))]
   [(re-pattern (str "(?is)drop\\s+column\\s+(?:if\\s+exists\\s+)?(" id ").*"))
    (fn [cols col] (dissoc cols (ident col)))]
   ;; PostgreSQL: ALTER [COLUMN] x [SET DATA] TYPE t [USING …]
   [(re-pattern (str "(?is)alter\\s+(?:column\\s+)?(" id ")\\s+(?:set\\s+data\\s+)?type\\s+(.+?)(?:\\s+using\\s+.*)?"))
    (fn [cols col type] (assoc cols (ident col) (str/trim type)))]
   ;; MySQL: MODIFY [COLUMN] x t
   [(re-pattern (str "(?is)modify\\s+(?:column\\s+)?(" id ")\\s+(.+)"))
    (fn [cols col type] (assoc cols (ident col) (str/trim type)))]
   [(re-pattern (str "(?is)rename\\s+(?:column\\s+)?(" id ")\\s+to\\s+(" id ")"))
    (fn [cols from to]
      (if-let [t (get cols (ident from))]
        (-> cols (dissoc (ident from)) (assoc (ident to) t))
        cols))]])

(defn- apply-action [cols action]
  (let [action (str/trim action)]
    (or (some (fn [[re f]]
                (when-let [m (re-matches re action)]
                  (apply f cols (rest m))))
              column-actions)
        cols)))

(defn- apply-statement [tables stmt]
  (let [stmt (str/trim stmt)]
    (if-let [[_ table body] (re-matches create-re stmt)]
      (assoc tables (ident table) (into {} (keep column-def) (split-top-level body)))
      (if-let [[_ table actions] (re-matches alter-re stmt)]
        (let [t (ident table)]
          (if (contains? tables t)
            (update tables t #(reduce apply-action % (split-top-level actions)))
            tables))
        (if-let [[_ table] (re-matches drop-table-re stmt)]
          (dissoc tables (ident table))
          tables)))))

(defn migration-columns
  "{table-name {column-name sql-type}} after applying `sqls`, the up migrations
   in the order they run. Names are lower-case and unquoted, as in the SQL."
  [sqls]
  (reduce (fn [tables sql] (reduce apply-statement tables (sql-statements sql)))
          {}
          sqls))

(defn column-types
  "A table's columns as {:kebab-field admin-type}, skipping unknown SQL types."
  [columns]
  (into {}
        (keep (fn [[col sql-type]]
                (when-let [t (sql-type->field-type sql-type)]
                  [(keyword (str/replace col #"[_\s]+" "-")) t])))
        columns))

;; =============================================================================
;; Type corrections
;; =============================================================================

(def ^:private carries
  "Model types a column's storage type legitimately holds: an enum lives in a
   VARCHAR, a boolean in SQLite's INTEGER, ids and timestamps in SQLite and
   H2 TEXT. Anything else is a wrong guess."
  {:string #{:text :enum :uuid :json :date :instant}
   :text   #{:string :enum :uuid :json :date :instant}
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
   :parent-context #"(?i):parent-context|parent[ -]context"
   :workflow       #"(?i)\bworkflow\b"})

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
  "One \"[path] problem\" line per schema error in `entities`, or nil when
   they are valid. An unknown key is named where it sits (BOU-572)."
  [entities]
  (when-not (file-validator entities)
    (->> (:errors (m/explain schema/AdminEntityFile entities))
         (map (fn [{:keys [in type] :as error}]
                (str (pr-str (vec in)) " "
                     (if (= type :malli.core/extra-key)
                       "is not a key the admin reads"
                       (me/error-message error)))))
         distinct
         vec)))

(def ^:private key-order
  [:label :table-name :primary-key :sidebar-hidden :parent-context :soft-delete
   :workflow :permissions :list-fields :detail-fields :search-fields :editable-fields
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
      {:error (str "The answer is not a valid admin entity config:\n  "
                   (str/join "\n  " errors))}

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
