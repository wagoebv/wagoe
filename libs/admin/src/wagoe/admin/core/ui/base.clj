(ns wagoe.admin.core.ui.base
  "Shared admin UI primitives used across multiple ui.* sections.

   Leaf namespace: URL helpers, field-value rendering, list-column width
   heuristics, and small formatting/label utilities. Must NOT require any
   other wagoe.admin.core.ui.* implementation namespace — it is the
   dependency root that the focused sections build on."
  (:require [wagoe.shared.ui.core.components :as ui]
            [wagoe.shared.ui.core.table :as table-ui]
            [clojure.string :as str]))

;; =============================================================================
;; URL Helpers
;; =============================================================================

(defn url-encode
  "URL-encode a string for use as a query-string value.

   Required when threading `return_to` (or any contextual URL containing
   its own `?`/`&` characters) through query parameters — otherwise the
   embedded `&` splits into a second top-level parameter and the receiving
   handler sees a truncated value."
  [^String s]
  (java.net.URLEncoder/encode s "UTF-8"))

(defn entity-create-url
  "Resolve the URL used for the 'New' button on an entity.

   Entities may expose a dedicated create flow via `:create-redirect-url`
   (e.g. split-table entities that cannot be created via the generic admin
   CRUD path). Falls back to `/web/admin/<entity>/new` when no override is
   configured.

   When `caller-url` is provided AND the entity delegates via
   `:create-redirect-url`, the caller URL is threaded through as a
   `return-to` query parameter so the delegated flow (e.g. the user
   module's `/web/users/new` page) can bring the admin back to their
   current filtered/paginated list view on cancel or success. The value
   is URL-encoded to survive embedded `&`/`=` characters from filters
   and pagination.

   `caller-url` is ignored for the non-delegated path because the generic
   admin create handler re-renders the default list view directly and
   does not honor `return-to`."
  ([entity-name entity-config]
   (entity-create-url entity-name entity-config nil))
  ([entity-name entity-config caller-url]
   (let [redirect-url (:create-redirect-url entity-config)
         base (or redirect-url (str "/web/admin/" (name entity-name) "/new"))]
     (if (and redirect-url caller-url)
       (let [separator (if (str/includes? base "?") "&" "?")]
         (str base separator "return-to=" (url-encode caller-url)))
       base))))

(defn current-list-url
  "Build the current admin list URL with filters/pagination applied.

   Used to seed `return-to` on links that navigate away from a list view
   (notably the delegated create flow), so the user lands back on the
   same filtered/paginated page after cancel or success. Returns the
   plain `/web/admin/<entity>` when no meaningful query params exist."
  [entity-name table-query filters]
  (let [base (str "/web/admin/" (name entity-name))
        ;; Drop empty values so the default list view produces the bare
        ;; `/web/admin/<entity>` rather than `?page=&page-size=`.
        ;; (`encode-query-params` already strips nils, so we only need to
        ;; filter empty strings here.)
        params (into {}
                     (remove (fn [[_ v]] (= "" v)))
                     (merge (table-ui/table-query->params table-query)
                            (table-ui/search-filters->params filters)))
        qs (table-ui/encode-query-params params)]
    (if (str/blank? qs)
      base
      (str base "?" qs))))

;; =============================================================================
;; Field Value Rendering
;; =============================================================================

(defn render-field-value
  "Render field value for display in table or detail view.

   Args:
     field-name: Keyword field name
     value: Field value to render
     field-config: Field configuration map

    Returns:
      Hiccup structure or string for display"
  [_field-name value field-config]
  (let [field-type (:type field-config :string)]
    (cond
      (nil? value)
      [:span.null-value {:class "badge ui-badge ui-badge-neutral null-value"} "—"]

      (= field-type :boolean)
      (ui/badge (if value [:t :common/option-yes] [:t :common/option-no])
                {:variant (if value :success :neutral)
                 :class (str "admin-bool-badge "
                             (if value "admin-bool-badge-true" "admin-bool-badge-false"))})

      (= field-type :instant)
      (str value)

      (= field-type :date)
      (str value)

      (= field-type :uuid)
      [:span.uuid-value {:class "font-mono text-xs opacity-80"} (str value)]

      (= field-type :enum)
      [:span.enum-badge {:class "badge ui-badge ui-badge-outline enum-badge"}
       (str/capitalize (name value))]

      (= field-type :json)
      [:code (str value)]

      (string? value)
      (if (> (count value) 50)
        (str (subs value 0 47) "...")
        value)

      :else
      (str value))))

;; =============================================================================
;; List Column Width Heuristics
;; =============================================================================

(def ^:private long-name-pattern
  ;; String fields whose names suggest long-form content deserve extra width.
  ;; \b word-boundaries keep this from matching substrings (e.g. "name" inside
  ;; "username"); kebab-cased names still match because '-' is a wagoe.
  #"(?i)\b(description|omschrijving|notes?|opmerking|address|adres|comment|bio|summary|samenvatting|content|inhoud|body|message|bericht|excerpt)\b")

(def ^:private medium-name-pattern
  ;; String fields that are typically a sentence-ish label.
  #"(?i)\b(name|naam|title|titel|label|subject|onderwerp|e-?mail|url|slug|path|pad)\b")

(defn list-column-weight
  "Relative width weight for a list column, used to distribute table width
   proportionally instead of evenly.

   Resolution order:
   1. Explicit `:width` in the field config (interpreted as a weight) wins.
   2. Otherwise derived from `:type`, with a name-based heuristic for strings
      (e.g. \"description\" gets more room than \"status\").

   Pure: takes a field keyword + its config map, returns a positive number."
  [field field-config]
  (or (:width field-config)
      (let [field-name (name field)]
        (case (:type field-config)
          :boolean 1
          :enum 2
          (:int :decimal :uuid :json :binary) 2
          (:date :instant) 3
          :text 6
          ;; :string and anything unrecognised fall through to the heuristic
          (cond
            (re-find long-name-pattern field-name) 6
            (re-find medium-name-pattern field-name) 4
            :else 3)))))

(defn- format-pct
  "Render a percentage with at most two decimals, dropping a trailing `.0`
   so whole numbers read as e.g. \"25%\" rather than \"25.0%\"."
  [n]
  (let [rounded (/ (Math/round (* (double n) 100.0)) 100.0)]
    (if (== rounded (Math/rint rounded))
      (str (long rounded))
      (str rounded))))

(defn list-column-styles
  "Given the ordered list-fields and the entity config, return a seq of
   Hiccup `[:col {:style ...}]` elements with proportional `width:N%` for the
   data columns. The select/actions framing columns are sized via CSS classes
   elsewhere, so widths here sum to 100% of the remaining data area.

   Returns a seq (not a vector) so Hiccup splices the elements into the
   surrounding `:colgroup` rather than treating them as a single element.

   Pure helper — no I/O."
  [list-fields entity-config]
  (let [weights (mapv (fn [field]
                        (list-column-weight field (get-in entity-config [:fields field])))
                      list-fields)
        total   (max (reduce + 0 weights) 1)
        rounded (mapv (fn [w] (/ (Math/round (/ (* 10000.0 w) total)) 100.0)) weights)
        ;; The last column absorbs the rounding remainder so the widths sum to
        ;; exactly 100% instead of drifting to 99.99% / 100.01%.
        pcts    (if (seq rounded)
                  (let [head (pop rounded)]
                    (conj head (- 100.0 (reduce + 0.0 head))))
                  rounded)]
    (for [p pcts]
      [:col {:style (str "width:" (format-pct p) "%")}])))

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn format-field-label
  "Format field name as human-readable label.

   Args:
     field-name: Keyword field name

   Returns:
     Capitalized string label"
  [field-name]
  (-> field-name
      name
      (str/replace #"[-_]" " ")
      str/capitalize))

(defn get-field-errors
  "Extract errors for a specific field from validation result.

   Args:
     errors: Validation errors map or vector
     field-name: Keyword field name

   Returns:
     Vector of error messages for the field"
  [errors field-name]
  (cond
    (map? errors)
    (get errors field-name [])

    (vector? errors)
    (filterv #(= field-name (:field %)) errors)

    :else
    []))
