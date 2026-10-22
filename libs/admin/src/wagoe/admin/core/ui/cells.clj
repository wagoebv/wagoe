(ns wagoe.admin.core.ui.cells
  "List cells rendered by display role (ADR-040).

   `cell` turns one value into the content of its list cell according to the
   field's role; `cell-class` is the class every cell of that role carries, so
   the stylesheet styles roles, never fields. What a cell needs beyond the
   value — a relation's title, a has-many count, the time — arrives in the
   overview the shell computed for the page. Pure Hiccup."
  (:require [wagoe.core.utils.text :as text]
            [wagoe.admin.core.display :as display]
            [wagoe.admin.core.ui.base :as base]
            [wagoe.shared.ui.core.components :as ui]
            [wagoe.shared.ui.core.icons :as icons]
            [clojure.string :as str]))

(defn cell-class
  "The class of a cell with `role`: `c-money`, `c-relation`, …"
  [role]
  (str "c-" (name role)))

(defn- entity-url [entity id]
  (str "/web/admin/" (name entity) "/" id))

(defn- id-key
  "How the overview keys a record id: as a string, whatever the driver read."
  [id]
  (some-> id str))

(def value-name base/value-name)

;; -----------------------------------------------------------------------------
;; Per role
;; -----------------------------------------------------------------------------

(defn- identifier [value]
  [:span {:class "c-mono" :title (str value)} (str value)])

(defn enum-label
  "The label `:options` gives `value`, else the value humanized."
  [field-config value]
  (or (base/option-label field-config value)
      (base/format-field-label (value-name value))))

(defn enum-tone
  "The tone `:tones` gives `value`, else :neutral."
  [field-config value]
  (let [tone (get (:tones field-config) (keyword (value-name value)))]
    (if (display/tones tone) tone :neutral)))

(defn- enum-badge [field-config value]
  (ui/tone-badge (enum-label field-config value) (enum-tone field-config value)))

(defn- matches-value? [record [field values]]
  (let [v (get record field)]
    (and (some? v) (some #(= (value-name %) (value-name v)) values))))

(defn urgency-tone
  "For a date with `:urgency`: :danger when it has passed, :warning within
   `:warn-days` (default 7), else nil. `:urgency` is true or a map
   `{:warn-days 7 :until {:status [:paid]}}`; `:until` ends the urgency once
   the record holds one of those values — a paid invoice is not overdue."
  [field-config days record]
  (let [urgency (:urgency field-config)
        {:keys [warn-days until] :or {warn-days 7}} (when (map? urgency) urgency)]
    (when (and urgency days (not (some #(matches-value? record %) until)))
      (cond
        (neg? days)          :danger
        (<= days warn-days)  :warning))))

(defn- dated
  "A relative date or time, the absolute one in `title`, toned by urgency."
  [label absolute tone]
  [:time (cond-> {:title absolute}
           tone (assoc :class (str "tone-" (name tone))))
   label])

(defn- relative-cell
  "A date or timestamp relative to `now`, or absolute without it. `days` is
   the distance in days, for urgency."
  [value now record field-config {:keys [relative absolute days]}]
  (if-let [label (when now (relative))]
    (dated label (absolute) (urgency-tone field-config (days) record))
    (if now (str value) (absolute))))

(defn- safe-url
  "`s` when it is an http(s) URL, else nil: a `javascript:` value must never
   become a link."
  [s]
  (when (and (string? s) (re-find #"(?i)^https?://" (str/trim s)))
    (str/trim s)))

(defn currency
  "The ISO currency of a money field: its `:currency`, else the admin's
   `[:ui :currency]`, else EUR."
  [field-config entity-config]
  (or (:currency field-config) (get-in entity-config [:ui :currency]) "EUR"))

(defn- url-host [url]
  (or (second (re-find #"(?i)^https?://([^/?#]+)" url)) url))

;; A column — of the list, or a field in the record drawer — described once,
;; so a row does not re-derive its role, label and relation for every cell.

(defn- humanize-column
  "The humanized key — without its `-id` for a relation, which shows a name."
  [field relation?]
  (base/format-field-label (cond-> (name field) relation? (str/replace #"[-_]id$" ""))))

(defn columns
  "`fields` as list columns: {:field :config :role :class :label :relation :data?}.
   `:data?` is a column of the table, not a computed one: only those sort,
   filter and edit. The role comes with the config, or is derived now for a
   config built without it."
  [entity-config fields]
  (let [belongs-to (display/belongs-to-fields entity-config)
        computed   (merge (display/has-many-columns entity-config) (display/link-columns entity-config))]
    (mapv (fn [field]
            (let [config (get-in entity-config [:fields field])
                  role   (or (get-in entity-config [:display-roles field])
                             (display/display-role entity-config field))]
              {:field    field
               :config   config
               :role     role
               :class    (cell-class role)
               :label    (or (:label config)
                             (:label (get computed field))
                             (humanize-column field (contains? belongs-to field)))
               :relation (or (get belongs-to field) (get computed field))
               :data?    (contains? (:fields entity-config) field)}))
          fields)))

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn link-chips
  "Linked records as chips, each a link to its record, then \"+N\" for the
   ones `total` counts beyond them."
  [entity items total]
  (list
   [:span {:class "link-chips"}
    (for [{:keys [id title]} items]
      [:span {:class "link-chip"} [:a {:href (entity-url entity id) :title title} title]])]
   (when (< (count items) (or total 0))
     [:span {:class "link-chip-more"} (str "+" (- total (count items)))])))

(defn cell
  "The content of `field`'s list cell for `record`.

   ctx:
     :role          the field's display role
     :field-config  its config (may be nil for a has-many column)
     :entity-config the entity's config
     :display       zone, locale, patterns and the `:overview` ({:titles :counts
                    :links :now}) from the shell (ADR-040)
     :href          the record's detail URL, when the user may open it
     :relation      the column's belongs-to or has-many

   Nil renders as nothing in every role but :count, which is 0."
  [field record {:keys [role field-config entity-config display href relation]}]
  (let [value    (get record field)
        overview (:overview display)
        now      (:now overview)]
    (case role
      :count
      (let [id (get record (:primary-key entity-config :id))
            n  (get-in overview [:counts field (id-key id)] 0)]
        (if (and relation id (pos? n))
          [:a {:href (base/filtered-list-url (:entity relation)
                                             (str/replace (name (:foreign-key relation)) "_" "-")
                                             id)}
           (str n)]
          [:span {:class "c-zero"} (str n)]))

      :links
      (let [{:keys [items total]} (get-in overview [:links field (id-key (get record (:primary-key entity-config :id)))])]
        (when (seq items) (link-chips (:entity relation) items total)))

      (when (some? value)
        (case role
          :title
          (if href [:a {:href href} (str value)] (str value))

          :relation
          (let [title (get-in overview [:titles field (id-key value)])]
            (if (and relation title)
              [:a {:href (entity-url (:entity relation) value) :title (str value)} title]
              (identifier value)))

          :enum
          (enum-badge field-config value)

          :money
          (base/format-money value (currency field-config entity-config) display)

          :number
          (base/format-number value display)

          :percent
          (str (base/format-number value display) " %")

          :date
          (relative-cell value now record field-config
                         {:relative #(base/relative-date value now display)
                          :absolute #(base/format-date value display)
                          :days     #(base/date-distance value now display)})

          :datetime
          (relative-cell value now record field-config
                         {:relative #(base/relative-instant value now display)
                          :absolute #(base/format-instant value display)
                          :days     #(some-> (base/instant-distance value now display) (Math/floorDiv 86400))})

          :boolean
          (if (cond
                (string? value) (contains? #{"true" "1" "t"} (text/lower-case value))
                ;; SQLite stores a boolean as 0 or 1
                (number? value) (not (zero? value))
                :else           (boolean value))
            [:span {:class "c-check"}
             (icons/icon :check {:size 14})
             [:span {:class "sr-only"} [:t :common/option-yes]]]
            [:span {:class "sr-only"} [:t :common/option-no]])

          :identifier
          (identifier value)

          ;; The service reads a file as its size in bytes
          :file
          [:t :admin/file-size {:n value}]

          :email
          [:a {:href (str "mailto:" value)} (str value)]

          :url
          (if-let [url (safe-url value)]
            [:a {:href url :target "_blank" :rel "noopener noreferrer" :title url} (url-host url)]
            (str value))

          ;; :text and anything unforeseen
          (let [s (str value)]
            [:span {:title s} s]))))))

(defn page-total
  "The sum of `field` over `records`, or nil when no row has a number there."
  [records field]
  (let [ds (keep #(base/->decimal (get % field)) records)]
    (when (seq ds) (reduce #(.add ^java.math.BigDecimal %1 %2) ds))))

(defn format-aggregate
  "A computed aggregate shown as `role` shows its values."
  [value role field-config entity-config display]
  (case role
    :money (base/format-money value (currency field-config entity-config) display)
    (base/format-number value display)))
