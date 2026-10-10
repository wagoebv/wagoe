(ns wagoe.admin.core.ui.cells
  "List cells rendered by display role (ADR-040).

   `cell` turns one value into the content of its list cell according to the
   field's role; `cell-class` is the class every cell of that role carries, so
   the stylesheet styles roles, never fields. What a cell needs beyond the
   value — a relation's title, a has-many count, the time — arrives in the
   overview the shell computed for the page. Pure Hiccup."
  (:require [wagoe.admin.core.display :as display]
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

(defn- kebab [k]
  (keyword (str/replace (name k) "_" "-")))

(defn value-name
  "An enum value as the string the config names it by: `:paid`, `\"paid\"`
   and `paid` are all \"paid\"."
  [value]
  (if (keyword? value) (name value) (str value)))

;; -----------------------------------------------------------------------------
;; Per role
;; -----------------------------------------------------------------------------

(defn- identifier [value]
  [:span {:class "c-mono" :title (str value)} (str value)])

(defn enum-label
  "The label `:options` gives `value`, else the value humanized."
  [field-config value]
  (let [v (value-name value)]
    (or (some (fn [[k label]] (when (= v (name k)) label)) (:options field-config))
        (str/capitalize (str/replace v #"[-_]" " ")))))

(defn enum-tone
  "The tone `:tones` gives `value`, else :neutral."
  [field-config value]
  (let [tone (get (:tones field-config) (keyword (value-name value)))]
    (if (display/tones tone) tone :neutral)))

(defn- enum-badge [field-config value]
  (let [tone (enum-tone field-config value)]
    (ui/badge (enum-label field-config value)
              {:variant tone :class (str "c-badge tone-" (name tone))})))

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

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn cell
  "The content of `field`'s list cell for `record`.

   ctx:
     :role          the field's display role
     :field-config  its config (may be nil for a has-many column)
     :entity-config the entity's config
     :display       zone, locale and patterns from the shell
     :overview      {:titles :counts :now} from the shell (ADR-040)
     :href          the record's detail URL, when the user may open it
     :relation      the column's belongs-to or has-many, when the caller has it

   Nil renders as nothing in every role but :count, which is 0."
  [field record {:keys [role field-config entity-config display overview href relation]}]
  (let [value (get record field)
        now   (:now overview)]
    (case role
      :count
      (let [rel   (or relation (get (display/has-many-columns entity-config) field))
            pk    (:primary-key entity-config :id)
            id    (get record pk)
            n     (get-in overview [:counts field (id-key id)] 0)]
        (if (and rel id (pos? n))
          [:a {:href (str "/web/admin/" (name (:entity rel)) "?"
                          (name (kebab (:foreign-key rel))) "=" (base/url-encode (str id)))}
           (str n)]
          [:span {:class "c-zero"} (str n)]))

      (when (some? value)
        (case role
          :title
          (if href [:a {:href href} (str value)] (str value))

          :relation
          (let [rel   (or relation (get (display/belongs-to-fields entity-config) field))
                title (get-in overview [:titles field (id-key value)])]
            (if (and rel title)
              [:a {:href (entity-url (:entity rel) value) :title (str value)} title]
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
                          :days     #(some-> (base/instant-distance value now display) (quot 86400))})

          :boolean
          (if (true? (if (string? value) (contains? #{"true" "1" "t"} value) (boolean value)))
            [:span {:class "c-check"}
             (icons/icon :check {:size 14})
             [:span {:class "sr-only"} [:t :common/option-yes]]]
            [:span {:class "sr-only"} [:t :common/option-no]])

          :identifier
          (identifier value)

          :email
          [:a {:href (str "mailto:" value)} (str value)]

          :url
          (if-let [url (safe-url value)]
            [:a {:href url :target "_blank" :rel "noopener noreferrer" :title url} (url-host url)]
            (str value))

          ;; :text and anything unforeseen
          (let [s (if (string? value) value (str value))]
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
