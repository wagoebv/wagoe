(ns wagoe.admin.shell.http.support
  "Shared plumbing for the admin HTTP layer.

   Leaf namespace requiring no handler or route namespaces. Provides the
   error mappings, query/form parsing, and handler helpers used by the handler
   namespaces and the route definitions in `wagoe.admin.shell.http`."
  (:require
   [wagoe.admin.core.ui.base :as ui-base]
   [wagoe.admin.ports :as ports]
   [wagoe.admin.shell.permissions :as shell-permissions]
   [wagoe.i18n.shell.middleware :as i18n-middleware]
   [wagoe.i18n.shell.render :as i18n]
   [wagoe.platform.core.http.problem-details :as problem-details]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [ring.util.response :as ring-response])
  (:import [java.util UUID]))

;; =============================================================================
;; Error Mappings - Admin-Specific RFC 7807 Problem Details
;; =============================================================================

(def admin-error-mappings
  "Error type mappings for admin-specific errors.

   Extends base error mappings with admin-specific error types:
   - :table-not-found - Entity/table doesn't exist in database
   - :entity-not-allowed - Entity not in allowlist
   - :invalid-entity-data - Validation failed on entity data"
  {:table-not-found
   {:status 404
    :type "https://wagoe.app/errors/table-not-found"
    :title "Table Not Found"
    :detail-fn (fn [ex-data] (str "Table '" (:table-name ex-data) "' does not exist"))}

   :entity-not-allowed
   {:status 403
    :type "https://wagoe.app/errors/entity-not-allowed"
    :title "Entity Not Allowed"
    :detail-fn (fn [ex-data] (str "Entity '" (:entity-name ex-data) "' is not accessible"))}

   :invalid-entity-data
   {:status 422
    :type "https://wagoe.app/errors/invalid-entity-data"
    :title "Invalid Entity Data"
    :detail-fn (fn [_ex-data] "Entity data failed validation")
    :errors-fn (fn [ex-data] (:errors ex-data))}

   :cannot-create-split-table-entity
   {:status 400
    :type "https://wagoe.app/errors/cannot-create-split-table-entity"
    :title "Cannot Create Entity"
    :detail-fn (fn [ex-data]
                 (str "Entity '" (name (:entity-name ex-data))
                      "' spans multiple tables and must be created via its dedicated"
                      " create flow (configure :create-redirect-url on the entity)."))}})

(def combined-error-mappings
  "Merged error mappings: base + admin-specific"
  (merge problem-details/default-error-mappings admin-error-mappings))

;; =============================================================================
;; Query Parameter Parsing
;; =============================================================================

(defn parse-advanced-filters
  "Parse nested filter parameters from query string (Week 2).

   Expected formats:
   - filters[field][op]=operator
   - filters[field][value]=single-value
   - filters[field][values][]=multi-value-1
   - filters[field][values][]=multi-value-2
   - filters[field][min]=min-value
   - filters[field][max]=max-value

   Args:
     params: Ring query-params map

   Returns:
     Map of field-name -> filter-spec
     {:field-name {:op :operator :value val}
      :other-field {:op :between :min 10 :max 100}}

   Example:
     (parse-advanced-filters {\"filters[created-at][op]\" \"gte\"
                              \"filters[created-at][value]\" \"2024-01-01\"})
     => {:created-at {:op :gte :value \"2024-01-01\"}}"
  [params]
  (let [;; Find all params that start with "filters["
        filter-param-pattern #"^filters\[([^\]]+)\]\[([^\]]+)\](?:\[\])?$"
        filter-entries (for [[k v] params
                             :let [match (re-matches filter-param-pattern k)]
                             :when match]
                         (let [[_ field-name filter-key] match]
                           [(keyword field-name) (keyword filter-key) v]))]

    ;; Group by field name and build filter specs
    (reduce
     (fn [acc [field-name filter-key value]]
       (update acc field-name
               (fn [existing]
                 (let [current (or existing {})]
                   (case filter-key
                     :op (assoc current :op (keyword value))
                     :value (assoc current :value value)
                     :values (update current :values (fnil conj []) value)
                     :min (assoc current :min value)
                     :max (assoc current :max value)
                     current)))))
     {}
     filter-entries)))

(defn parse-query-params
  "Parse query parameters into admin service options.

   Extracts and normalizes:
   - Pagination: page, page-size, limit, offset
   - Sorting: sort, sort-dir
   - Search: search (text search across search-fields)
   - Filters: Any other params become field filters

   Args:
     params: Ring query-params map (all string values)

   Returns:
     Options map with normalized keys and parsed values

   Examples:
     (parse-query-params {page 2 page-size 25 search john})
     => {:page 2 :page-size 25 :search john}

     (parse-query-params {sort email sort-dir desc role admin})
     => {:sort :email :sort-dir :desc :filters {:role admin}}"
  [params]
  (let [params (into {}
                     (for [[k v] params]
                       [(if (keyword? k) (name k) (str k))
                        v]))
        page (when-let [p (get params "page")] (parse-long p))
        page-size (when-let [ps (get params "page-size")] (parse-long ps))
        limit (when-let [l (get params "limit")] (parse-long l))
        offset (when-let [o (get params "offset")] (parse-long o))
        sort (when-let [s (get params "sort")]
               (keyword (if (keyword? s) (name s) (str s))))
        ; Accept both "dir" and "sort-dir" for backward compatibility
        sort-dir (when-let [sd (or (get params "dir") (get params "sort-dir"))]
                   (keyword (if (keyword? sd) (name sd) (str sd))))
        search (get params "search")
        add-filter-field (get params "add_filter_field")
        remove-filter-field (get params "remove_filter")

        ; Check for advanced filters (Week 2 format: filters[field][op]=...)
        advanced-filters (parse-advanced-filters params)

        ; Backward compatibility: Simple filters (Week 1 format: role=admin)
        ; Any params not in reserved keys and not part of advanced filters become simple filters
        reserved-keys #{"page" "page-size" "limit" "offset" "sort" "sort-dir" "dir" "search" "add_filter_field" "remove_filter"}
        advanced-filter-keys (set (filter #(str/starts-with? % "filters[") (keys params)))
        simple-filter-params (apply dissoc params (concat reserved-keys advanced-filter-keys))
        simple-filters (when (seq simple-filter-params)
                         (into {} (map (fn [[k v]] [(keyword k) {:op :eq :value v}])) simple-filter-params))

        ; Merge advanced and simple filters (advanced takes precedence)
        all-filters (merge simple-filters advanced-filters)

        ; Handle add/remove filter actions
        filters (cond
                  ; Adding a new filter - initialize with default operator
                  (and add-filter-field (not (str/blank? add-filter-field)))
                  (assoc all-filters (keyword add-filter-field) {:op :eq :value ""})

                  ; Removing a filter
                  (and remove-filter-field (not (str/blank? remove-filter-field)))
                  (dissoc all-filters (keyword remove-filter-field))

                  ; Default: use parsed filters
                  :else
                  all-filters)]

    (cond-> {}
      page (assoc :page page)
      page-size (assoc :page-size page-size)
      limit (assoc :limit limit)
      offset (assoc :offset offset)
      sort (assoc :sort sort)
      sort-dir (assoc :sort-dir sort-dir)
      search (assoc :search search)
      (seq filters) (assoc :filters filters))))

;; =============================================================================
;; Form Data Parsing
;; =============================================================================

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
  ([params entity-config {:keys [input-zone server-zone]}]
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

             ; Convert string value to appropriate type
           typed-value (cond
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
                         (or (ui-base/parse-datetime-input normalized-value input-zone server-zone)
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
       (assoc acc field-keyword typed-value)))
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

(defn require-admin-user!
  "Assert current user is an admin, throw if not.

   Args:
     request: Ring request map

   Returns:
     User entity map if admin

   Throws:
     ExceptionInfo with :type :forbidden if not admin"
  [request]
  (let [user (get-current-user request)]
    (shell-permissions/assert-can-access-admin! user)
    user))

(defn get-entity-name
  "Extract entity name from path parameters.

   Args:
     request: Ring request map

   Returns:
     Entity name as keyword"
  [request]
  (keyword (get-in request [:path-params :entity])))

(defn get-entity-id
  "Extract entity ID from path parameters.

   Args:
     request: Ring request map

   Returns:
     Entity ID as UUID"
  [request]
  (UUID/fromString (get-in request [:path-params :id])))

(defn html-response
  "Create HTML response with standard headers, resolving [:t ...] i18n markers.

   Args:
     request: Ring request map (used to extract :i18n/t translation function)
     html: Hiccup data structure or HTML string

   Returns:
     Ring response map"
  [request html]
  (let [fallback-t (fn
                     ([k] (name k))
                     ([k _params] (name k))
                     ([k _params _n] (name k)))
        t-fn (or (i18n-middleware/resolve-t-fn request)
                 (get request :i18n/t)
                 fallback-t)
        body-content (i18n/render html t-fn)]
    (-> (ring-response/response body-content)
        (ring-response/content-type "text/html; charset=utf-8"))))

(defn htmx-fragment-response
  "Create HTMX fragment response, resolving [:t ...] i18n markers.

   Args:
     request: Ring request map
     html: Hiccup data or HTML string

   Returns:
     Ring response map with HTMX headers"
  [request html]
  (-> (html-response request html)
      (ring-response/header "HX-Trigger" "entityListUpdated")))

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

(defn form-zone-options
  "The zones to parse a submitted form's :instant fields in, and `params`
   without the `__zone` field that carried them.

   The form says which zone it was rendered in, and parsing uses exactly that:
   deriving it from the cookie again would shift every value on the first
   visit, when the page was rendered before the cookie existed. A missing or
   unknown `__zone` falls back to the same resolution the page used."
  [config request params]
  (let [display (display-options config request)]
    [{:input-zone  (or (->zone (get params "__zone")) (:zone-id display))
      :server-zone (:server-zone-id display)}
     (dissoc params "__zone" :__zone)]))

;; =============================================================================
;; Entity Detail Options (shared by detail + crud handlers)
;; =============================================================================

(defn build-entity-detail-opts
  "Builds opts for entity-detail-page and the surrounding admin-layout.
   Shared between entity-detail-handler and update-entity-handler.

   Returns a map with:
     :entities       - all available entity names
     :entity-configs - map of entity-name -> entity-config
     :page-opts      - opts map for entity-detail-page
                       (related-records, return-to, parent-context, sibling-nav,
                        display)"
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
        related-records (when (seq has-many-rels)
                          (mapv (fn [rel]
                                  [(cond-> (assoc rel :entity-config (get entity-configs (:entity rel)))
                                     (:editable rel) (assoc :return-to parent-url))
                                   (ports/list-related-entities admin-service entity-name record-id rel)])
                                has-many-rels))

        ; Return URL when navigating back from a child entity (e.g. order-items → order)
        return-to   (get-in request [:query-params "return_to"])

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
                                                                        matching-rel)
                                current-id record-id
                                ids        (mapv #(str (:id %)) siblings)
                                idx        (.indexOf ^java.util.List ids current-id)
                                nav-url    #(str "/web/admin/" (name entity-name) "/" %
                                                 "?return_to=" return-to)]
                            (when (>= idx 0)
                              (cond-> {:position (inc idx) :total (count ids)}
                                (> idx 0)                (assoc :prev-url (nav-url (nth ids (dec idx))))
                                (< idx (dec (count ids))) (assoc :next-url (nav-url (nth ids (inc idx))))))))))]

    {:entities       entities
     :entity-configs entity-configs
     :page-opts      {:related-records related-records
                      :return-to       return-to
                      :parent-context  parent-context
                      :sibling-nav     sibling-nav
                      :display         (display-options config request)}}))
