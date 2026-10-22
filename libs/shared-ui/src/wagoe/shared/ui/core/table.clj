(ns wagoe.shared.ui.core.table
  "Shared table UI helpers (sorting, paging) for Hiccup-based web UIs."
  (:require [wagoe.core.utils.type-conversion :as tc]
            [wagoe.core.utils.text :as text]
            [wagoe.shared.ui.core.htmx :as htmx]
            [wagoe.shared.ui.core.icons :as icons]
            [wagoe.shared.ui.core.modal :as modal]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Query-param helpers (pure, inlined to avoid core→shell dependency)
;; ---------------------------------------------------------------------------

(defn table-query->params
  "Convert a TableQuery map into a string-keyed param map for query strings."
  [{:keys [sort dir page page-size search]}]
  {"sort"      (when sort (name sort))
   "dir"       (when dir (name dir))
   "page"      (some-> page str)
   "page-size" (some-> page-size str)
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

(defn- control-id
  "An id for a table control, unique per table: htmx refocuses the element
   with the focused one's id after a swap. Nil unless `hx-target` is an id."
  [hx-target & parts]
  (when (and (string? hx-target) (str/starts-with? hx-target "#"))
    (str/join "-" (cons (subs hx-target 1) parts))))

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
        ;; "false", not absent: htmx inherits hx-push-url from an ancestor
        push-url (if hx-push-url?
                   (str (or push-url-base base-url) "?" query-str)
                   "false")]
    [:th
     {:class     (str "sortable-header"
                      (when active? " sortable-header--active")
                      (when active? (str " sort-dir-" (name current-dir))))
      :aria-sort (when active? (if (= current-dir :asc) "ascending" "descending"))}
     ;; A button: the th keeps its columnheader role and the keyboard sorts
     [:button
      {:type        "button"
       :class       "sort-button"
       :id          (control-id hx-target "sort" (name field))
       :hx-get      url
       :hx-target   hx-target
       :hx-swap     hx-swap
       :hx-push-url push-url
       :hx-params   "none"}
      [:span.sort-label label]
      (when icon-symbol
        [:span.sort-icon {:aria-hidden "true"} icon-symbol])]]))

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
   - :base-url      base URL for hx-get links (e.g. \"/web/users\")
   - :hx-target     HTMX target selector
   - :hx-swap       default \"outerHTML\"; see `sortable-th`
   - :push-url-base optional path used for browser URL updates (defaults to :base-url)
   - :push-url?     default true; false leaves the address bar alone
   - :extra-params  map of additional query params (filters, etc.)

   Returns nil when a single page is sufficient."
  [{:keys [table-query total-count base-url push-url-base push-url? hx-target hx-swap extra-params]
    :or {hx-swap "outerHTML" push-url? true}}]
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
                       (if push-url?
                         (let [tq   (assoc table-query :page page*)
                               base (table-query->params tq)
                               qs   (merge base extra-q)]
                           (str (or push-url-base base-url) "?"
                                (encode-query-params qs)))
                         "false"))
        prev-page    (max 1 (dec page))
        next-page    (min total-pages (inc page))
        page-btn     (fn [p]
                       (let [active? (= p page)]
                         ;; The current page stays focusable (aria-current
                         ;; says it is current), so focus can land on it
                         [:button {:type       "button"
                                   :id         (control-id hx-target "page" (str p))
                                   :class      (str "page-btn" (when active? " active"))
                                   :hx-get     (when-not active? (mk-url p))
                                   :hx-target  (when-not active? hx-target)
                                   :hx-swap    (when-not active? hx-swap)
                                   :hx-push-url (when-not active? (mk-push-url p))
                                   :hx-params  "none"
                                   :aria-label [:t :admin/pagination-page {:page p}]
                                   :aria-current (when active? "page")}
                          (str p)]))
        ;; data-page is what the keyboard pages with (admin keyboard.js)
        nav-btn      (fn [p disabled? label which glyph]
                       [:button.page-nav-btn {:type        "button"
                                              :id          (control-id hx-target "page" which)
                                              :hx-get      (mk-url p)
                                              :hx-target   hx-target
                                              :hx-swap     hx-swap
                                              :hx-push-url (mk-push-url p)
                                              :hx-params   "none"
                                              :disabled    disabled?
                                              :aria-label  [:t label]
                                              :title       [:t label]
                                              :data-page   which}
                        glyph])]
    (when show-pages?
      [:div.table-pagination
       [:div.page-info
        [:span.hide-mobile [:t :admin/pagination-showing] " "]
        [:strong (str from "–" to)]
        " " [:t :admin/pagination-of] " " (str total-count)]
       [:nav.pagination-nav {:aria-label [:t :admin/pagination-label]}
        (nav-btn 1 (<= page 1) :admin/pagination-first-page "first" "«")
        (nav-btn prev-page (<= page 1) :admin/pagination-previous-page "prev" "‹")
        ;; Page number buttons with ellipsis
        [:div.page-numbers
         (for [item (page-window page total-pages 1)]
           (if (= item :ellipsis)
             [:span.page-ellipsis "…"]
             (page-btn item)))]
        (nav-btn next-page (>= page total-pages) :admin/pagination-next-page "next" "›")
        (nav-btn total-pages (>= page total-pages) :admin/pagination-last-page "last" "»")]])))

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
   {:f {:op :eq :value \"x\"}}, :op defaulting to :eq as `search-filters->params`
   writes it. Blank values are kept; see `parse-table-params`."
  [params]
  (update-vals
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
           params)
   #(merge {:op :eq} %)))

(defn- blank-spec?
  "A filter with nothing to compare against. An is-empty check needs no value."
  [{:keys [op value min max values]}]
  (and (not (#{:is-null :is-not-null} op))
       (str/blank? value) (str/blank? min) (str/blank? max)
       (not-any? (complement str/blank?) values)))

(defn parse-table-params
  "The table state in a Ring query-params map: {:sort :dir :page :page-size
   :search :filters}, each key only when present and valid.

   opts:
   - :sortable       set of sortable field keywords; any other :sort is dropped
   - :max-page-size  upper bound for :page-size
   - :keep-blank-filters?  keep filters with no value, which a filter form
                     sends for its untouched inputs; dropped by default

   :dir is :asc or :desc (`sort-dir` is accepted too)."
  ([params] (parse-table-params params {}))
  ([params {:keys [sortable max-page-size keep-blank-filters?]}]
   (let [params    (update-keys params param-name)
         sort      (let [s (get params "sort")] (when (and (string? s) (seq s)) (keyword s)))
         dir       (#{:asc :desc} (some-> (or (get params "dir") (get params "sort-dir")) str keyword))
         page      (tc/parse-int (get params "page"))
         page-size (cond-> (tc/parse-int (get params "page-size"))
                     max-page-size (some-> (min max-page-size)))
         search    (let [s (get params "search")] (when (and (string? s) (not (str/blank? s))) s))
         filters   (cond->> (parse-filter-params params)
                     (not keep-blank-filters?) (into {} (remove (comp blank-spec? val))))]
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

(defn sort-select
  "Field and direction selects that re-sort a `data-table`, back to page one:
   for a narrow screen, where rows are cards and the sortable headers hidden.
   Takes `data-table`'s :url, :hx-target, :table-query, :filters and :columns.
   Nil when no column is sortable."
  [{:keys [url hx-target table-query filters columns]}]
  (when-let [sortable (seq (filter #(:sortable? % true) columns))]
    (let [{:keys [sort dir]} table-query
          attrs {:hx-get      (list-url url (dissoc table-query :sort :dir :page) filters)
                 :hx-target   hx-target
                 :hx-swap     "outerHTML"
                 :hx-push-url "true"
                 :hx-include  "closest .card-sort"
                 :class       "select"}]
      [:div {:class "card-sort"}
       [:select (assoc attrs :name "sort" :aria-label [:t :admin/sort-by])
        ;; Sorted by no column on offer (the default sort is often the id):
        ;; the placeholder, not a first option the list is not sorted by
        (when-not (some #(= sort (:field %)) sortable)
          [:option {:value ""} [:t :admin/sort-by]])
        (for [{:keys [field label]} sortable]
          [:option {:value (name field) :selected (= field sort)} label])]
       [:select (assoc attrs :name "dir" :aria-label [:t :admin/sort-direction])
        [:option {:value "asc" :selected (not= :desc dir)} [:t :admin/sort-ascending]]
        [:option {:value "desc" :selected (= :desc dir)} [:t :admin/sort-descending]]]])))

(defn filter-input
  "An input that re-queries the table as the user types (a select, with
   :options [[value label] ...], when it changes) and pushes the URL.

   opts:
   - :name, :value, :label (aria-label), :placeholder, :options
   - :url        the list URL to request, carrying the state to keep
   - :hx-target  the table container selector
   - :hx-swap    default \"outerHTML\"
   - :hx-include other inputs to send along, default \"this\"
   - :push-url?  default true; false leaves the address bar alone
   - :attrs      merged last, for classes or a different :type"
  [{:keys [name value label placeholder options url hx-target hx-swap hx-include push-url? attrs]
    :or   {hx-swap "outerHTML" hx-include "this" push-url? true}}]
  (let [base {:name        name
              :aria-label  label
              :hx-get      url
              :hx-target   hx-target
              :hx-swap     hx-swap
              :hx-push-url (str (boolean push-url?))
              :hx-include  hx-include}]
    (if options
      [:select (merge base {:class "select w-full" :hx-trigger "change"} attrs)
       [:option {:value ""} (or placeholder "")]
       (for [[v l] options]
         [:option {:value v :selected (= (str v) (str value))} l])]
      [:input (merge base {:type        "search"
                           :class       "input w-full"
                           :value       (or value "")
                           :placeholder placeholder
                           :hx-trigger  "input changed delay:300ms, search"}
                     attrs)])))

(defn toolbar-search
  "A list toolbar's search box: a `filter-input` (same opts, :id its input's
   id) behind the search icon."
  [{:keys [id placeholder label] :as opts}]
  [:div.toolbar-search
   (filter-input (-> opts
                     (assoc :label (or label placeholder))
                     (update :attrs merge {:type "text" :class "search-input" :id id})))
   [:span {:class "search-icon" :aria-hidden "true"} (icons/icon :search {:size 15})]])

(defn count-badge
  "A list toolbar's count, \"12 records\" or in a plural catalogue entry
   `label` of its own. A fragment carries it out of band (`htmx/oob`) so it
   follows the search; put it in an aria-live container."
  ([id total-count] (count-badge id total-count :admin/entity-card-count))
  ([id total-count label]
   [:span {:id id :class "record-count-badge"}
    [:t label {:count total-count} total-count]]))

(defn- nil-last
  "`compare`, or its reverse when `desc?`, with nil after any value either way."
  [desc?]
  (fn [a b]
    (cond (nil? a) (if (nil? b) 0 1)
          (nil? b) -1
          desc?    (compare b a)
          :else    (compare a b))))

(defn query-rows
  "A list held in memory, searched and sorted as `table-query` says: the rows
   whose `(search-text row)` holds every word of :search, ignoring case, by
   `((get sort-keys (:sort table-query)) row)` in :dir, nil last. A :sort not
   in `sort-keys` keeps the order given."
  [rows {:keys [search sort dir]} {:keys [search-text sort-keys]}]
  (let [words  (when-not (str/blank? search) (str/split (text/lower-case (str/trim search)) #"\s+"))
        hit?   (fn [row]
                 (let [text (text/lower-case (str (search-text row)))]
                   (every? #(str/includes? text %) words)))
        rows   (cond->> rows words (filter hit?))
        key-fn (get sort-keys sort)]
    (if key-fn
      (sort-by key-fn (nil-last (= :desc dir)) rows)
      rows)))

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
              htmx/merge-attrs {:hx-get     next-url
                                :hx-trigger "revealed"
                                :hx-swap    "afterend"
                                :hx-target  "this"})
      rows)))

(defn hidden-inputs
  "A hidden input per value of a string-keyed param map, a sequential value
   repeating its key: the table state a form or `hx-include` sends along."
  [params]
  (for [[k v] params
        v     (if (sequential? v) v [v])
        :when (some? v)]
    [:input {:type "hidden" :name k :value v}]))

(defn- filter-cell
  [{:keys [field filter label]} {:keys [filter-url hx-target hx-swap filters push-url?]}]
  ;; The swap replaces the input being typed in; htmx refocuses it by id
  [:th {:class (str "filter-cell field-" (name field))}
   (when filter
     (filter-input {:name        (str "filters[" (name field) "][value]")
                    :value       (get-in filters [field :value])
                    :label       label
                    :placeholder (:placeholder filter)
                    :options     (:options filter)
                    :url         filter-url
                    :hx-target   hx-target
                    :hx-swap     hx-swap
                    :hx-include  "closest tr"
                    :push-url?   push-url?
                    :attrs       {:id (str (str/replace (str hx-target) #"^#" "") "-filter-" (name field))}}))])

(defn data-table
  "A table with sortable headers, an optional row of filter inputs, and
   either pagination (placed by the caller, see `pagination`) or, with
   :infinite?, rows that load the next page as the last comes into view.

   opts:
   - :url          the list URL the controls request and push
   - :push-url?    default true; false for a table whose URL is not a page,
                   so its controls leave the address bar alone
   - :rows-url     what the last row requests with :infinite?, answered with
                   just the next rows; no :rows-url, no loading
   - :hx-target    the container the handler re-renders, e.g. \"#orders\"
   - :hx-swap      default \"outerHTML\"
   - :columns      [{:field :label :class :sortable? (default true)
                     :filter {:placeholder :options}}]
   - :rows         rendered [:tr ...] rows
   - :table-query  {:sort :dir :page :page-size :search}
   - :filters      the parsed filters, kept by every control
   - :total-count  needed by :infinite?, with :rows-url
   - :head-before, :head-after  seqs of extra [:th] cells, e.g. a select-all box
   - :colgroup, :tfoot, :class  passed through"
  [{:keys [url push-url? rows-url hx-target hx-swap columns rows table-query filters total-count
           infinite? head-before head-after colgroup tfoot class]
    :or   {hx-swap "outerHTML" push-url? true}}]
  (let [{:keys [sort dir page page-size]} table-query
        head-before (keep identity head-before)
        head-after  (keep identity head-after)
        ;; Page one, sorted as now; the filter values come from the row
        ctx  {:filter-url (list-url url (dissoc table-query :page) nil)
              :hx-target hx-target :hx-swap hx-swap :filters filters :push-url? push-url?}
        rows (cond-> rows
               (and infinite? rows-url) (infinite-rows (next-page-url rows-url table-query filters total-count)))]
    [:table.data-table {:class (str "table" (when class (str " " class)))}
     colgroup
     [:thead
      [:tr
       head-before
       (for [{:keys [field label sortable?] col-class :class :or {sortable? true}} columns]
         (if sortable?
           (update (sortable-th {:label label :field field :current-sort sort :current-dir dir
                                 :base-url url :page page :page-size page-size
                                 :hx-target hx-target :hx-swap hx-swap :hx-push-url? push-url?
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

(defn bulk-bar
  "The bar that appears once rows are selected: how many, what to do with
   them, and how to stop. Inside an Alpine bulk-selection scope (see
   `alpine/bulk-selection-attrs`).

   opts:
   - :form-attrs  the htmx request of #bulk-action-form, e.g. :hx-post
   - :controls    what goes in the form: the action select, the submit button
   - :confirm     `modal/confirm-modal` opts; the form waits for its answer"
  [{:keys [form-attrs controls confirm]}]
  [:div {:class      "bulk-bar"
         :role       "region"
         :aria-label [:t :admin/bulk-bar-label]
         :x-show     "selectedIds.length > 0"
         :x-cloak    true}
   [:span {:class "bulk-count"}
    [:span {:x-text "selectedIds.length"}] " " [:t :admin/bulk-selected]]
   ;; htmx asks the form, not the button, to confirm a submit
   (into [:form#bulk-action-form (merge (modal/confirm-attrs (:id confirm)) form-attrs)]
         controls)
   ;; Outside the form: the modal holds forms of its own
   (modal/confirm-modal confirm)
   [:button {:type "button" :class "button bulk-clear" :x-on:click "clearSelection()"}
    [:t :admin/bulk-clear]
    [:kbd "q"]]])
