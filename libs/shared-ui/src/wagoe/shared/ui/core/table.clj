(ns wagoe.shared.ui.core.table
  "Shared table UI helpers (sorting, paging) for Hiccup-based web UIs."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Query-param helpers (pure, inlined to avoid core→shell dependency)
;; ---------------------------------------------------------------------------

(defn table-query->params
  "Convert a TableQuery map into a string-keyed param map for query strings."
  [{:keys [sort dir page page-size]}]
  {"sort"      (when sort (name sort))
   "dir"       (when dir (name dir))
   "page"      (str page)
   "page-size" (str page-size)})

(defn encode-query-params
  "Turn a string-keyed map into a query string (nil values are omitted)."
  [m]
  (->> m
       (remove (comp nil? val))
       (map (fn [[k v]] (str k "=" v)))
       (str/join "&")))

(defn search-filters->params
  "Convert parsed search/filter map back into a string-keyed param map."
  [filters]
  (into {}
        (for [[k v] filters]
          [(name k) (str v)])))

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
   - :hx-push-url?  bool, default true
   - :push-url-base optional path used for browser URL updates (defaults to :base-url)
   - :extra-params  map of additional query params (keyword/string keys)

   Behaviour:
   - Clicking toggles :dir between :asc and :desc for the same :field.
   - When changing sort field or direction, page is reset to 1.
   "
  [{:keys [label field current-sort current-dir base-url push-url-base _page page-size
           hx-target hx-push-url? extra-params]}]
  (let [active?  (= current-sort field)
        next-dir (if (and active? (= current-dir :asc)) :desc :asc)
        icon-symbol (cond
                      (not active?) nil
                      (= current-dir :asc) "↑"
                      :else "↓")
        page*    1
        base-q   (table-query->params
                  {:sort field :dir next-dir :page page* :page-size page-size})
        extra-q  (into {}
                       (for [[k v] extra-params]
                         [(name k) (str v)]))
        qs-map   (merge base-q extra-q)
        query-str (encode-query-params qs-map)
        url      (str base-url "?" query-str)
        push-url (when hx-push-url?
                   (str (or push-url-base base-url) "?" query-str))]
    [:th
     {:hx-get     url
      :hx-target  hx-target
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
   - :push-url-base optional path used for browser URL updates (defaults to :base-url)
   - :extra-params  map of additional query params (filters, etc.)

   Returns nil when a single page is sufficient."
  [{:keys [table-query total-count base-url push-url-base hx-target extra-params]}]
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
        extra-q      (into {}
                           (for [[k v] extra-params]
                             [(name k) (str v)]))
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
                               :hx-push-url (mk-push-url 1)
                               :hx-params  "none"
                               :disabled   (<= page 1)
                               :aria-label [:t :admin/pagination-first-page]
                               :title      [:t :admin/pagination-first-page]}
         "«"]
        ;; Previous page
        [:button.page-nav-btn {:type       "button"
                               :hx-get     (mk-url prev-page)
                               :hx-target  hx-target
                               :hx-push-url (mk-push-url prev-page)
                               :hx-params  "none"
                               :disabled   (<= page 1)
                               :aria-label [:t :admin/pagination-previous-page]
                               :title      [:t :admin/pagination-previous-page]}
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
                               :hx-push-url (mk-push-url next-page)
                               :hx-params  "none"
                               :disabled   (>= page total-pages)
                               :aria-label [:t :admin/pagination-next-page]
                               :title      [:t :admin/pagination-next-page]}
         "›"]
        ;; Last page
        [:button.page-nav-btn {:type       "button"
                               :hx-get     (mk-url total-pages)
                               :hx-target  hx-target
                               :hx-push-url (mk-push-url total-pages)
                               :hx-params  "none"
                               :disabled   (>= page total-pages)
                               :aria-label [:t :admin/pagination-last-page]
                               :title      [:t :admin/pagination-last-page]}
         "»"]]])))
