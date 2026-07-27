(ns wagoe.platform.core.pagination.pagination
  "Pure functions for pagination logic.
   
   This namespace provides pure functional implementations for pagination calculations,
   following the Functional Core pattern. All functions are deterministic and side-effect free.
   
   Supports:
   - Offset-based pagination (simple, familiar)
   - Cursor-based pagination (high performance, stable results)
   - Parameter validation
   - Metadata calculation
   
   Pure: All functions return data, no side effects."
  (:require [clojure.string :as str]))

;; =============================================================================
;; Offset Pagination
;; =============================================================================

(defn calculate-offset-pagination
  "Calculate offset pagination metadata.
   
   Args:
     total - Total number of items in collection
     offset - Starting position (0-based)
     limit - Number of items per page
     
   Returns:
     {:type      \"offset\"
      :total     int - Total items
      :offset    int - Current offset
      :limit     int - Items per page
      :has-next? bool - More items available
      :has-prev? bool - Previous page available
      :page      int - Current page (1-based)
      :pages     int - Total pages}
      
   Examples:
     (calculate-offset-pagination 100 0 20)
     => {:type \"offset\" :total 100 :offset 0 :limit 20 
         :has-next? true :has-prev? false :page 1 :pages 5}
         
     (calculate-offset-pagination 100 80 20)
     => {:type \"offset\" :total 100 :offset 80 :limit 20
         :has-next? false :has-prev? true :page 5 :pages 5}
   
   Pure: true"
  [total offset limit]
  (let [page       (inc (quot offset limit))
        pages      (if (zero? total)
                     0
                     (int (Math/ceil (/ total (max limit 1)))))
        has-next?  (< (+ offset limit) total)
        has-prev?  (> offset 0)]
    {:type      "offset"
     :total     total
     :offset    offset
     :limit     limit
     :has-next? has-next?
     :has-prev? has-prev?
     :page      page
     :pages     pages}))

(defn calculate-next-offset
  "Calculate next page offset.
   
   Args:
     current-offset - Current offset
     limit - Items per page
     total - Total items (optional, for bounds checking)
     
   Returns:
     Next offset, or nil if at end
     
   Pure: true"
  [current-offset limit total]
  (let [next-offset (+ current-offset limit)]
    (when (< next-offset total)
      next-offset)))

(defn calculate-prev-offset
  "Calculate previous page offset.
   
   Args:
     current-offset - Current offset
     limit - Items per page
     
   Returns:
     Previous offset, or nil if at beginning
     
   Pure: true"
  [current-offset limit]
  (when (pos? current-offset)
    (max 0 (- current-offset limit))))

;; =============================================================================
;; Cursor Pagination
;; =============================================================================

(defn calculate-cursor-pagination
  "Calculate cursor pagination metadata.
   
   Args:
     items - Current page items
     limit - Items per page
     next-cursor - Cursor for next page (optional)
     prev-cursor - Cursor for previous page (optional)
     
   Returns:
     {:type        \"cursor\"
      :limit       int
      :next-cursor string or nil
      :prev-cursor string or nil
      :has-next?   bool
      :has-prev?   bool}
      
   Pure: true"
  [_items limit next-cursor prev-cursor]
  {:type        "cursor"
   :limit       limit
   :next-cursor next-cursor
   :prev-cursor prev-cursor
   :has-next?   (some? next-cursor)
   :has-prev?   (some? prev-cursor)})

(defn extract-cursor-value
  "Extract cursor value from item based on sort key.
   
   Args:
     item - Item to extract cursor from
     sort-key - Keyword for sort field (e.g., :created-at, :id)
     
   Returns:
     {:id value :sort-value value}
     
   Examples:
     (extract-cursor-value {:id 123 :created-at \"2024-01-01\"} :created-at)
     => {:id 123 :sort-value \"2024-01-01\"}
     
   Pure: true"
  [item sort-key]
  {:id         (get item :id)
   :sort-value (get item sort-key)})

;; =============================================================================
;; Parameter Validation
;; =============================================================================

(defn validate-pagination-params
  "Validate pagination parameters.
   
   Args:
     params - Map with :limit, :offset, :cursor
     config - Configuration with :default-limit, :max-limit
     
   Returns:
     {:valid? bool
      :errors map - Field name to error messages
      :params map - Normalized parameters}
      
   Examples:
     (validate-pagination-params {:limit 50} {:default-limit 20 :max-limit 100})
     => {:valid? true :errors {} :params {:limit 50 :offset 0}}
     
     (validate-pagination-params {:limit 200} {:max-limit 100})
     => {:valid? false :errors {:limit \"Must be at most 100\"} ...}
   
   Pure: true"
  [params config]
  (let [default-limit (get config :default-limit 20)
        max-limit     (get config :max-limit 100)
        limit         (or (:limit params) default-limit)
        offset        (or (:offset params) 0)
        cursor        (:cursor params)

        errors (cond-> {}
                 (not (int? limit))
                 (assoc :limit "Must be an integer")

                 (and (int? limit) (< limit 1))
                 (assoc :limit "Must be at least 1")

                 (and (int? limit) (> limit max-limit))
                 (assoc :limit (str "Must be at most " max-limit))

                 (not (int? offset))
                 (assoc :offset "Must be an integer")

                 (and (int? offset) (< offset 0))
                 (assoc :offset "Must be non-negative")

                 (and cursor offset (> offset 0))
                 (assoc :cursor "Cannot use cursor and offset together"))]

    {:valid? (empty? errors)
     :errors errors
     :params {:limit  limit
              :offset offset
              :cursor cursor}}))

(defn parse-limit
  "Parse limit parameter from string or integer.
   
   Args:
     limit-value - String, integer, or nil
     default-limit - Default if not provided
     
   Returns:
     Integer limit value, or default
     
   Pure: true"
  [limit-value default-limit]
  (cond
    (nil? limit-value)    default-limit
    (int? limit-value)    limit-value
    (string? limit-value) (try
                            (Integer/parseInt (str/trim limit-value))
                            (catch Exception _
                              default-limit))
    :else                 default-limit))

(defn parse-offset
  "Parse offset parameter from string or integer.
   
   Args:
     offset-value - String, integer, or nil
     
   Returns:
     Integer offset value, or 0
     
   Pure: true"
  [offset-value]
  (cond
    (nil? offset-value)    0
    (int? offset-value)    offset-value
    (string? offset-value) (try
                             (Integer/parseInt (str/trim offset-value))
                             (catch Exception _
                               0))
    :else                  0))

;; =============================================================================
;; Sort Parameters
;; =============================================================================

(defn parse-sort
  "Parse sort parameter into field and direction.
   
   Args:
     sort-value - String like \"created_at\" or \"-created_at\" (desc)
     
   Returns:
     {:field keyword :direction :asc or :desc}
     
   Examples:
     (parse-sort \"created_at\")   => {:field :created-at :direction :asc}
     (parse-sort \"-created_at\")  => {:field :created-at :direction :desc}
     (parse-sort nil)             => {:field :created-at :direction :desc}
     
   Pure: true"
  [sort-value]
  (if (nil? sort-value)
    {:field :created-at :direction :desc}  ; Default
    (let [descending? (str/starts-with? sort-value "-")
          field-str   (if descending?
                        (subs sort-value 1)
                        sort-value)
          field       (keyword (str/replace field-str #"_" "-"))]
      {:field     field
       :direction (if descending? :desc :asc)})))

(defn validate-sort-field
  "Validate sort field against allowed fields.
   
   Args:
     sort-field - Keyword field name
     allowed-fields - Set of allowed keywords
     
   Returns:
     {:valid? bool :error string or nil}
     
   Pure: true"
  [sort-field allowed-fields]
  (if (contains? allowed-fields sort-field)
    {:valid? true :error nil}
    {:valid? false
     :error  (str "Invalid sort field. Allowed: " (str/join ", " (map name allowed-fields)))}))

;; =============================================================================
;; Link URL Construction (Data Preparation)
;; =============================================================================

(defn prepare-link-data
  "Prepare data for link URL construction.
   
   This is a pure function that prepares the data structure needed for
   link generation. The actual URL construction happens in the shell layer.
   
   Args:
     base-path - Base path (e.g., \"/api/v1/users\")
     pagination - Pagination metadata
     query-params - Additional query parameters
     
   Returns:
     {:first {:path string :params map}
      :last  {:path string :params map} or nil
      :next  {:path string :params map} or nil
      :prev  {:path string :params map} or nil}
      
   Pure: true"
  [base-path pagination query-params]
  (let [type (:type pagination)]
    (case type
      "offset"
      (let [total       (:total pagination)
            limit       (:limit pagination)
            has-next?   (:has-next? pagination)
            has-prev?   (:has-prev? pagination)
            last-offset (max 0 (- total limit))]
        (cond-> {:first {:path base-path
                         :params (merge query-params {:limit limit :offset 0})}}

          ;; Last page (only if we have total count)
          (some? total)
          (assoc :last {:path base-path
                        :params (merge query-params {:limit limit :offset last-offset})})

          ;; Next page
          has-next?
          (assoc :next {:path base-path
                        :params (merge query-params
                                       {:limit limit
                                        :offset (+ (:offset pagination) limit)})})

          ;; Previous page
          has-prev?
          (assoc :prev {:path base-path
                        :params (merge query-params
                                       {:limit limit
                                        :offset (max 0 (- (:offset pagination) limit))})})))

      "cursor"
      (let [next-cursor (:next-cursor pagination)
            prev-cursor (:prev-cursor pagination)
            limit       (:limit pagination)]
        (cond-> {}
          ;; Next page
          next-cursor
          (assoc :next {:path base-path
                        :params (merge query-params {:limit limit :cursor next-cursor})})

          ;; Previous page
          prev-cursor
          (assoc :prev {:path base-path
                        :params (merge query-params {:limit limit :cursor prev-cursor})})))

      ;; Unknown type
      {})))

;; =============================================================================
;; Response Envelope Construction
;; =============================================================================

(defn create-paginated-response
  "Create standardized paginated response envelope.
   
   Args:
     data - Collection of items
     pagination - Pagination metadata
     meta - Optional additional metadata
     
   Returns:
     {:data       collection
      :pagination metadata
      :meta       metadata}
      
   Pure: true"
  [data pagination meta]
  (cond-> {:data       data
           :pagination pagination}
    (seq meta) (assoc :meta meta)))

(defn add-api-metadata
  "Add API metadata to response.
   
   Args:
     version - API version (e.g., :v1)
     timestamp - ISO 8601 timestamp string
     
   Returns:
     Metadata map
     
   Pure: true"
  [version timestamp]
  {:version   (name version)
   :timestamp timestamp})
