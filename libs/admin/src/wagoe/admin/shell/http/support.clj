(ns wagoe.admin.shell.http.support
  "Shared plumbing for the admin HTTP layer.

   Leaf namespace requiring no handler or route namespaces. Provides the
   error mappings, query/form parsing, and handler helpers used by the handler
   namespaces and the route definitions in `wagoe.admin.shell.http`."
  (:require
   [wagoe.admin.core.display :as display]
   [wagoe.admin.core.forms :as forms]
   [wagoe.admin.core.permissions :as core-permissions]
   [wagoe.admin.core.schema-introspection :as introspection]
   [wagoe.admin.core.ui :as admin-ui]
   [wagoe.admin.core.ui.base :as ui-base]
   [wagoe.admin.core.ui.filters :as ui-filters]
   [wagoe.admin.ports :as ports]
   [wagoe.core.utils.redirect :as redirect]
   [wagoe.admin.shell.permissions :as shell-permissions]
   [wagoe.i18n.shell.middleware :as i18n-middleware]
   [wagoe.i18n.shell.render :as i18n]
   [wagoe.platform.core.http.problem-details :as problem-details]
   [wagoe.shared.ui.core.combobox :as combobox]
   [wagoe.shared.ui.core.htmx :as htmx]
   [wagoe.shared.ui.core.table :as table-ui]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [ring.util.codec :as codec]
   [ring.util.response :as ring-response])
  (:import [java.io File]
           [java.nio.file Files]
           [java.util UUID]))

;; =============================================================================
;; Error Mappings
;; =============================================================================

(def combined-error-mappings
  "The :type -> status table admin errors are answered with. Admin throws the
   platform's own types, so the platform's status and this one agree (BOU-586)."
  problem-details/default-error-mappings)

;; =============================================================================
;; Query Parameter Parsing
;; =============================================================================

(defn parse-query-params
  "Admin service options from a Ring query-params map: the table state
   (`table-ui/parse-table-params`, :dir as :sort-dir), :limit/:offset, and
   filters in both the `filters[f][op]` and the plain `role=admin` format.
   Blank filters are kept, so the filter drawer can re-render them.
   `add_filter_field` / `remove_filter` add or drop one."
  [params]
  (let [params   (update-keys params #(if (keyword? %) (name %) (str %)))
        {:keys [dir] :as tq} (table-ui/parse-table-params params {:keep-blank-filters? true})
        limit    (some-> (get params "limit") parse-long)
        offset   (some-> (get params "offset") parse-long)
        add-f    (get params "add_filter_field")
        remove-f (get params "remove_filter")
        reserved #{"page" "page-size" "limit" "offset" "sort" "sort-dir" "dir" "search"
                   "add_filter_field" "remove_filter"}
        simple   (into {}
                       (keep (fn [[k v]]
                               (when-not (or (reserved k) (str/starts-with? k "filters["))
                                 [(keyword k) {:op :eq :value v}])))
                       params)
        filters  (cond-> (merge simple (:filters tq))
                   (not (str/blank? add-f))    (assoc (keyword add-f) {:op :eq :value ""})
                   (not (str/blank? remove-f)) (dissoc (keyword remove-f)))]
    (cond-> (dissoc tq :dir :filters)
      dir           (assoc :sort-dir dir)
      limit         (assoc :limit limit)
      offset        (assoc :offset offset)
      (seq filters) (assoc :filters filters))))

;; =============================================================================
;; Form Data Parsing
;; =============================================================================

(defn- no-file?
  "A file input left empty: the browser still sends the part, with no name."
  [v]
  (if (map? v) (str/blank? (:filename v)) (and (string? v) (str/blank? v))))

(defn- upload-bytes
  "The content of a multipart file part, which Ring stores in a temp file."
  [field-keyword v]
  (if (instance? File (:tempfile v))
    (Files/readAllBytes (.toPath ^File (:tempfile v)))
    (throw (ex-info "Not a file upload"
                    {:type :validation-error
                     :field field-keyword
                     :message (str "Field '" (name field-keyword) "' must be an uploaded file")}))))

(defn parse-form-params
  "Parse form parameters into entity data map.

   Converts string form values to appropriate types based on field config.

   Args:
     params: Ring form-params map (all string values)
     entity-config: Entity configuration with field metadata

   Returns:
     Entity data map with typed values

   Examples:
     (parse-form-params {name John active true} entity-config)
     => {:name John :active true}

     (parse-form-params {price 19.99 quantity 5} entity-config)
     => {:price 19.99 :quantity 5}"
  ([params entity-config]
   ;; No form zone: read a zone-less value in the server's zone, which is how
   ;; the database would have read it anyway.
   (let [server (java.time.ZoneId/systemDefault)]
     (parse-form-params params entity-config {:input-zone server :server-zone server})))
  ([params entity-config {:keys [input-zone server-zone offsets]}]
   (reduce-kv
    (fn [acc field-name value]
      (let [field-keyword (keyword field-name)
            field-config (get-in entity-config [:fields field-keyword])
            field-type (:type field-config :string)

            ; Handle array values (e.g., from checkbox + hidden field pattern)
            ; Take the last value when multiple values are submitted
            normalized-value (if (vector? value)
                               (last value)
                               value)
            ;; A file input left empty keeps the stored file
            keep-stored? (and (= field-type :binary) (no-file? normalized-value))

             ; Convert string value to appropriate type
            typed-value (cond
                          keep-stored? nil

                          ;; A JSON null clears the column
                          (nil? normalized-value) nil

                          (= field-type :binary)
                          (upload-bytes field-keyword normalized-value)

                           ; Empty strings become nil
                          (str/blank? normalized-value) nil

                           ; Boolean checkbox values
                           ; Checked: sends "true" (from value attribute)
                           ; Unchecked: sends "false" (from hidden field)
                          (= field-type :boolean)
                          (= normalized-value "true")

                           ; Integer values. `parse-long` answers nil for unreadable
                           ; input rather than throwing, so the catch below never
                           ; fired and "forty" was written as NULL, silently clearing
                           ; the field (BOU-521). nil is the failure signal.
                          (= field-type :int)
                          (try
                            (or (parse-long normalized-value)
                                (throw (NumberFormatException. normalized-value)))
                            (catch NumberFormatException _
                              (throw (ex-info "Invalid integer value"
                                              {:type :validation-error
                                               :field field-keyword
                                               :value normalized-value
                                               :message (str "Field '" (name field-keyword) "' must be a valid integer")}))))

                           ; Decimal values - wrap in try/catch for invalid input
                          (= field-type :decimal)
                          (try
                            (bigdec normalized-value)
                            (catch NumberFormatException _
                              (throw (ex-info "Invalid decimal value"
                                              {:type :validation-error
                                               :field field-keyword
                                               :value normalized-value
                                               :message (str "Field '" (name field-keyword) "' must be a valid decimal")}))))

                           ; An instant is entered as wall time in the form's zone and
                           ; stored with the server's offset, so every database reads
                           ; back the moment that was meant (BOU-523).
                          (= field-type :instant)
                          (or (ui-base/parse-datetime-input normalized-value input-zone server-zone
                                                            (get offsets field-keyword))
                              (throw (ex-info "Invalid date-time value"
                                              {:type :validation-error
                                               :field field-keyword
                                               :value normalized-value
                                               :message (str "Field '" (name field-keyword)
                                                             "' must be a date and time")})))

                           ; A calendar date is exactly YYYY-MM-DD: no time part and no
                           ; zone (BOU-519 decision). The date widget only sends that
                           ; shape; anything else is a hand-crafted request and was
                           ; written straight to the table (BOU-521).
                          (= field-type :date)
                          (if (and (re-matches #"\d{4}-\d{2}-\d{2}" normalized-value)
                                  ;; The shape alone let `2024-02-31` through, and the
                                  ;; DATE column rejected it at the write: a 500.
                                  ;; ISO_LOCAL_DATE resolves strictly, so an impossible
                                  ;; day is refused here instead.
                                   (try (java.time.LocalDate/parse normalized-value) true
                                        (catch java.time.format.DateTimeParseException _ false)))
                            normalized-value
                            (throw (ex-info "Invalid date value"
                                            {:type :validation-error
                                             :field field-keyword
                                             :value normalized-value
                                             :message (str "Field '" (name field-keyword)
                                                           "' must be a date as YYYY-MM-DD")})))

                           ; UUID values - wrap in try/catch for invalid input
                          (= field-type :uuid)
                          (try
                            (UUID/fromString normalized-value)
                            (catch IllegalArgumentException _
                              (throw (ex-info "Invalid UUID value"
                                              {:type :validation-error
                                               :field field-keyword
                                               :value normalized-value
                                               :message (str "Field '" (name field-keyword) "' must be a valid UUID")}))))

                           ; Default: keep as string
                          :else normalized-value)]

       ;; A submitted field is kept even when it is empty, as nil. Dropping it
       ;; made "cleared this field" indistinguishable from "did not submit
       ;; it": an optional field could not be emptied — the old value survived
       ;; the write — and a required field emptied by mistake passed
       ;; validation instead of being rejected (BOU-477). A field the form did
       ;; not submit is still absent, which is what makes a partial update
       ;; partial.
        (if keep-stored?
          acc
          (assoc acc field-keyword typed-value))))
    {}
    params)))

;; =============================================================================
;; Handler Helpers
;; =============================================================================

(defn parse-form-params-checked
  "Like `parse-form-params`, but returns `[data field-errors]` instead of
   throwing on a value that cannot be read as its field's type.

   `parse-form-params` throws a :validation-error on the first bad field, and
   the create and update handlers call it outside their error handling, so an
   invalid integer, decimal, UUID or date answered 500 rather than re-rendering
   the form (BOU-521). Parsing field by field reports every bad field at once,
   in the {field [message]} shape the form already renders, and keeps what the
   user typed in `data` so the re-rendered form shows it."
  ([params entity-config] (parse-form-params-checked params entity-config nil))
  ([params entity-config zones]
   (reduce-kv
    (fn [[data errors] field-name value]
      (try
        [(merge data (if zones
                       (parse-form-params {field-name value} entity-config zones)
                       (parse-form-params {field-name value} entity-config)))
         errors]
        (catch clojure.lang.ExceptionInfo e
          (let [{:keys [type field message]} (ex-data e)]
            (if (= :validation-error type)
              [(assoc data field (if (vector? value) (last value) value))
               (assoc errors field [message])]
              (throw e))))))
    [{} {}]
    params)))

(defn get-current-user
  "Extract authenticated user from request.

   The authentication middleware sets [:user {...}] with the full user entity
   (including :id, :role, :email, :name, etc.).

   Args:
     request: Ring request map

   Returns:
     Full user entity map, or nil if not authenticated"
  [request]
  (:user request))

(defn- readable-entity-configs
  "{entity-name config} for the admin's entities. Only :allowlist discovery
   lists, and an entity whose config cannot be resolved cannot be read."
  [schema-provider]
  (into {}
        (keep (fn [e] (try [e (ports/get-entity-config schema-provider e)]
                           (catch Exception _ nil))))
        (try (ports/list-available-entities schema-provider)
             (catch clojure.lang.ExceptionInfo _ []))))

(defn require-admin-user!
  "Assert current user may use the admin, throw if not. Under :enforce and
   :report that reads `schema-provider`'s entities.

   Args:
     request: Ring request map
     schema-provider: the admin's ISchemaProvider

   Returns:
     User entity map if admin

   Throws:
     ExceptionInfo with :type :forbidden if not admin"
  [request schema-provider]
  (let [user (get-current-user request)]
    (shell-permissions/assert-can-access-admin!
     user (when (not= :off (core-permissions/mode user)) (readable-entity-configs schema-provider)))
    user))

(defn get-entity-name
  "Extract entity name from path parameters.

   Args:
     request: Ring request map

   Returns:
     Entity name as keyword"
  [request]
  (keyword (get-in request [:path-params :entity])))

(defn parse-id
  "`s` as a record's UUID. One that is not a UUID names no record: :not-found."
  [s]
  (or (when (string? s) (parse-uuid s))
      (throw (ex-info "Entity not found" {:type :not-found :id s}))))

(defn get-entity-id
  "The record id in the path, a UUID; :not-found when it is not one."
  [request]
  (parse-id (get-in request [:path-params :id])))

(defn t-fn
  "The request's translation function; without i18n on the request, one that
   answers the key's name."
  [request]
  (or (i18n-middleware/resolve-t-fn request)
      (get request :i18n/t)
      (fn
        ([k] (name k))
        ([k _params] (name k))
        ([k _params _n] (name k)))))

(defn refusal?
  "Whether `e` is a write refused by a rule: a :conflict, shown to the user
   rather than raised."
  [e]
  (= :conflict (:type (ex-data e))))

(defn rejection
  "The field errors of `e`, a :validation-error naming its fields, such as a
   value the database refused as taken; nil otherwise."
  [e]
  (let [{:keys [type errors]} (ex-data e)]
    (when (= :validation-error type) (not-empty errors))))

(defn attempt
  "Call `f`: [result nil], or [nil e] when it throws a refusal or a rejection."
  [f]
  (try [(f) nil]
       (catch clojure.lang.ExceptionInfo e
         (if (or (refusal? e) (rejection e)) [nil e] (throw e)))))

(defn refusal-message
  "What to tell the user about refusal `e`: its :message-key translated, a
   unique key's translation, or its message."
  [request e]
  (let [{:keys [message-key constraint]} (ex-data e)]
    (cond
      message-key            ((t-fn request) message-key)
      (= :unique constraint) ((t-fn request) :admin/refused-duplicate-values)
      (= :check constraint)  ((t-fn request) :admin/refused-check)
      :else                  (ex-message e))))

(defn conflict-errors
  "Field errors for a key over several columns the database refused: each of
   its fields marked, since no one of them is wrong alone (BOU-712)."
  [e]
  (let [{:keys [constraint fields]} (ex-data e)]
    (when (and (= :unique constraint) (seq fields))
      (zipmap fields (repeat [[:t :admin/error-duplicate-values]])))))

(defn html-response
  "Create HTML response with standard headers, resolving [:t ...] i18n markers.

   Args:
     request: Ring request map (used to extract :i18n/t translation function)
     html: Hiccup data structure or HTML string

   Returns:
     Ring response map"
  [request html]
  (let [body-content (i18n/render html (t-fn request))]
    (-> (ring-response/response body-content)
        (ring-response/content-type "text/html; charset=utf-8"))))

(defn htmx-fragment-response
  "Create HTMX fragment response, resolving [:t ...] i18n markers.

   Args:
     request: Ring request map
     html: Hiccup data or HTML string
     oob-fragments: (optional) elements swapped out of band, by id — a badge
                    counter or a toast beside the main fragment

   Returns:
     Ring response map with HTMX headers"
  ([request html]
   (htmx-fragment-response request html nil))
  ([request html oob-fragments]
   (let [t (t-fn request)]
     (-> (html-response request (->> (cons html (map htmx/oob oob-fragments))
                                     (map #(i18n/render % t))
                                     (apply str)))
         (ring-response/header "HX-Trigger" "entityListUpdated")))))

(defn create-config-error-response
  "The admin page saying why `entity-name` cannot be created, or nil when it
   can. A create form that could never be saved is refused up front (BOU-494).
   A 500: the config is at fault, not the request."
  [request config schema-provider user entity-name entity-config]
  (when (seq (:create-config-errors entity-config))
    (let [entities       (ports/list-available-entities schema-provider)
          entity-configs (into {} (map (fn [e] [e (ports/get-entity-config schema-provider e)])) entities)]
      (-> (html-response request
                         (admin-ui/admin-layout
                          (admin-ui/create-config-error entity-name entity-config)
                          {:user           user
                           :current-entity entity-name
                           :entity-configs entity-configs
                           :logo-url       (:logo-url config)}))
          (ring-response/status 500)))))

(defn create-disabled-response
  "The admin 403 page for an entity with `:permissions {:create false}`, or
   nil when create is allowed. Names `:create-hint` when the config has one
   (BOU-534)."
  [request user entity-name entity-config]
  (let [{:keys [create create-hint]} (:permissions entity-config)]
    (when (false? create)
      (let [label (:label entity-config)
            label (if (string? label) label (name entity-name))]
        (-> (html-response request
                           (admin-ui/admin-forbidden-page
                            [:span
                             [:t :admin/create-disabled {:entity label}]
                             (when create-hint
                               [:span " " [:t :admin/create-disabled-hint {:hint create-hint}]])]
                            user))
            (ring-response/status 403))))))

;; =============================================================================
;; Display Options
;; =============================================================================

(defn valid-date-pattern
  "The pattern, or nil with a warning when `DateTimeFormatter` rejects it.
   Dropping it means one bad character in `config.edn` costs a column its
   formatting rather than the whole page — the renderer falls back to its
   default pattern.

   Called once from the module wiring, not per request: a misconfigured
   application should say so at boot, rather than log the same warning on every
   admin page load and every HTMX table refresh for the life of the process."
  [pattern]
  (cond
    (nil? pattern) nil

    ;; `ofPattern` takes a String and casts; `:date-format :iso` in config.edn
    ;; would throw a ClassCastException with no `:type` and 500 every admin page.
    (not (string? pattern))
    (do (log/warn "Ignoring non-string admin date pattern" {:pattern pattern})
        nil)

    :else
    (try
      (java.time.format.DateTimeFormatter/ofPattern pattern)
      pattern
      (catch IllegalArgumentException e
        (log/warn "Ignoring invalid admin date pattern"
                  {:pattern pattern :reason (ex-message e)})
        nil))))

(defn- request-locale
  "The locale the i18n middleware resolved for this request, as a
   `java.util.Locale`. Textual patterns (`dd MMM yyyy`) render month and day
   names through it; without it they follow the server's JVM default while the
   rest of the page is translated."
  [request]
  (when-let [loc (or (first (:i18n/locale-chain request))
                     (:i18n/default-locale request))]
    (java.util.Locale/forLanguageTag (name loc))))

(def zone-cookie
  "Set by init.js to the browser's IANA zone (`Intl…resolvedOptions().timeZone`)."
  "wagoe_tz")

(def default-time-zone
  "The zone timestamps are shown and entered in when neither the browser nor
   `:time-zone` in :wagoe/settings names one. A presentation default only:
   storage is zone-aware and the JVM runs in UTC (BOU-431), so this changes
   what people see, never what is stored."
  (java.time.ZoneId/of "Europe/Amsterdam"))

(defn- ->zone
  "A ZoneId for `s`, or nil when it is blank or not a zone the JVM knows. The
   value comes from a cookie or a form field, so it is untrusted input."
  [s]
  (when-not (str/blank? s)
    (try (java.time.ZoneId/of (str/trim s))
         (catch java.time.DateTimeException _ nil))))

(defn- cookie-value
  "The value of cookie `cookie-name` from the raw Cookie header. The admin
   routes run without Ring's cookie middleware, so it is read here."
  [request cookie-name]
  (some (fn [pair]
          (let [[k v] (str/split (str/trim pair) #"=" 2)]
            ;; init.js writes it with encodeURIComponent: `America%2FNew_York`.
            (when (and (= k cookie-name) v)
              (try (java.net.URLDecoder/decode ^String v "UTF-8")
                   (catch IllegalArgumentException _ nil)))))
        (some-> (get-in request [:headers "cookie"]) (str/split #";"))))

(defn display-options
  "Zones, locale and date patterns for rendering stored timestamps in the admin UI.

   Two zones (BOU-523). `:server-zone-id` is the JVM zone — the one the
   database reads a zone-less timestamp in, so the one such a value is read
   back in. `:zone-id` is the zone timestamps are shown and entered in: the
   browser's, from the `wagoe_tz` cookie; else the application's configured
   `:time-zone`; else `default-time-zone`, Europe/Amsterdam. Not the server's:
   that is UTC by design (BOU-431) and says nothing about the people using it.

   Read here because `wagoe.admin.core.ui.base` may not: check:fcis bans
   `ZoneId/systemDefault` in a core namespace. The patterns come from the
   application's `:wagoe/settings` (BOU-382)."
  [config request]
  (let [server (java.time.ZoneId/systemDefault)]
    {:zone-id          (or (->zone (cookie-value request zone-cookie))
                           (->zone (:time-zone config))
                           default-time-zone)
     :server-zone-id   server
     :locale           (request-locale request)
     :date-time-format (:date-time-format config)
     :date-format      (:date-format config)}))

(defn overview
  "What the cells of `records` need (ADR-040), or nil when the service does
   not provide it."
  [admin-service entity-name options records]
  (when (satisfies? ports/IListOverview admin-service)
    (ports/list-overview admin-service entity-name options records)))

(defn display-with-overview
  "The display options, with the overview of `records`."
  [admin-service config request entity-name options records]
  (assoc (display-options config request)
         :overview (overview admin-service entity-name options records)))

(defn form-zone-options
  "The zones to parse a submitted form's :instant fields in, and `params`
   without the `__zone` field that carried them.

   The form says which zone it was rendered in, and parsing uses exactly that:
   deriving it from the cookie again would shift every value on the first
   visit, when the page was rendered before the cookie existed. A missing or
   unknown `__zone` falls back to the same resolution the page used."
  [config request params]
  (let [display      (display-options config request)
        offset-param #(some-> % name (str/starts-with? "__offset."))
        ;; `__offset.<field>`: the offset each datetime was rendered with, to
        ;; tell the two occurrences of a repeated local time apart.
        offsets      (into {}
                           (keep (fn [[k v]]
                                   (when (offset-param k)
                                     [(keyword (subs (name k) (count "__offset."))) v])))
                           params)]
    [{:input-zone  (or (->zone (get params "__zone")) (:zone-id display))
      :server-zone (:server-zone-id display)
      :offsets     offsets}
     (into {} (remove (fn [[k _]] (or (offset-param k) (#{"__zone" :__zone} k)))) params)]))

;; =============================================================================
;; Return-to and has-many prefill (BOU-491)
;; =============================================================================

(defn safe-return-to
  "The request's `return_to`, or nil unless it is a path inside the admin, so
   it cannot redirect off-site."
  [request]
  (let [return-to (get-in request [:query-params "return_to"])]
    (when (and (redirect/local-path? return-to)
               (str/starts-with? return-to "/web/admin/"))
      return-to)))

(defn foreign-key-prefill
  "Starting values for a create form of `entity-name`, from the query string
   a parent's \"New\" link builds. Only a field some entity names as the
   `:foreign-key` of a has-many on `entity-name` is taken, and only when its
   value is a UUID."
  [entity-name entity-configs query-params]
  (let [fk-fields (into #{}
                        (comp (mapcat :has-many)
                              (filter #(= entity-name (:entity %)))
                              (keep :foreign-key)
                              (map #(keyword (str/replace (name %) "_" "-"))))
                        (vals entity-configs))]
    (into {}
          (keep (fn [field]
                  (when-let [id (some-> (get query-params (name field)) parse-uuid)]
                    [field id])))
          fk-fields)))

;; =============================================================================
;; Foreign-key pickers and workflow state (BOU-563)
;; =============================================================================

(defn- label-field
  "The field a parent row is named by in a picker: its `:title-field`, what
   its relation cells show."
  [parent-cfg]
  (or (:title-field parent-cfg)
      (first (:search-fields parent-cfg))
      (:primary-key parent-cfg :id)))

(defn- text-label?
  "Whether a parent's label can be searched as text: a combobox filters it
   with LIKE, which an integer column refuses on PostgreSQL."
  [parent-cfg]
  (contains? #{:string :text} (get-in parent-cfg [:fields (label-field parent-cfg) :type])))

(defn- row-label [parent-cfg row]
  (let [pk (:primary-key parent-cfg :id)]
    (str (or (get row (label-field parent-cfg)) (get row pk)))))

(defn- row->option [parent-cfg row]
  [(str (get row (:primary-key parent-cfg :id))) (row-label parent-cfg row)])

(defn options-url
  "Where the combobox of foreign key `field` of `entity-name` reads its options."
  [entity-name field]
  (str "/web/admin/" (name entity-name) "/options/" (name field)))

(defn- current-label
  "What the combobox shows for the parent `id`: its label, from `rows` when it
   is there, or the id when the row cannot be read."
  [admin-service parent parent-cfg rows id]
  (or (some #(when (= id (first %)) (second %)) (map #(row->option parent-cfg %) rows))
      (try (some->> (ports/get-entity admin-service parent id) (row-label parent-cfg))
           (catch Exception e
             (log/warn e "foreign key shown by its id: parent row unreadable" {:parent parent :id id})
             nil))
      id))

(defn foreign-key-options
  "The choices for each editable foreign key of `entity-name`: the parent's
   rows as [id label], where the label is the parent's `:title-field` — what
   its relation cells show. Only when every parent fits in the maximum page
   size: a select capped there would silently offer a subset, so past it the
   key is a combobox, {:url :label}, searching the label — or a text input
   when the label is not text. An optional key can be left
   empty. `fields` limits it to those keys; the form's editable fields by
   default."
  ([admin-service config entity-configs entity-name values]
   (foreign-key-options admin-service config entity-configs entity-name
                        (get-in entity-configs [entity-name :editable-fields]) values))
  ([admin-service config entity-configs entity-name fields values]
  (let [entity-config (get entity-configs entity-name)
        page-size     (get-in config [:pagination :max-page-size] 200)]
    (into {}
          (for [[field parent] (introspection/declared-parents entity-name entity-configs)
                :when (some #{field} fields)
                :let  [parent-cfg (get entity-configs parent)
                       label      (label-field parent-cfg)
                       page       (try (ports/list-entities admin-service parent
                                                            {:limit page-size :sort label :sort-dir :asc})
                                       (catch Exception e
                                         (log/warn e "foreign key left as a text input: parent rows unreadable"
                                                   {:entity entity-name :field field :parent parent})
                                         ::unreadable))
                       rows       (:records page)
                       current    (some-> (get values field) str)
                       readable?  (not= ::unreadable page)
                       fits?      (and readable? (<= (:total-count page 0) (count rows)))]
                :when (and readable? (or fits? (text-label? parent-cfg)))]
            (if-not fits?
              [field {:url   (options-url entity-name field)
                      :label (when current (current-label admin-service parent parent-cfg rows current))}]
              (let [options (mapv #(row->option parent-cfg %) rows)]
                [field (cond->> (cond-> options
                                  ;; A parent the list does not show (soft-deleted): kept,
                                  ;; so saving the form does not change it.
                                  (and current (not-any? #(= current (first %)) options))
                                  (conj [current current]))
                         (not (get-in entity-config [:fields field :required])) (into [["" "—"]]))])))))))

(defn- all-entity-configs [schema-provider]
  (into {} (map (fn [e] [e (ports/get-entity-config schema-provider e)]))
        (ports/list-available-entities schema-provider)))

(defn field-options
  "The choices `field` of `entity-name` is rendered with, as
   `foreign-key-options` finds them, or nil when it is no foreign key. Reads
   only the parent of that field."
  [admin-service schema-provider config entity-name field values]
  (let [entity-configs (all-entity-configs schema-provider)]
    (when-let [parent (get (introspection/declared-parents entity-name entity-configs) field)]
      ;; Only `field`, so a second key to the same parent is not read
      (get (foreign-key-options admin-service config (select-keys entity-configs [entity-name parent])
                                entity-name [field] values)
           field))))

(defn- viewable-parents
  "{foreign-key-field parent} of `entity-name`, for the parents `user` may view."
  [entity-configs user entity-name]
  (into {}
        (filter (fn [[_ parent]] (core-permissions/can-view-entity? user parent (get entity-configs parent))))
        (introspection/declared-parents entity-name entity-configs)))

(defn filter-field-options
  "The choices of each filterable foreign key of `entity-name`, as
   `foreign-key-options` finds them, for the parents `user` may view: a
   filter lists them as the form does. `filters` are the current ones, whose
   values the comboboxes show by label."
  [admin-service schema-provider config user entity-name filters]
  (let [entity-configs (all-entity-configs schema-provider)
        parents        (viewable-parents entity-configs user entity-name)
        fields         (filter parents (ui-filters/filterable-fields (get entity-configs entity-name)))]
    (when (seq fields)
      ;; Blank ones are kept for the drawer; they name no parent to look up
      (foreign-key-options admin-service config entity-configs entity-name fields
                           (into {} (keep (fn [[k spec]] (some->> (:value spec) str not-empty (vector k))))
                                 filters)))))

(defn with-filter-value-labels
  "`entity-config` with each active foreign-key filter's parent label under
   `[:fields field :value-labels]`, so its chip names the parent rather than
   its id."
  [admin-service schema-provider user entity-name entity-config filters]
  (if (empty? filters)
    entity-config
    (let [entity-configs (all-entity-configs schema-provider)]
      (reduce-kv (fn [cfg field parent]
                   (if-let [v (some-> (get-in filters [field :value]) str not-empty)]
                     (assoc-in cfg [:fields field :value-labels]
                               {v (current-label admin-service parent (get entity-configs parent) nil v)})
                     cfg))
                 entity-config
                 (viewable-parents entity-configs user entity-name)))))

(defn viewable-entity!
  "[user entity-name entity-config] for the entity in the path, which the
   signed-in admin may view. Throws :not-found or :forbidden."
  [schema-provider request]
  (let [user        (require-admin-user! request schema-provider)
        entity-name (get-entity-name request)]
    (when-not (ports/validate-entity-exists schema-provider entity-name)
      (throw (ex-info "Unknown entity" {:type :not-found :entity-name entity-name})))
    (let [entity-config (ports/get-entity-config schema-provider entity-name)]
      (shell-permissions/assert-can-view-entity! user entity-name entity-config)
      [user entity-name entity-config])))

(defn search-entity-options
  "The first rows of `entity-name` by label whose label contains the typed
   `q`, as [id label], for a combobox choosing one of them. Nil when its label
   is not text."
  [admin-service entity-cfg entity-name q]
  (when (text-label? entity-cfg)
    (let [label (label-field entity-cfg)]
      (mapv #(row->option entity-cfg %)
            (:records (ports/list-entities admin-service entity-name
                                           (cond-> {:limit combobox/limit :sort label :sort-dir :asc}
                                             q (assoc :filters {label {:op :contains :value q}}))))))))

(defn search-foreign-key-options
  "What the combobox of foreign key `field` of `entity-name` offers for the
   typed `q`: {:parent :parent-cfg :options}, where the options are the first
   parent rows, by label, whose label contains `q`, as [id label]. A
   many-to-many key searches the entity it links (BOU-778). Nil when `field`
   is neither, or its parent's label is not text."
  [admin-service schema-provider entity-name field q]
  (let [entity-configs (all-entity-configs schema-provider)]
    (when-let [parent (or (get (introspection/declared-parents entity-name entity-configs) field)
                          (:entity (display/link-rel (get entity-configs entity-name) field)))]
      (let [parent-cfg (get entity-configs parent)]
        (when-let [options (search-entity-options admin-service parent-cfg parent q)]
          {:parent     parent
           :parent-cfg parent-cfg
           :options    options})))))

(def ^:private checkbox-limit
  "The most records a many-to-many offers as checkboxes; past it, a combobox."
  10)

(defn- chosen-links
  "[[id label] ...] for `ids` of `parent`, in that order; an id that is not
   there (deleted since) is left out."
  [admin-service parent parent-cfg ids]
  (if (empty? ids)
    []
    ;; In pages: the service caps a read at :max-page-size
    (let [pk     (:primary-key parent-cfg :id)
          by-id  (into {} (comp (mapcat (fn [part]
                                          (:records (ports/list-entities admin-service parent
                                                                         {:limit   (count part)
                                                                          :filters {pk {:op :in :values (vec part)}}}))))
                                (map #(row->option parent-cfg %))
                                (map (juxt first identity)))
                       (partition-all 100 ids))]
      (into [] (keep by-id) ids))))

(defn- all-options
  "Every record of `parent` as [id label], by label, or nil when there are
   more than `limit`."
  [admin-service parent parent-cfg limit]
  (let [page (ports/list-entities admin-service parent
                                  {:limit limit :sort (label-field parent-cfg) :sort-dir :asc})]
    (when (<= (:total-count page 0) limit)
      (mapv #(row->option parent-cfg %) (:records page)))))

(defn- link-field
  "One relation of `link-fields`, or nil when it cannot be edited."
  [admin-service config entity-name record-id links {k :key parent :entity :as rel} parent-cfg]
  (let [chosen  (cond
                  (contains? links k)
                  (chosen-links admin-service parent parent-cfg (get links k))

                  record-id
                  (mapv (juxt :id :title)
                        (:items (get (ports/linked-records admin-service entity-name k [record-id] {})
                                     (str record-id))))

                  :else [])
        options (or (all-options admin-service parent parent-cfg checkbox-limit)
                    (when-not (text-label? parent-cfg)
                      (all-options admin-service parent parent-cfg
                                   (get-in config [:pagination :max-page-size] 200))))]
    (cond
      options                  {:rel rel :chosen chosen :options options}
      (text-label? parent-cfg) {:rel rel :chosen chosen :url (options-url entity-name k)})))

(defn link-fields
  "How each many-to-many relation of `entity-name` is edited:
   {key {:rel :chosen [[id label] ...] :options [[id label] ...] | :url}}.
   Checkboxes (`:options`) while the linked entity has at most
   `checkbox-limit` records, else a multiple combobox. The chosen ids come
   from `links` ({key [id ...]}, a form shown again) for a key it has, else
   from the record `record-id`. A linked entity whose label is not text
   cannot be searched: up to the maximum page size it is checkboxes too,
   past it left off the form. So is one that cannot be read. Nil for a
   service without links."
  [admin-service config entity-configs entity-name record-id links]
  (when (satisfies? ports/ILinks admin-service)
    (not-empty
     (into {}
           (for [{k :key parent :entity :as rel} (:many-to-many (get entity-configs entity-name))
                 :let  [found (try (link-field admin-service config entity-name record-id links rel
                                               (get entity-configs parent))
                                   (catch Exception e
                                     (log/warn e "many-to-many left off the form: linked records unreadable"
                                               {:entity entity-name :relation k})
                                     nil))]
                 :when found]
             [k found])))))

(defn with-link-options
  "`entity-config` with, on each many-to-many relation `keep?` accepts, the
   records its filter offers, while they fit in the maximum page size. Past
   it the filter takes an id."
  [admin-service schema-provider config entity-config keep?]
  (update entity-config :many-to-many
          (fn [rels]
            (mapv (fn [{parent :entity :as rel}]
                    (cond-> rel
                      (keep? rel)
                      (assoc :options (try (all-options admin-service parent
                                                        (ports/get-entity-config schema-provider parent)
                                                        (get-in config [:pagination :max-page-size] 200))
                                           (catch Exception e
                                             (log/warn e "many-to-many filter takes an id: linked records unreadable"
                                                       {:relation (:key rel)})
                                             nil)))))
                  rels))))

(defn nested-relationships
  "The has-many entries `entity-config` is created with, as
   `forms/nested-relationships` finds them (BOU-570)."
  [schema-provider entity-config]
  (forms/nested-relationships
   entity-config
   (into {} (for [rel   (:has-many entity-config)
                  :when (and (:editable rel) (:min rel)
                             (ports/validate-entity-exists schema-provider (:entity rel)))]
              [(:entity rel) (ports/get-entity-config schema-provider (:entity rel))]))))

(defn with-workflow-states
  "`records`, each with `:admin/workflow` {:instance-id :state} when it has a
   workflow instance. Only for an entity with a `:workflow` config, and only
   when the workflow module is on."
  [config entity-config records]
  (let [workflows   (:workflows config)
        entity-type (get-in entity-config [:workflow :entity-type])
        pk          (:primary-key entity-config :id)]
    (if (and workflows entity-type (seq records))
      (let [states (ports/entity-workflows workflows entity-type (map #(get % pk) records))]
        (mapv #(if-let [w (get states (get % pk))] (assoc % :admin/workflow w) %) records))
      records)))

;; =============================================================================
;; Entity Detail Options (shared by detail + crud handlers)
;; =============================================================================

(defn- list-nav
  "Previous and next record in the list the page was opened from — its
   filters, search, sort and page — across a page edge, or nil when the page
   was not opened from this entity's list or the record is not on it. One list
   query, two at a page edge."
  [admin-service entity-name entity-config record return-to]
  (let [list-path (str "/web/admin/" (name entity-name))
        [path qs] (when return-to (str/split return-to #"\?" 2))]
    (when (= path list-path)
      (let [params   (if (str/blank? qs) {} (let [d (codec/form-decode qs)] (if (map? d) d {})))
            options  (let [o (parse-query-params params)
                           active (ui-filters/active-filters (:filters o))]
                       (if (seq active) (assoc o :filters active) (dissoc o :filters)))
            page-of  (fn [page] (ports/list-entities admin-service entity-name (assoc options :page page)))
            current  (page-of (:page options 1))
            pk       (:primary-key entity-config :id)
            ids      (mapv #(str (get % pk)) (:records current))
            idx      (.indexOf ^java.util.List ids (str (get record pk)))
            page     (:page-number current 1)
            size     (:page-size current)
            url      (fn [id on-page]
                       (str list-path "/" id "?return_to="
                            (ui-base/url-encode
                             (str list-path "?" (codec/form-encode (assoc params "page" (str on-page)))))))
            edge     (fn [on-page pick]
                       (some->> (:records (page-of on-page)) seq pick (#(get % pk)) str))]
        (when (>= idx 0)
          (let [prev (cond (pos? idx) [(nth ids (dec idx)) page]
                           (> page 1) (some-> (edge (dec page) last) (vector (dec page))))
                next (cond (< idx (dec (count ids))) [(nth ids (inc idx)) page]
                           (< page (:total-pages current 1)) (some-> (edge (inc page) first) (vector (inc page))))]
            (cond-> {:position (+ (* (dec page) (or size 0)) idx 1)
                     :total    (:total-count current)}
              prev (assoc :prev-url (apply url prev))
              next (assoc :next-url (apply url next)))))))))

(defn- belongs-to-filter
  "[field id] of the first `eq` filter in `filters` on a belongs-to of
   `entity-config`: a list of one parent's children."
  [entity-config filters]
  (let [bts (display/belongs-to-fields entity-config)]
    (some (fn [[field {:keys [op value]}]]
            (when (and (= :eq op) (some? value) (contains? bts field))
              [field (str value)]))
          filters)))

(defn parent-trail
  "The record a page of `entity-name` hangs under, for its breadcrumbs: the
   one `return-to` opens, or the one an `eq` filter on a belongs-to names —
   in `filters`, or in the list `return-to` points at. {:label :href :title
   :title-href :list-href}, :list-href being this entity's list of that
   record's children; nil when there is none."
  [admin-service schema-provider entity-name entity-config {:keys [filters return-to]}]
  (let [bts          (display/belongs-to-fields entity-config)
        [path qs]    (when return-to (str/split return-to #"\?" 2))
        parts        (vec (remove str/blank? (some-> path (str/split #"/"))))
        [field id]   (or (belongs-to-filter entity-config filters)
                         (when (= 4 (count parts))
                           (let [parent (keyword (nth parts 2))]
                             (some (fn [[f rel]] (when (= parent (:entity rel)) [f (nth parts 3)])) bts)))
                         (when (and (= 3 (count parts)) (= (name entity-name) (nth parts 2)) (not (str/blank? qs)))
                           (let [params (codec/form-decode qs)]
                             (when (map? params)
                               (belongs-to-filter entity-config
                                                  (ui-filters/active-filters (:filters (parse-query-params params))))))))
        parent       (:entity (get bts field))]
    (when (and parent (ports/validate-entity-exists schema-provider parent))
      (when-let [record (try (ports/get-entity admin-service parent id)
                             (catch Exception _ nil))]
        (let [pcfg (ports/get-entity-config schema-provider parent)
              pk   (:primary-key pcfg :id)]
          {:label      (:label pcfg)
           :href       (str "/web/admin/" (name parent))
           :title      (str (get record (:title-field pcfg pk) (get record pk)))
           :title-href (str "/web/admin/" (name parent) "/" id)
           :list-href  (ui-base/filtered-list-url entity-name field id)})))))

(def ^:private sibling-cap
  "The most siblings a child page reads for its prev/next navigation. A record
   past them gets none; the count reads \"500+\"."
  500)

(defn build-entity-detail-opts
  "Builds opts for entity-detail-page and the surrounding admin-layout.
   Shared between entity-detail-handler and update-entity-handler.

   Returns a map with:
     :entity-configs - map of entity-name -> entity-config
     :page-opts      - opts map for entity-detail-page
                       (related-records, return-to, parent-context, sibling-nav,
                        display, field-options, workflow)"
  [admin-service schema-provider config entity-name entity-config record request]
  (let [entities        (ports/list-available-entities schema-provider)
        entity-configs  (into {} (map (fn [e] [e (ports/get-entity-config schema-provider e)])) entities)

        ; Related records for has-many relationships on this entity.
        ; The related entity's config rides along so its table can render each
        ; cell by field type rather than printing the stored value (BOU-382).
        primary-key     (:primary-key entity-config :id)
        record-id       (str (get record primary-key))
        has-many-rels   (get entity-config :has-many [])
        parent-url      (str "/web/admin/" (name entity-name) "/" record-id)
        ; One page of children; one row more tells whether there are others.
        panel-size      (get-in config [:pagination :default-page-size] 20)
        related-records (when (seq has-many-rels)
                          (mapv (fn [rel]
                                  (let [rows (ports/list-related-entities admin-service entity-name record-id
                                                                          (assoc rel :limit (inc panel-size)))
                                        page (vec (take panel-size rows))]
                                    [(cond-> (assoc rel
                                                    :entity-config (get entity-configs (:entity rel))
                                                    :parent-id record-id
                                                    :has-more? (> (count rows) panel-size))
                                       (:editable rel) (assoc :return-to parent-url)
                                       ;; Relation titles for its cells (ADR-040)
                                       (seq page)
                                       (assoc :overview (overview admin-service (:entity rel)
                                                                  {:overview/fields (:fields rel)} page)))
                                     page]))
                                has-many-rels))

        ; Return URL when navigating back from a child entity (e.g. order-items → order)
        ; Checked: it becomes hrefs on this page (BOU-553).
        return-to   (safe-return-to request)

        ; Parse parent entity + id from return_to (/web/admin/{entity}/{id})
        parent-ref  (when return-to
                      (let [parts (remove str/blank? (str/split return-to #"/"))]
                        (when (>= (count parts) 4)
                          {:entity (keyword (nth parts 2))
                           :id     (nth parts 3)})))

        ; Parent context banner
        parent-context (when-let [ctx-cfg (:parent-context entity-config)]
                         (when (and parent-ref
                                    (ports/validate-entity-exists schema-provider (:entity parent-ref)))
                           (when-let [parent-rec (ports/get-entity admin-service
                                                                   (:entity parent-ref)
                                                                   (:id parent-ref))]
                             {:config ctx-cfg :record parent-rec})))

        ; Sibling navigation — prev/next within the parent's has-many list
        sibling-nav (when (and parent-ref (:parent-context entity-config)
                               (ports/validate-entity-exists schema-provider (:entity parent-ref)))
                      (let [parent-cfg   (ports/get-entity-config schema-provider (:entity parent-ref))
                            matching-rel (first (filter #(= (:entity %) entity-name)
                                                        (:has-many parent-cfg [])))]
                        (when matching-rel
                          (let [siblings   (ports/list-related-entities admin-service
                                                                        (:entity parent-ref)
                                                                        (:id parent-ref)
                                                                        (assoc matching-rel :limit (inc sibling-cap)))
                                more?      (> (count siblings) sibling-cap)
                                ;; The extra row is read, so the last in the window links on to it.
                                ids        (mapv #(str (get % primary-key)) siblings)
                                idx        (.indexOf ^java.util.List ids record-id)
                                nav-url    #(str "/web/admin/" (name entity-name) "/" %
                                                 "?return_to=" (ui-base/url-encode return-to))]
                            (when (< -1 idx sibling-cap)
                              (cond-> {:position (inc idx) :total (if more? (str sibling-cap "+") (count ids))}
                                (> idx 0)                (assoc :prev-url (nav-url (nth ids (dec idx))))
                                (< idx (dec (count ids))) (assoc :next-url (nav-url (nth ids (inc idx))))))))))]

    {:entity-configs entity-configs
     :page-opts      {:related-records related-records
                      :return-to       return-to
                      :parent-trail    (parent-trail admin-service schema-provider entity-name entity-config
                                                     {:return-to return-to})
                      :parent-context  parent-context
                      ;; Within a parent's children, else within the list it came from
                      :sibling-nav     (or sibling-nav
                                           (try (list-nav admin-service entity-name entity-config record return-to)
                                                (catch Exception e
                                                  (log/warn e "admin list navigation failed" {:entity entity-name})
                                                  nil)))
                      :display         (display-options config request)
                      :field-options   (foreign-key-options admin-service config entity-configs entity-name record)
                      :links           (link-fields admin-service config entity-configs entity-name
                                                    (get record primary-key) (:admin/links record))
                      :workflow        (:admin/workflow (first (with-workflow-states config entity-config [record])))}}))
