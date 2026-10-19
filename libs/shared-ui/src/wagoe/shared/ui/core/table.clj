(ns wagoe.shared.ui.core.table
  "Shared table UI helpers (sorting, paging) for Hiccup-based web UIs."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Query-param helpers (pure, inlined to avoid core→shell dependency)
;; ---------------------------------------------------------------------------

(defn table-query->params
  "Convert a TableQuery map into a string-keyed param map for query strings."
  [{:keys [sort dir page page-size search]}]
  {"sort"      (when sort (name sort))
   "dir"       (when dir (name dir))
   "page"      (str page)
   "page-size" (str page-size)
   "search"    (when-not (str/blank? search) search)})

(defn- url-encode [s]
  (java.net.URLEncoder/encode (str s) "UTF-8"))

(defn- param-name [x]
  (if (keyword? x) (name x) (str x)))

(defn encode-query-params
  "Turn a string-keyed map into a URL-encoded query string. Nil values are
   omitted; a sequential value repeats its key."
  [m]
  (->> (for [[k v] m
             :when (some? v)
             v     (if (sequential? v) v [v])]
         (str (url-encode (param-name k)) "=" (url-encode (param-name v))))
       (str/join "&")))

(defn search-filters->params
  "Convert parsed search/filter map back into a string-keyed param map. A
   filter spec map goes back in the filter bar's `filters[f][op]` format,
   which is what the handlers parse."
  [filters]
  (into {}
        (mapcat (fn [[k v]]
                  (let [f (param-name k)
                        p #(str "filters[" f "][" % "]")]
                    (if (map? v)
                      (cond-> [[(p "op") (param-name (:op v :eq))]]
                        (contains? v :value)  (conj [(p "value") (str (:value v))])
                        (contains? v :min)    (conj [(p "min") (str (:min v))])
                        (contains? v :max)    (conj [(p "max") (str (:max v))])
                        (contains? v :values) (conj [(str "filters[" f "][values][]") (mapv str (:values v))]))
                      [[f (str v)]]))))
        filters))

(defn sortable-th
  "Reusable sortable table header cell.

   opts:
   - :label         string label to display
   - :field         keyword used as :sort in TableQuery
   - :current-sort  current :sort from TableQuery
   - :current-dir   current :dir from TableQuery
   - :base-url      base path (e.g. \"/web/users\")
   - :page          current page (integer)
   - :page-size     current page-size (integer)
   - :hx-target     HTMX target selector (string)
   - :hx-swap       default \"outerHTML\": the handler returns the target element
                    itself. Pass \"innerHTML\" if it returns only the contents.
   - :hx-push-url?  bool, default true
   - :push-url-base optional path used for browser URL updates (defaults to :base-url)
   - :extra-params  map of additional query params (keyword/string keys)

   Behaviour:
   - Clicking toggles :dir between :asc and :desc for the same :field.
   - When changing sort field or direction, page is reset to 1.
   "
  [{:keys [label field current-sort current-dir base-url push-url-base _page page-size
           hx-target hx-swap hx-push-url? extra-params]
    :or {hx-swap "outerHTML"}}]
  (let [active?  (= current-sort field)
        next-dir (if (and active? (= current-dir :asc)) :desc :asc)
        icon-symbol (cond
                      (not active?) nil
                      (= current-dir :asc) "↑"
                      :else "↓")
        page*    1
        base-q   (table-query->params
                  {:sort field :dir next-dir :page page* :page-size page-size})
        qs-map   (merge base-q (search-filters->params extra-params))
        query-str (encode-query-params qs-map)
        url      (str base-url "?" query-str)
        push-url (when hx-push-url?
                   (str (or push-url-base base-url) "?" query-str))]
    [:th
     {:hx-get     url
      :hx-target  hx-target
      :hx-swap    hx-swap
      :hx-push-url push-url
      :hx-params  "none"
      :class      (str "sortable-header"
                       (when active? " sortable-header--active")
                       (when active? (str " sort-dir-" (name current-dir))))
      :role       "button"
      :tabindex   "0"}
     [:span.sort-label label]
     (when icon-symbol
       [:span.sort-icon
        icon-symbol])]))

(defn- page-window
  "Compute which page numbers to show in the pagination bar.
   Returns a sequence of page numbers (longs) and :ellipsis keywords.

   Algorithm: always show first, last, and current ± siblings.
   Gaps larger than 1 become :ellipsis."
  [current total-pages siblings]
  (if (<= total-pages (+ 3 (* 2 siblings)))
    ;; Few enough pages to show all
    (range 1 (inc total-pages))
    ;; Build window around current page
    (let [lo (max 2 (- current siblings))
          hi (min (dec total-pages) (+ current siblings))]
      (concat [1]
              (cond
                (= lo 2) nil          ; no gap
                (= lo 3) [2]          ; gap of 1 — show the page directly
                :else     [:ellipsis]) ; gap > 1
              (range lo (inc hi))
              (cond
                (= hi (dec total-pages)) nil
                (= hi (- total-pages 2)) [(dec total-pages)]
                :else                    [:ellipsis])
              [total-pages]))))

(defn pagination
  "Render pagination controls for a table.

   Shows page numbers with ellipsis, first/last jump, prev/next arrows,
   and a 'Showing X-Y of Z' summary.

   opts:
   - :table-query   normalized TableQuery map
   - :total-count   total number of items
   - :base-url      base URL for hx-get links (e.g. \"/web/users/table\")
   - :hx-target     HTMX target selector
   - :hx-swap       default \"outerHTML\"; see `sortable-th`
   - :push-url-base optional path used for browser URL updates (defaults to :base-url)
   - :extra-params  map of additional query params (filters, etc.)

   Returns nil when a single page is sufficient."
  [{:keys [table-query total-count base-url push-url-base hx-target hx-swap extra-params]
    :or {hx-swap "outerHTML"}}]
  (let [{:keys [page page-size]} table-query
        total-count  (long (or total-count 0))
        page-size    (long (max 1 (or page-size 20)))
        page         (long (max 1 (or page 1)))
        total-pages  (max 1 (long (Math/ceil (/ (double total-count)
                                                (double page-size)))))
        page         (min page total-pages)
        from         (if (pos? total-count)
                       (inc (* (dec page) page-size))
                       0)
        to           (if (pos? total-count)
                       (min total-count (+ (* (dec page) page-size) page-size))
                       0)
        show-pages?  (> total-pages 1)
        extra-q      (search-filters->params extra-params)
        mk-url       (fn [page*]
                       (let [tq   (assoc table-query :page page*)
                             base (table-query->params tq)
                             qs   (merge base extra-q)]
                         (str base-url "?" (encode-query-params qs))))
        mk-push-url  (fn [page*]
                       (let [tq   (assoc table-query :page page*)
                             base (table-query->params tq)
                             qs   (merge base extra-q)]
                         (str (or push-url-base base-url) "?"
                              (encode-query-params qs))))
        prev-page    (max 1 (dec page))
        next-page    (min total-pages (inc page))
        page-btn     (fn [p]
                       (let [active? (= p page)]
                         [:button {:type       "button"
                                   :class      (str "page-btn" (when active? " active"))
                                   :hx-get     (when-not active? (mk-url p))
                                   :hx-target  (when-not active? hx-target)
                                   :hx-swap    (when-not active? hx-swap)
                                   :hx-push-url (when-not active? (mk-push-url p))
                                   :hx-params  "none"
                                   :disabled   active?
                                   :aria-label [:t :admin/pagination-page {:page p}]
                                   :aria-current (when active? "page")}
                          (str p)]))]
    (when show-pages?
      [:div.table-pagination
       [:div.page-info
        [:span.hide-mobile [:t :admin/pagination-showing] " "]
        [:strong (str from "–" to)]
        " " [:t :admin/pagination-of] " " (str total-count)]
       [:nav.pagination-nav {:aria-label [:t :admin/pagination-label]}
        ;; First page
        [:button.page-nav-btn {:type       "button"
                               :hx-get     (mk-url 1)
                               :hx-target  hx-target
                               :hx-swap    hx-swap
                               :hx-push-url (mk-push-url 1)
                               :hx-params  "none"
                               :disabled   (<= page 1)
                               :aria-label [:t :admin/pagination-first-page]
                               :title      [:t :admin/pagination-first-page]
                               ;; What the keyboard pages with (admin keyboard.js)
                               :data-page  "first"}
         "«"]
        ;; Previous page
        [:button.page-nav-btn {:type       "button"
                               :hx-get     (mk-url prev-page)
                               :hx-target  hx-target
                               :hx-swap    hx-swap
                               :hx-push-url (mk-push-url prev-page)
                               :hx-params  "none"
                               :disabled   (<= page 1)
                               :aria-label [:t :admin/pagination-previous-page]
                               :title      [:t :admin/pagination-previous-page]
                               :data-page  "prev"}
         "‹"]
        ;; Page number buttons with ellipsis
        [:div.page-numbers
         (for [item (page-window page total-pages 1)]
           (if (= item :ellipsis)
             [:span.page-ellipsis "…"]
             (page-btn item)))]
        ;; Next page
        [:button.page-nav-btn {:type       "button"
                               :hx-get     (mk-url next-page)
                               :hx-target  hx-target
                               :hx-swap    hx-swap
                               :hx-push-url (mk-push-url next-page)
                               :hx-params  "none"
                               :disabled   (>= page total-pages)
                               :aria-label [:t :admin/pagination-next-page]
                               :title      [:t :admin/pagination-next-page]
                               :data-page  "next"}
         "›"]
        ;; Last page
        [:button.page-nav-btn {:type       "button"
                               :hx-get     (mk-url total-pages)
                               :hx-target  hx-target
                               :hx-swap    hx-swap
                               :hx-push-url (mk-push-url total-pages)
                               :hx-params  "none"
                               :disabled   (>= page total-pages)
                               :aria-label [:t :admin/pagination-last-page]
                               :title      [:t :admin/pagination-last-page]
                               :data-page  "last"}
         "»"]]])))

;; ---------------------------------------------------------------------------
;; Data table: query params, filter inputs, sortable table, infinite scroll
;;
;; The controls request the list's own URL and push it, so the address bar
;; holds sort, filters and page. The handler answers an htmx request (the
;; HX-Request header) with the fragment and anything else with the page.
;; ---------------------------------------------------------------------------

(def ^:private filter-param-pattern
  #"^filters\[([^\]]+)\]\[([^\]]+)\](?:\[\])?$")

(defn parse-filter-params
  "The `filters[f][op|value|min|max|values][]` params of a query string as
   {:f {:op :eq :value \"x\"}}. Blank values are kept; see `parse-table-params`."
  [params]
  (reduce (fn [acc [k v]]
            (if-let [[_ field part] (re-matches filter-param-pattern (param-name k))]
              (let [field (keyword field)
                    vs    (if (sequential? v) v [v])]
                (case part
                  "op"     (assoc-in acc [field :op] (keyword (str (first vs))))
                  "values" (update-in acc [field :values] (fnil into []) vs)
                  ("value" "min" "max") (assoc-in acc [field (keyword part)] (first vs))
                  acc))
              acc))
          {}
          params))

(defn- blank-spec? [{:keys [value min max values]}]
  (and (str/blank? value) (str/blank? min) (str/blank? max)
       (not-any? (complement str/blank?) values)))

(defn- positive-long [s]
  (let [n (some-> s str parse-long)]
    (when (and n (pos? n)) n)))

(defn parse-table-params
  "The table state in a Ring query-params map: {:sort :dir :page :page-size
   :search :filters}, each key only when present and valid.

   opts:
   - :sortable       set of sortable field keywords; any other :sort is dropped
   - :max-page-size  upper bound for :page-size

   :dir is :asc or :desc (`sort-dir` is accepted too). Filters with no value
   are dropped: a filter form sends its untouched inputs blank."
  ([params] (parse-table-params params {}))
  ([params {:keys [sortable max-page-size]}]
   (let [params    (update-keys params param-name)
         sort      (let [s (get params "sort")] (when (and (string? s) (seq s)) (keyword s)))
         dir       (#{:asc :desc} (some-> (or (get params "dir") (get params "sort-dir")) str keyword))
         page      (positive-long (get params "page"))
         page-size (cond-> (positive-long (get params "page-size"))
                     max-page-size (some-> (min max-page-size)))
         search    (let [s (get params "search")] (when (and (string? s) (not (str/blank? s))) s))
         filters   (into {} (remove (comp blank-spec? val)) (parse-filter-params params))]
     (cond-> {}
       (and sort (or (nil? sortable) (contains? sortable sort))) (assoc :sort sort)
       dir             (assoc :dir dir)
       page            (assoc :page page)
       page-size       (assoc :page-size page-size)
       search          (assoc :search search)
       (seq filters)   (assoc :filters filters)))))

(defn list-url
  "`path` with the table state and filters as its query string, blank ones
   left out, so the default view is the bare path."
  [path table-query filters]
  (let [qs (encode-query-params
            (into {}
                  (remove (fn [[_ v]] (or (nil? v) (= "" v))))
                  (merge (table-query->params table-query)
                         (search-filters->params filters))))]
    (if (str/blank? qs) path (str path "?" qs))))

(defn filter-input
  "An input that re-queries the table as the user types (a select, with
   :options [[value label] ...], when it changes) and pushes the URL.

   opts:
   - :name, :value, :label (aria-label), :placeholder, :options
   - :url        the list URL to request, carrying the state to keep
   - :hx-target  the table container selector
   - :hx-swap    default \"outerHTML\"
   - :hx-include other inputs to send along, default \"this\"
   - :attrs      merged last, for classes or a different :type"
  [{:keys [name value label placeholder options url hx-target hx-swap hx-include attrs]
    :or   {hx-swap "outerHTML" hx-include "this"}}]
  (let [htmx {:name        name
              :aria-label  label
              :hx-get      url
              :hx-target   hx-target
              :hx-swap     hx-swap
              :hx-push-url "true"
              :hx-include  hx-include}]
    (if options
      [:select (merge htmx {:class "select w-full" :hx-trigger "change"} attrs)
       [:option {:value ""} (or placeholder "")]
       (for [[v l] options]
         [:option {:value v :selected (= (str v) (str value))} l])]
      [:input (merge htmx {:type        "search"
                           :class       "input w-full"
                           :value       (or value "")
                           :placeholder placeholder
                           :hx-trigger  "input changed delay:300ms, search"}
                     attrs)])))

(defn next-page-url
  "The URL of the page after `table-query`'s, or nil on the last page."
  [path table-query filters total-count]
  (let [{:keys [page page-size]} table-query
        page      (or page 1)
        page-size (or page-size 20)]
    (when (< (* page page-size) (or total-count 0))
      (list-url path (assoc table-query :page (inc page) :page-size page-size) filters))))

(defn infinite-rows
  "`rows` with the last one loading `next-url` once scrolled into view: the
   handler answers with the next page's rows, which go in after it, the last
   of them carrying the page after that. No `next-url`, no loading."
  [rows next-url]
  (let [rows (vec rows)]
    (if (and next-url (seq rows))
      (update rows (dec (count rows))
              (fn [[tag & more]]
                (let [[attrs children] (if (map? (first more)) [(first more) (rest more)] [{} more])]
                  (into [tag (merge attrs {:hx-get     next-url
                                           :hx-trigger "revealed"
                                           :hx-swap    "afterend"
                                           :hx-target  "this"})]
                        children))))
      rows)))

(defn- filter-cell
  [{:keys [field filter label]} {:keys [url table-query hx-target hx-swap filters]}]
  [:th {:class (str "filter-cell field-" (name field))}
   (when filter
     (filter-input {:name        (str "filters[" (name field) "][value]")
                    :value       (get-in filters [field :value])
                    :label       label
                    :placeholder (:placeholder filter)
                    :options     (:options filter)
                    ;; Page one, sorted as now; the filter values come from the row
                    :url         (list-url url (dissoc table-query :page) nil)
                    :hx-target   hx-target
                    :hx-swap     hx-swap
                    :hx-include  "closest tr"}))])

(defn data-table
  "A table with sortable headers, an optional row of filter inputs, and
   either pagination (placed by the caller, see `pagination`) or, with
   :infinite?, rows that load the next page as the last comes into view.

   opts:
   - :url          the list URL the controls request and push
   - :rows-url     what the last row requests with :infinite?; default :url
   - :hx-target    the container the handler re-renders, e.g. \"#orders\"
   - :hx-swap      default \"outerHTML\"
   - :columns      [{:field :label :class :sortable? (default true)
                     :filter {:placeholder :options}}]
   - :rows         rendered [:tr ...] rows
   - :table-query  {:sort :dir :page :page-size :search}
   - :filters      the parsed filters, kept by every control
   - :total-count  needed by :infinite?
   - :head-before, :head-after  seqs of extra [:th] cells, e.g. a select-all box
   - :colgroup, :tfoot, :class  passed through"
  [{:keys [url rows-url hx-target hx-swap columns rows table-query filters total-count
           infinite? head-before head-after colgroup tfoot class]
    :or   {hx-swap "outerHTML"}}]
  (let [{:keys [sort dir page page-size]} table-query
        head-before (keep identity head-before)
        head-after  (keep identity head-after)
        ctx  {:url url :table-query table-query :hx-target hx-target :hx-swap hx-swap
              :filters filters}
        rows (cond-> rows
               infinite? (infinite-rows (next-page-url (or rows-url url) table-query filters total-count)))]
    [:table.data-table {:class (str "table" (when class (str " " class)))}
     colgroup
     [:thead
      [:tr
       head-before
       (for [{:keys [field label sortable?] col-class :class :or {sortable? true}} columns]
         (if sortable?
           (update (sortable-th {:label label :field field :current-sort sort :current-dir dir
                                 :base-url url :page page :page-size page-size
                                 :hx-target hx-target :hx-swap hx-swap :hx-push-url? true
                                 :extra-params (cond-> (or filters {})
                                                 (:search table-query) (assoc :search (:search table-query)))})
                   1 update :class str " field-" (name field) (when col-class (str " " col-class)))
           [:th {:class (str "field-" (name field) (when col-class (str " " col-class)))} label]))
       head-after]
      (when (some :filter columns)
        [:tr {:class "filter-row"}
         (for [_ head-before] [:th])
         (for [column columns] (filter-cell column ctx))
         (for [_ head-after] [:th])])]
     [:tbody rows]
     tfoot]))
