(ns wagoe.platform.shell.pagination.link-headers
  "Shell layer RFC 5988 Link header generation for pagination.
   
   RFC 5988: Web Linking
   https://datatracker.ietf.org/doc/html/rfc5988
   
   SIDE EFFECTS:
   - String building and URL encoding
   - Logging
   
   Link headers provide hypermedia controls for pagination navigation.
   They allow clients to discover next, prev, first, and last pages without
   parsing response bodies.
   
   Format:
   Link: </api/v1/users?limit=20&offset=20>; rel=\"next\",
         </api/v1/users?limit=20&offset=0>; rel=\"first\",
         </api/v1/users?limit=20&offset=980>; rel=\"last\"
   
   Supported Relations:
   - first: First page of results
   - last: Last page of results (offset pagination only)
   - prev: Previous page
   - next: Next page
   - self: Current page"
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log])
  (:import
   [java.net URLEncoder]))

;; =============================================================================
;; URL Building
;; =============================================================================

(defn- url-encode
  "URL-encode a string value.
   
   Args:
     value - String to encode
     
   Returns:
     URL-encoded string
     
   Side Effects:
     URL encoding"
  [value]
  (URLEncoder/encode (str value) "UTF-8"))

(defn build-query-string
  "Build URL query string from parameters map.
   
   Filters out nil values and URL-encodes parameter values.
   
   Args:
     params - Map of query parameters
     
   Returns:
     Query string (without leading '?')
     
   Side Effects:
     - URL encoding
     - String building
     
   Example:
     (build-query-string {:limit 20 :offset 40 :sort \"name\"})
     ;;=> \"limit=20&offset=40&sort=name\"
     
     (build-query-string {:limit 20 :offset nil})
     ;;=> \"limit=20\""
  [params]
  (->> params
       (filter (fn [[_k v]] (some? v)))  ; Remove nil values
       (map (fn [[k v]] (str (name k) "=" (url-encode v))))
       (str/join "&")))

(defn build-url
  "Build full URL from base path and query parameters.
   
   Args:
     base-path - Base URL path (e.g., \"/api/v1/users\")
     params - Map of query parameters
     
   Returns:
     Full URL with query string
     
   Side Effects:
     - URL encoding (via build-query-string)
     - String building
     
   Example:
     (build-url \"/api/v1/users\" {:limit 20 :offset 40})
     ;;=> \"/api/v1/users?limit=20&offset=40\"
     
     (build-url \"/api/v1/users\" {})
     ;;=> \"/api/v1/users\""
  [base-path params]
  (let [query (build-query-string params)]
    (if (str/blank? query)
      base-path
      (str base-path "?" query))))

;; =============================================================================
;; Link Header Building
;; =============================================================================

(defn- build-link-entry
  "Build a single Link header entry.
   
   Args:
     url - Full URL for the link
     rel - Relation type (:first, :last, :prev, :next, :self)
     
   Returns:
     Link header entry string
     
   Example:
     (build-link-entry \"/api/v1/users?offset=20&limit=20\" :next)
     ;;=> \"</api/v1/users?offset=20&limit=20>; rel=\\\"next\\\"\""
  [url rel]
  (str "<" url ">; rel=\"" (name rel) "\""))

(defn build-link-header
  "Build RFC 5988 Link header string from link data.
   
   Takes a collection of link entries and produces a single Link header value
   with multiple relations.
   
   Args:
     links - Collection of maps with :url and :rel keys
       :url - Full URL for the link
       :rel - Relation type (:first, :last, :prev, :next, :self)
       
   Returns:
     RFC 5988 Link header string
     
   Side Effects:
     - String building
     - Logging
     
   Example:
     (build-link-header
       [{:url \"/api/v1/users?offset=0&limit=20\" :rel :first}
        {:url \"/api/v1/users?offset=20&limit=20\" :rel :next}
        {:url \"/api/v1/users?offset=980&limit=20\" :rel :last}])
     ;;=> \"</api/v1/users?offset=0&limit=20>; rel=\\\"first\\\", </api/v1/users?offset=20&limit=20>; rel=\\\"next\\\", </api/v1/users?offset=980&limit=20>; rel=\\\"last\\\"\"
     
   Returns:
     nil if links collection is empty"
  [links]
  (when (seq links)
    (log/debug "Building Link header" {:link-count (count links)})
    (let [header (->> links
                      (map (fn [{:keys [url rel]}]
                             (build-link-entry url rel)))
                      (str/join ", "))]
      (log/debug "Link header built" {:header-length (count header)})
      header)))

;; =============================================================================
;; Link Data Generation (Offset Pagination)
;; =============================================================================

(defn build-offset-links
  "Build link data for offset-based pagination.
   
   Generates URLs for first, last, prev, next, and self relations based on
   offset pagination metadata.
   
   Args:
     base-path - Base URL path (e.g., \"/api/v1/users\")
     params - Current query parameters (map)
     pagination-meta - Offset pagination metadata with keys:
       :total - Total number of items
       :offset - Current offset
       :limit - Items per page
       :has-next - Boolean indicating if next page exists
       :has-prev - Boolean indicating if previous page exists
       :next-offset - Offset for next page (if has-next)
       :prev-offset - Offset for previous page (if has-prev)
       
   Returns:
     Vector of link data maps [{:url ... :rel ...} ...]
     
   Side Effects:
     - URL building
     - Logging
     
   Example:
     (build-offset-links
       \"/api/v1/users\"
       {:limit 20 :offset 40 :sort \"name\"}
       {:total 100
        :offset 40
        :limit 20
        :has-next true
        :has-prev true
        :next-offset 60
        :prev-offset 20
        :total-pages 5
        :current-page 3})
     ;;=> [{:url \"/api/v1/users?limit=20&offset=0&sort=name\" :rel :first}
     ;;    {:url \"/api/v1/users?limit=20&offset=20&sort=name\" :rel :prev}
     ;;    {:url \"/api/v1/users?limit=20&offset=40&sort=name\" :rel :self}
     ;;    {:url \"/api/v1/users?limit=20&offset=60&sort=name\" :rel :next}
     ;;    {:url \"/api/v1/users?limit=20&offset=80&sort=name\" :rel :last}]"
  [base-path params pagination-meta]
  (let [{:keys [total offset limit has-next has-prev next-offset prev-offset]} pagination-meta
        base-params (dissoc params :offset :cursor)  ; Remove pagination params
        last-offset (- total (rem total limit))
        last-offset (if (= last-offset total) (- total limit) last-offset)
        last-offset (max 0 last-offset)]

    (log/debug "Building offset pagination links"
               {:base-path base-path
                :offset offset
                :limit limit
                :has-next has-next
                :has-prev has-prev})

    (cond-> []
      ;; First page
      true
      (conj {:url (build-url base-path (assoc base-params :limit limit :offset 0))
             :rel :first})

      ;; Previous page
      has-prev
      (conj {:url (build-url base-path (assoc base-params :limit limit :offset prev-offset))
             :rel :prev})

      ;; Self (current page)
      true
      (conj {:url (build-url base-path (assoc base-params :limit limit :offset offset))
             :rel :self})

      ;; Next page
      has-next
      (conj {:url (build-url base-path (assoc base-params :limit limit :offset next-offset))
             :rel :next})

      ;; Last page
      true
      (conj {:url (build-url base-path (assoc base-params :limit limit :offset last-offset))
             :rel :last}))))

;; =============================================================================
;; Link Data Generation (Cursor Pagination)
;; =============================================================================

(defn build-cursor-links
  "Build link data for cursor-based pagination.
   
   Generates URLs for prev, next, and self relations based on cursor pagination
   metadata. Note: first and last are not available with cursor pagination
   (would require scanning entire dataset).
   
   Args:
     base-path - Base URL path (e.g., \"/api/v1/users\")
     params - Current query parameters (map)
     pagination-meta - Cursor pagination metadata with keys:
       :limit - Items per page
       :has-next - Boolean indicating if next page exists
       :has-prev - Boolean indicating if previous page exists
       :next-cursor - Cursor for next page (if has-next)
       :prev-cursor - Cursor for previous page (if has-prev)
       
   Returns:
     Vector of link data maps [{:url ... :rel ...} ...]
     
   Side Effects:
     - URL building
     - Logging
     
   Example:
     (build-cursor-links
       \"/api/v1/users\"
       {:limit 20 :cursor \"eyJ...\" :sort \"name\"}
       {:limit 20
        :has-next true
        :has-prev true
        :next-cursor \"eyJpZCI6IjEyMyI...\"
        :prev-cursor \"eyJpZCI6IjQ1NiI...\"})
     ;;=> [{:url \"/api/v1/users?limit=20&cursor=eyJpZCI6IjQ1NiI...&sort=name\" :rel :prev}
     ;;    {:url \"/api/v1/users?limit=20&cursor=eyJ...&sort=name\" :rel :self}
     ;;    {:url \"/api/v1/users?limit=20&cursor=eyJpZCI6IjEyMyI...&sort=name\" :rel :next}]"
  [base-path params pagination-meta]
  (let [{:keys [limit has-next has-prev next-cursor prev-cursor]} pagination-meta
        current-cursor (:cursor params)
        base-params (dissoc params :offset :cursor)]

    (log/debug "Building cursor pagination links"
               {:base-path base-path
                :limit limit
                :has-next has-next
                :has-prev has-prev
                :has-cursors {:next (some? next-cursor)
                              :prev (some? prev-cursor)}})

    (cond-> []
      ;; Previous page
      (and has-prev prev-cursor)
      (conj {:url (build-url base-path (assoc base-params :limit limit :cursor prev-cursor))
             :rel :prev})

      ;; Self (current page)
      current-cursor
      (conj {:url (build-url base-path (assoc base-params :limit limit :cursor current-cursor))
             :rel :self})

      ;; Next page
      (and has-next next-cursor)
      (conj {:url (build-url base-path (assoc base-params :limit limit :cursor next-cursor))
             :rel :next}))))

;; =============================================================================
;; High-Level API
;; =============================================================================

(defn generate-link-header
  "Generate RFC 5988 Link header for pagination response.
   
   Automatically detects pagination type (offset or cursor) and generates
   appropriate links.
   
   Args:
     base-path - Base URL path (e.g., \"/api/v1/users\")
     params - Current query parameters (map)
     pagination-meta - Pagination metadata (either offset or cursor type)
       Must have :type key with value \"offset\" or \"cursor\"
       
   Returns:
     RFC 5988 Link header string, or nil if links are empty
     
   Side Effects:
     - Link building (URL construction, string building)
     - Logging
     
   Example (offset):
     (generate-link-header
       \"/api/v1/users\"
       {:limit 20 :offset 40}
       {:type \"offset\"
        :total 100
        :offset 40
        :limit 20
        :has-next true
        :has-prev true
        :next-offset 60
        :prev-offset 20})
     ;;=> \"</api/v1/users?limit=20&offset=0>; rel=\\\"first\\\", ...\"
     
   Example (cursor):
     (generate-link-header
       \"/api/v1/users\"
       {:limit 20 :cursor \"eyJ...\"}
       {:type \"cursor\"
        :limit 20
        :has-next true
        :next-cursor \"eyJpZCI...\"})
     ;;=> \"</api/v1/users?limit=20&cursor=eyJpZCI...>; rel=\\\"next\\\", ...\""
  [base-path params pagination-meta]
  (log/debug "Generating Link header"
             {:base-path base-path
              :pagination-type (:type pagination-meta)})

  (let [links (case (:type pagination-meta)
                "offset" (build-offset-links base-path params pagination-meta)
                "cursor" (build-cursor-links base-path params pagination-meta)
                (do
                  (log/warn "Unknown pagination type, skipping Link header"
                            {:type (:type pagination-meta)})
                  []))]
    (build-link-header links)))

(comment
  "Example usage:
   
   ;; Offset pagination
   (def offset-meta
     {:type \"offset\"
      :total 100
      :offset 40
      :limit 20
      :has-next true
      :has-prev true
      :next-offset 60
      :prev-offset 20
      :total-pages 5
      :current-page 3})
   
   (generate-link-header
     \"/api/v1/users\"
     {:limit 20 :offset 40 :sort \"name\"}
     offset-meta)
   ;;=> \"</api/v1/users?limit=20&offset=0&sort=name>; rel=\\\"first\\\", ...\"
   
   ;; Cursor pagination
   (def cursor-meta
     {:type \"cursor\"
      :limit 20
      :has-next true
      :has-prev true
      :next-cursor \"eyJpZCI6IjEyMyI...\"
      :prev-cursor \"eyJpZCI6IjQ1NiI...\"})
   
   (generate-link-header
     \"/api/v1/users\"
     {:limit 20 :cursor \"current-cursor\" :sort \"name\"}
     cursor-meta)
   ;;=> \"</api/v1/users?limit=20&cursor=eyJpZCI6IjQ1NiI...&sort=name>; rel=\\\"prev\\\", ...\"
   
   ;; Use in Ring response
   (defn paginated-response [data pagination-meta request]
     {:status 200
      :headers {\"Content-Type\" \"application/json\"
                \"Link\" (generate-link-header
                          (:uri request)
                          (:query-params request)
                          pagination-meta)}
      :body {:data data
             :pagination pagination-meta}})")
