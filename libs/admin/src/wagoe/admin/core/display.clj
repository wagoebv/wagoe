(ns wagoe.admin.core.display
  "Display roles for the admin overview (ADR-040).

   A field's role says what its value is to a reader — a relation, money, a
   due date — and decides how a list cell renders it. Roles are derived from
   the field's type, its name and the entity's relations; an explicit
   `:display` in the field config wins. Pure: config in, data out."
  (:require [clojure.string :as str]))

(def roles
  "Every display role. Closed: the cell renderer and the CSS know each one."
  #{:title :relation :count :enum :money :number :percent :date :datetime
    :boolean :identifier :email :url :text})

(def tones
  "Tones an enum value can carry. The CSS colours each one."
  #{:neutral :info :success :warning :danger})

;; -----------------------------------------------------------------------------
;; Name rules
;; -----------------------------------------------------------------------------

(defn- tokens
  "The words of a field name: `:invoice-total_amount` → [\"invoice\" \"total\" \"amount\"]."
  [field]
  (str/split (str/lower-case (name field)) #"[-_]+"))

(def ^:private money-words
  #{"amount" "price" "total" "subtotal" "cost" "fee" "rate" "bedrag" "prijs" "totaal"})

(def ^:private percent-words #{"pct" "percent" "percentage"})

(def ^:private identifier-words
  #{"number" "code" "reference" "ref" "nummer" "kenmerk" "sku"})

(def ^:private url-words #{"url" "website" "homepage"})

(defn- name-rule
  "The role a field's name implies for its type, with the rule that matched,
   or nil. A rule only applies to the type it names: a string called `amount`
   is text."
  [field type]
  (let [ws   (tokens field)
        word (peek ws)
        rule #(vector % (str "name rule: " word))]
    (case type
      :decimal (cond
                 (some money-words ws) [:money (str "name rule: " (some money-words ws))]
                 (percent-words word)  (rule :percent))
      :int     (when (percent-words word) (rule :percent))
      (:string :text)
      (cond
        (some #(str/includes? % "mail") ws) [:email "name rule: mail"]
        (url-words word)                    (rule :url)
        (identifier-words word)             (rule :identifier))
      nil)))

(defn- type-default
  [type]
  (case type
    :uuid               :identifier
    (:int :decimal)     :number
    :date               :date
    :instant            :datetime
    :boolean            :boolean
    :enum               :enum
    :text))

;; -----------------------------------------------------------------------------
;; Entity-level derivations
;; -----------------------------------------------------------------------------

(def ^:private title-candidates [:name :title :label :email :slug :number])

(defn title-field
  "What a record of this entity is called: the explicit `:title-field`, else
   the first of :name :title :label :email :slug :number the entity has and
   shows, else its first string identifier (a code, a SKU, a reference), else
   its primary key."
  [entity-config]
  (let [fields (:fields entity-config)
        hidden (set (:hide-fields entity-config))
        shown? #(and (contains? fields %) (not (hidden %)))
        order  (or (seq (:field-order entity-config))
                   (seq (:detail-fields entity-config))
                   (sort (keys fields)))]
    (or (let [explicit (:title-field entity-config)]
          (when (and explicit (contains? fields explicit)) explicit))
        (some #(when (shown? %) %) title-candidates)
        (some #(when (and (shown? %)
                          (= :string (get-in fields [% :type]))
                          (= :identifier (first (name-rule % :string))))
                 %)
              order)
        (:primary-key entity-config :id))))

(defn facet-field
  "The enum whose values become the status tabs: the explicit `:facet`, else
   the field called status or state when it is an enum, else nil."
  [entity-config]
  (let [enum? #(= :enum (get-in entity-config [:fields % :type]))]
    (or (let [explicit (:facet entity-config)]
          (when (and explicit (enum? explicit)) explicit))
        (some #(when (enum? %) %) [:status :state]))))

(defn belongs-to-fields
  "Field → the belongs-to relationship it holds."
  [entity-config]
  (into {} (map (juxt :field identity))
        (get-in entity-config [:relationships :belongs-to])))

(defn has-many-columns
  "List column key → the has-many relationship it counts. A has-many is a list
   column when `:list-fields` names its child entity."
  [entity-config]
  (into {} (map (juxt :entity identity)) (:has-many entity-config)))

;; -----------------------------------------------------------------------------
;; Field roles
;; -----------------------------------------------------------------------------

(defn- role-context
  "What every field's role is derived against, computed once per entity."
  [entity-config]
  {:entity-config entity-config
   :title         (title-field entity-config)
   :belongs-to    (belongs-to-fields entity-config)
   :has-many      (has-many-columns entity-config)})

(defn- role-in
  [{:keys [entity-config title belongs-to has-many]} field]
  (let [field-config (get-in entity-config [:fields field])
        type         (:type field-config :string)
        explicit     (:display field-config)
        rel          (get belongs-to field)
        children     (when-not field-config (get has-many field))]
    (cond
      (roles explicit) [explicit "explicit"]
      (= field title)  [:title "title field"]
      rel              [:relation (str "belongs-to " (name (:entity rel)))]
      children         [:count (str "has-many " (name (:entity children)))]
      (= :enum type)   [:enum "type :enum"]
      :else            (or (name-rule field type)
                           [(type-default type) "type default"]))))

(defn role-with-source
  "[role source] for `field`, source saying why: `explicit`, `title field`,
   `belongs-to <entity>`, `has-many <entity>`, a name rule, or `type default`.

   Precedence: explicit `:display`, the title field, belongs-to, has-many,
   enum, name rules, the type default."
  [entity-config field]
  (role-in (role-context entity-config) field))

(defn display-role
  "The display role of `field` in `entity-config`. See `role-with-source`."
  [entity-config field]
  (first (role-with-source entity-config field)))

;; -----------------------------------------------------------------------------
;; Default list columns
;; -----------------------------------------------------------------------------

(def ^:private max-default-columns 7)

(def ^:private audit-fields #{:updated-at :deleted-at :created-by :updated-by})

(defn- list-rank
  "Where a role goes in the default column order, or nil to leave it out."
  [entity-config facet field role]
  (let [type (get-in entity-config [:fields field :type])]
    (cond
      (= role :title)                          0
      (= role :relation)                       1
      (= field facet)                          2
      (= role :enum)                           3
      (= role :money)                          4
      (audit-fields field)                     nil
      (#{:date :datetime} role)                5
      ;; A UUID says nothing to a reader; a code or a SKU does
      (and (= role :identifier) (= :uuid type)) nil
      (#{:text :json :binary} type)            nil
      :else                                    6)))

(defn default-list-fields
  "List columns for an entity with no `:list-fields`: title, relations, the
   facet, other enums, money, dates, then the rest, at most seven. Long text,
   JSON, identifiers that are not the title and audit columns other than
   `created-at` are left out. Within a rank, `field-order` keeps column order."
  [entity-config field-order]
  (let [hidden (set (:hide-fields entity-config))
        ctx    (role-context entity-config)
        facet  (facet-field entity-config)
        fields (->> field-order
                    (filter #(contains? (:fields entity-config) %))
                    (remove hidden)
                    distinct)
        ranked (keep-indexed (fn [i field]
                               (when-let [rank (list-rank entity-config facet field
                                                          (first (role-in ctx field)))]
                                 [rank i field]))
                             fields)]
    (->> (sort ranked)
         (map peek)
         (take max-default-columns)
         vec)))

;; -----------------------------------------------------------------------------
;; Config enrichment and explanation
;; -----------------------------------------------------------------------------

(defn with-display
  "`entity-config` with its derived `:title-field`, `:facet` and
   `:display-roles` (list column → role). With `derive-list-fields?`, an
   entity whose config names no `:list-fields` gets `default-list-fields`."
  [entity-config {:keys [derive-list-fields? field-order]}]
  (let [facet (facet-field entity-config)
        cfg   (cond-> (assoc entity-config :title-field (title-field entity-config))
                facet (assoc :facet facet)
                derive-list-fields?
                (as-> c (assoc c :list-fields
                               (default-list-fields c (or (seq field-order)
                                                          (:field-order c)
                                                          (:detail-fields c)
                                                          (keys (:fields c)))))))
        ctx   (role-context cfg)
        cols  (distinct (concat (:list-fields cfg) (keys (:fields cfg))))]
    (assoc cfg :display-roles
           (into {} (map (juxt identity #(first (role-in ctx %)))) cols))))

(defn explain
  "What the overview derives for `entity-config`, for `(explain-entity :e)` in the REPL:
   each field's role and why, the title field, the facet and the list fields."
  [entity-config]
  (let [ctx       (role-context entity-config)
        list-cols (set (:list-fields entity-config))]
    {:title-field (:title ctx)
     :facet       (facet-field entity-config)
     :list-fields (vec (:list-fields entity-config))
     :fields      (vec (for [field (distinct (concat (:list-fields entity-config)
                                                     (sort (keys (:fields entity-config)))))
                             :let [[role source] (role-in ctx field)]]
                         {:field  field
                          :type   (get-in entity-config [:fields field :type])
                          :role   role
                          :source source
                          :list?  (contains? list-cols field)}))}))

(defn format-explain
  "`explain` as text for the REPL: one line per field, list columns first."
  [entity-name explained]
  (let [{:keys [title-field facet list-fields fields]} explained
        width (fn [k] (reduce max (count (name k)) (map #(count (str (some-> (get % k) name))) fields)))
        wf    (width :field)
        wr    (width :role)
        pad   (fn [s n] (str s (apply str (repeat (- n (count s)) \space))))]
    (str/join
     "\n"
     (concat
      [(str "Admin overview of " (name entity-name))
       (str "  title field  " (some-> title-field name))
       (str "  facet        " (or (some-> facet name) "—"))
       (str "  list fields  " (str/join " " (map name list-fields)))
       ""]
      (for [{:keys [field role source list?]} fields]
        (str (if list? "  * " "    ")
             (pad (name field) wf) "  "
             (pad (name role) wr) "  "
             source))
      ["" "  * = list column. Override a role with :display in the field's config."]))))
