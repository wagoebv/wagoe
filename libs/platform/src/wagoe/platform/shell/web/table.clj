(ns wagoe.platform.shell.web.table
  "Shared helpers for table sorting, pagination, and basic search filters across web UIs.

   This namespace centralizes parsing of table-related query parameters so that
   all modules use the same semantics for sorting and paging. It also provides
   small helpers for generic search/filter parameters that feature modules can
   interpret according to their own needs.

   TableQuery shape:
   {:sort      :name        ; keyword column identifier (or default)
    :dir       :asc         ; :asc or :desc
    :page      1            ; 1-based page index
    :page-size 20           ; items per page
    :offset    0            ; derived from page/page-size
    :limit     20}         ; derived from page-size
   "
  (:require [clojure.string :as str]))

(defn- get-param
  "Read a query param from map supporting both string and keyword keys."
  [query-params key-name]
  (or (get query-params key-name)
      (get query-params (keyword key-name))))

(defn- parse-long-safe [^String s]
  (when (and s (not (str/blank? s)))
    (try
      (Long/parseLong s)
      (catch Exception _
        nil))))

(defn parse-search-filters
   "Parse search and filter parameters from query params.
    
    Extracts 'q' (search), 'role', and 'status' from query parameters
    and filters out blank/empty values.
    
    Args:
      query-params: String-keyed map of query parameters from Ring
      
    Returns:
      Map with :q, :role, :status keys (only non-blank values included)"
   [query-params]
   (let [q      (get-param query-params "q")
         role   (get-param query-params "role")
         status (get-param query-params "status")]
     (cond-> {}
       (some-> q str/blank? not)       (assoc :q q)
       (some-> role str/blank? not)    (assoc :role role)
       (some-> status str/blank? not)  (assoc :status status))))

(defn search-filters->params
  "Convert parsed search/filter map back into a string-keyed param map.

   Useful for building combined query strings with table-query->params."
  [filters]
  (into {}
        (for [[k v] filters]
          [(name k) (str v)])))

(defn parse-table-query
  "Parse table-related query params into a normalized TableQuery map.

   query-params: raw string-keyed query params from Ring (:query-params).

   options: {:default-sort       ; required keyword for default sort column
             :default-dir        ; :asc or :desc
             :default-page-size} ; integer page size when not provided

   Returns a map with keys :sort, :dir, :page, :page-size, :offset, :limit.
   "
  [query-params {:keys [default-sort default-dir default-page-size]}]
  (let [sort-str  (get-param query-params "sort")
        dir-str   (get-param query-params "dir")
        page-str  (get-param query-params "page")
        size-str  (get-param query-params "page-size")
        sort-k    (some-> sort-str keyword)
        dir-k     (case (some-> dir-str keyword)
                    :desc :desc
                    :asc  :asc
                    nil)
        page-raw  (or (parse-long-safe page-str) 1)
        page      (max 1 page-raw)
        size-raw  (or (parse-long-safe size-str) default-page-size 20)
        size      (-> size-raw (max 1) (min 100))
        sort      (or sort-k default-sort)
        dir       (or dir-k default-dir :asc)
        offset    (* (dec page) size)]
    {:sort      sort
     :dir       dir
     :page      page
     :page-size size
     :offset    offset
     :limit     size}))

(defn table-query->params
  "Convert a TableQuery map into a simple string-keyed param map suitable
   for building query strings.
   "
  [{:keys [sort dir page page-size]}]
  {"sort"      (when sort (name sort))
   "dir"       (when dir (name dir))
   "page"      (str page)
   "page-size" (str page-size)})

(defn encode-query-params
  "Very small helper to turn a string-keyed map into a query string.
   Values are stringified with str; caller is responsible for URL encoding
   if needed (current usages only contain safe characters).
   "
  [m]
  (->> m
       (remove (comp nil? val))
       (map (fn [[k v]] (str k "=" v)))
       (str/join "&")))
