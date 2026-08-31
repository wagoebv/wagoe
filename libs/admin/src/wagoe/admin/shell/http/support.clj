(ns wagoe.admin.shell.http.support
  "Shared plumbing for the admin HTTP layer.

   Leaf namespace requiring no handler or route namespaces. Provides the
   error mappings, query/form parsing, and handler helpers used by the handler
   namespaces and the route definitions in `wagoe.admin.shell.http`."
  (:require
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
  [params entity-config]
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

                           ; Integer values - wrap in try/catch for invalid input
                         (= field-type :int)
                         (try
                           (parse-long normalized-value)
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

       (if (or typed-value (= field-type :boolean))
         (assoc acc field-keyword typed-value)
         acc)))
   {}
   params))

;; =============================================================================
;; Handler Helpers
;; =============================================================================

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
;; Entity Detail Options (shared by detail + crud handlers)
;; =============================================================================

(defn build-entity-detail-opts
  "Builds opts for entity-detail-page and the surrounding admin-layout.
   Shared between entity-detail-handler and update-entity-handler.

   Returns a map with:
     :entities       - all available entity names
     :entity-configs - map of entity-name -> entity-config
     :page-opts      - opts map for entity-detail-page
                       (related-records, return-to, parent-context, sibling-nav)"
  [admin-service schema-provider entity-name entity-config record request]
  (let [entities        (ports/list-available-entities schema-provider)
        entity-configs  (into {} (map (fn [e] [e (ports/get-entity-config schema-provider e)])) entities)

        ; Related records for has-many relationships on this entity
        primary-key     (:primary-key entity-config :id)
        record-id       (str (get record primary-key))
        has-many-rels   (get entity-config :has-many [])
        parent-url      (str "/web/admin/" (name entity-name) "/" record-id)
        related-records (when (seq has-many-rels)
                          (mapv (fn [rel]
                                  [(cond-> rel (:editable rel) (assoc :return-to parent-url))
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
                      :sibling-nav     sibling-nav}}))

;; =============================================================================
;; Display Options
;; =============================================================================

(defn- valid-pattern
  "The pattern, or nil with a warning when `DateTimeFormatter` rejects it.
   Dropping it here means one bad character in `config.edn` costs a column its
   formatting rather than the whole page — the renderer falls back to its
   default pattern."
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

(defn display-options
  "Zone, locale and date patterns for rendering stored timestamps in the admin UI.

   The clock's zone is read here because `wagoe.admin.core.ui.base` may not:
   check:fcis bans `ZoneId/systemDefault` in a core namespace. The patterns
   come from the application's `:wagoe/settings`, which the module wiring
   threads into the admin config — before BOU-382 nothing read them and every
   timestamp rendered as the raw database value."
  [config request]
  {:zone-id          (java.time.ZoneId/systemDefault)
   :locale           (request-locale request)
   :date-time-format (valid-pattern (:date-time-format config))
   :date-format      (valid-pattern (:date-format config))})
