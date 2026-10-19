(ns wagoe.shared.ui.core.table-test
  (:require [wagoe.shared.ui.core.table :as table-ui]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest ^:unit sortable-th-direction-toggle-test
  (testing "active asc sort toggles to desc and renders ascending icon"
    (let [th (table-ui/sortable-th {:label "Email"
                                    :field :email
                                    :current-sort :email
                                    :current-dir :asc
                                    :base-url "/web/admin/users/table"
                                    :page-size 20
                                    :hx-target "#entity-table-container"
                                    :hx-push-url? true})
          attrs (second th)]
      (is (str/includes? (:hx-get attrs) "sort=email"))
      (is (str/includes? (:hx-get attrs) "dir=desc"))
      (is (str/includes? (:hx-push-url attrs) "dir=desc"))
      (is (= "↑" (last (last th))))))

  (testing "active desc sort toggles to asc and renders descending icon"
    (let [th (table-ui/sortable-th {:label "Email"
                                    :field :email
                                    :current-sort :email
                                    :current-dir :desc
                                    :base-url "/web/admin/users/table"
                                    :page-size 20
                                    :hx-target "#entity-table-container"
                                    :hx-push-url? true})
          attrs (second th)]
      (is (str/includes? (:hx-get attrs) "sort=email"))
      (is (str/includes? (:hx-get attrs) "dir=asc"))
      (is (str/includes? (:hx-push-url attrs) "dir=asc"))
      (is (= "↓" (last (last th)))))))

(deftest ^:unit table-controls-replace-their-target-test
  ;; The handlers behind these return the element carrying the target id, so
  ;; an innerHTML swap nested a duplicate on every click (BOU-386).
  (let [th    (table-ui/sortable-th {:label "Email" :field :email :base-url "/t"
                                     :page-size 20 :hx-target "#entity-table-container"})
        pager (table-ui/pagination {:table-query {:page 2 :page-size 10} :total-count 50
                                    :base-url "/t" :hx-target "#entity-table-container"})
        requesting (filter #(and (vector? %) (map? (second %)) (:hx-get (second %)))
                           (tree-seq #(or (vector? %) (seq? %)) seq [th pager]))]
    (is (< 3 (count requesting)))
    (is (every? #(= "outerHTML" (:hx-swap (second %))) requesting))))

(deftest ^:unit table-controls-hx-swap-option-test
  ;; For a handler that returns only the target's contents.
  (let [th    (table-ui/sortable-th {:label "Email" :field :email :base-url "/t" :page-size 20
                                     :hx-target "#t" :hx-swap "innerHTML"})
        pager (table-ui/pagination {:table-query {:page 2 :page-size 10} :total-count 50
                                    :base-url "/t" :hx-target "#t" :hx-swap "innerHTML"})
        requesting (filter #(and (vector? %) (map? (second %)) (:hx-get (second %)))
                           (tree-seq #(or (vector? %) (seq? %)) seq [th pager]))]
    (is (< 3 (count requesting)))
    (is (every? #(= "innerHTML" (:hx-swap (second %))) requesting))))

(deftest ^:unit filter-specs-survive-sort-and-paging-test
  (let [filters {:status {:op :eq :value "paid"}
                 :kind   {:op :in :values ["a" "b c"]}
                 :role   "admin"}
        th      (table-ui/sortable-th {:label "Email" :field :email :base-url "/t"
                                       :page 1 :page-size 20 :hx-target "#t"
                                       :extra-params filters})
        url     (get-in th [1 :hx-get])]
    (testing "a spec map goes back in the filter bar's format, URL-encoded"
      (is (str/includes? url "filters%5Bstatus%5D%5Bop%5D=eq"))
      (is (str/includes? url "filters%5Bstatus%5D%5Bvalue%5D=paid"))
      (is (str/includes? url "filters%5Bkind%5D%5Bvalues%5D%5B%5D=a&filters%5Bkind%5D%5Bvalues%5D%5B%5D=b+c"))
      (is (str/includes? url "role=admin"))
      (is (not (str/includes? url "%7B")) "no printed map"))
    (testing "search is kept"
      (is (str/includes? (table-ui/encode-query-params
                          (table-ui/table-query->params {:page 2 :page-size 20 :search "a&b"}))
                         "search=a%26b")))))

;; ---------------------------------------------------------------------------
;; Data table
;; ---------------------------------------------------------------------------

(defn- requesting-elements [hiccup]
  (filter #(and (vector? %) (map? (second %)) (:hx-get (second %)))
          (tree-seq #(or (vector? %) (seq? %)) seq hiccup)))

(defn- tagged [tag hiccup]
  (filter #(and (vector? %) (= tag (first %))) (tree-seq coll? seq hiccup)))

(deftest ^:unit parse-table-params-test
  (testing "the table state, typed"
    (is (= {:sort :email :dir :desc :page 3 :page-size 50 :search "ann"}
           (table-ui/parse-table-params {"sort" "email" "dir" "desc" "page" "3"
                                         "page-size" "50" "search" "ann"}))))
  (testing "keyword keys and sort-dir are accepted"
    (is (= {:sort :name :dir :asc}
           (table-ui/parse-table-params {:sort "name" :sort-dir "asc"}))))
  (testing "what is missing or invalid is left out"
    (is (= {} (table-ui/parse-table-params {"sort" "" "dir" "sideways" "page" "0"
                                            "page-size" "x" "search" "  "})))
    (is (= {} (table-ui/parse-table-params {"page" "-2" "search" ["a" "b"]}))))
  (testing "a sort outside :sortable is dropped, a page size above the cap clamped"
    (is (= {:page-size 100}
           (table-ui/parse-table-params {"sort" "password_hash" "page-size" "100000"}
                                        {:sortable #{:email} :max-page-size 100}))))
  (testing "filters in the filters[f][part] format, blank ones dropped"
    (is (= {:filters {:status {:op :eq :value "paid"}
                      :kind   {:op :in :values ["a" "b"]}
                      :total  {:op :between :min "1" :max "9"}}}
           (table-ui/parse-table-params
            {"filters[status][op]"     "eq"
             "filters[status][value]"  "paid"
             "filters[kind][op]"       "in"
             "filters[kind][values][]" ["a" "b"]
             "filters[total][op]"      "between"
             "filters[total][min]"     "1"
             "filters[total][max]"     "9"
             "filters[name][op]"       "contains"
             "filters[name][value]"    ""
             "unrelated"               "x"})))))

(deftest ^:unit parse-filter-params-keeps-blank-test
  (is (= {:name {:op :contains :value ""}}
         (table-ui/parse-filter-params {"filters[name][op]" "contains" "filters[name][value]" ""}))))

(deftest ^:unit list-url-round-trips-test
  (let [tq      {:sort :email :dir :desc :page 2 :page-size 20 :search "a b"}
        filters {:status {:op :eq :value "paid"}}
        url     (table-ui/list-url "/orders" tq filters)
        params  (into {} (for [kv (str/split (second (str/split url #"\?")) #"&")
                               :let [[k v] (map #(java.net.URLDecoder/decode ^String % "UTF-8")
                                                (str/split kv #"="))]]
                           [k v]))]
    (is (str/starts-with? url "/orders?"))
    (is (= (assoc tq :filters filters) (table-ui/parse-table-params params)))
    (is (= "/orders" (table-ui/list-url "/orders" {} nil)) "the default view is the bare path")))

(deftest ^:unit filter-input-test
  (testing "a text input re-queries as the user types and pushes the URL"
    (let [[tag attrs] (table-ui/filter-input {:name "search" :value "ann" :url "/orders?sort=id"
                                              :hx-target "#orders"})]
      (is (= :input tag))
      (is (= "ann" (:value attrs)))
      (is (= "/orders?sort=id" (:hx-get attrs)))
      (is (= "true" (:hx-push-url attrs)))
      (is (= "#orders" (:hx-target attrs)))
      (is (= "outerHTML" (:hx-swap attrs)))
      (is (str/includes? (:hx-trigger attrs) "delay:300ms"))))
  (testing "with :options a select, the current value selected"
    (let [select (table-ui/filter-input {:name "status" :value "paid" :url "/o" :hx-target "#o"
                                         :options [["open" "Open"] ["paid" "Paid"]]})]
      (is (= :select (first select)))
      (is (= "change" (get-in select [1 :hx-trigger])))
      (is (= ["paid"] (map #(get-in % [1 :value])
                           (filter #(get-in % [1 :selected]) (tagged :option select)))))))
  (testing ":attrs win"
    (is (= "text" (get-in (table-ui/filter-input {:name "q" :url "/o" :attrs {:type "text"}})
                          [1 :type])))))

(deftest ^:unit data-table-test
  (let [table (table-ui/data-table
               {:url         "/orders"
                :hx-target   "#orders"
                :table-query {:sort :number :dir :asc :page 1 :page-size 20 :search "x"}
                :filters     {:status {:op :eq :value "paid"}}
                :columns     [{:field :number :label "Number"}
                              {:field :status :label "Status" :sortable? false
                               :filter {:options [["paid" "Paid"]]}}
                              {:field :customer :label "Customer" :class "text-right"
                               :filter {:placeholder "Name"}}]
                :head-before [[:th "select"]]
                :head-after  [nil [:th "actions"]]
                :rows        [[:tr [:td "1"]] [:tr [:td "2"]]]})
        [header-row filter-row] (tagged :tr table)
        ths (fn [row] (tagged :th row))]
    (testing "sortable headers push the list URL, keeping filters and search"
      (let [th  (first (filter #(get-in % [1 :hx-get]) (ths header-row)))
            url (get-in th [1 :hx-get])]
        (is (str/starts-with? url "/orders?"))
        (is (str/includes? url "dir=desc"))
        (is (str/includes? url "search=x"))
        (is (str/includes? url "filters%5Bstatus%5D%5Bvalue%5D=paid"))
        (is (= url (get-in th [1 :hx-push-url])))
        (is (str/includes? (get-in th [1 :class]) "field-number"))))
    (testing "a column can opt out of sorting and carry a class"
      (is (= ["field-status"] (keep #(when-not (get-in % [1 :hx-get]) (get-in % [1 :class]))
                                    (rest (butlast (ths header-row))))))
      (is (some #(str/ends-with? (str (get-in % [1 :class])) "field-customer text-right")
                (ths header-row))))
    (testing "the filter row lines up with the headers, nil cells dropped"
      (is (= 5 (count (ths header-row)) (count (ths filter-row)))))
    (testing "filter inputs send the row and keep the sort, from page one"
      (let [inputs (requesting-elements filter-row)]
        (is (= 2 (count inputs)))
        (is (every? #(= "closest tr" (get-in % [1 :hx-include])) inputs))
        (is (every? #(str/includes? (get-in % [1 :hx-get]) "sort=number") inputs))
        (is (not-any? #(str/includes? (get-in % [1 :hx-get]) "page=") inputs))
        (is (= #{"filters[status][value]" "filters[customer][value]"}
               (set (map #(get-in % [1 :name]) inputs))))))
    (testing "no infinite scroll unless asked"
      (is (not-any? #(= "revealed" (get-in % [1 :hx-trigger])) (requesting-elements table))))))

(deftest ^:unit data-table-without-filters-test
  (let [table (table-ui/data-table {:url "/o" :hx-target "#o" :table-query {}
                                    :columns [{:field :a :label "A"}] :rows []})]
    (is (= 1 (count (tagged :tr table))) "no filter row")))

(deftest ^:unit infinite-scroll-test
  (let [rows    [[:tr {:id "r1"} [:td "1"]] [:tr [:td "2"]]]
        table   (fn [page total]
                  (table-ui/data-table {:url "/orders" :rows-url "/orders/rows" :hx-target "#o"
                                        :infinite? true :total-count total
                                        :table-query {:sort :id :dir :asc :page page :page-size 2}
                                        :filters {:status {:op :eq :value "paid"}}
                                        :columns [{:field :id :label "Id"}] :rows rows}))
        loaders (fn [t] (filter #(= "revealed" (get-in % [1 :hx-trigger])) (requesting-elements t)))]
    (testing "the last row loads the next page after itself once revealed"
      (let [[loader & more] (loaders (table 1 5))
            attrs (second loader)]
        (is (empty? more))
        (is (= [:td "2"] (last loader)))
        (is (str/starts-with? (:hx-get attrs) "/orders/rows?"))
        (is (str/includes? (:hx-get attrs) "page=2"))
        (is (str/includes? (:hx-get attrs) "filters%5Bstatus%5D%5Bvalue%5D=paid"))
        (is (= "afterend" (:hx-swap attrs)))
        (is (nil? (:hx-push-url attrs)) "scrolling is not navigation")))
    (testing "the last page loads nothing"
      (is (empty? (loaders (table 3 5)))))
    (testing "infinite-rows keeps the row's own attributes"
      (is (= "r1" (get-in (table-ui/infinite-rows [[:tr {:id "r1"}]] "/n") [0 1 :id]))))
    (testing "no next page, rows unchanged"
      (is (= rows (table-ui/infinite-rows rows nil))))))

(deftest ^:unit hidden-inputs-test
  (is (= [[:input {:type "hidden" :name "sort" :value "id"}]
          [:input {:type "hidden" :name "v[]" :value "a"}]
          [:input {:type "hidden" :name "v[]" :value "b"}]]
         (table-ui/hidden-inputs {"sort" "id" "page" nil "v[]" ["a" "b"]}))))

(deftest ^:unit parse-table-params-keeps-blank-filters-on-request-test
  (is (= {:filters {:name {:op :contains :value ""}}}
         (table-ui/parse-table-params {"filters[name][op]" "contains" "filters[name][value]" ""}
                                      {:keep-blank-filters? true}))))
