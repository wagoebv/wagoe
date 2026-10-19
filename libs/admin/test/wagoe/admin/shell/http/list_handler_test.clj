(ns wagoe.admin.shell.http.list-handler-test
  "The table fragment handler's response against the page it is swapped into."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.admin.core.ui :as ui]
            [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.http.handlers.list :as list]
            [support.htmx-swap :as htmx-swap]))

(def ^:private entity-config
  {:label       "Users"
   :primary-key :id
   :list-fields [:email :name]
   :search-fields [:email]
   :fields      {:id    {:type :uuid}
                 :email {:type :string :label "Email"}
                 :name  {:type :string :label "Name"}}})

(def ^:private record {:id #uuid "00000000-0000-0000-0000-000000000002"
                       :email "user@example.com" :name "User"})

(def ^:private admin {:id #uuid "00000000-0000-0000-0000-000000000001" :role :admin :active true})

(def ^:private filters {:email {:op :eq :value "user@example.com"}})

(def ^:private tq {:sort :email :dir :asc :page 2 :page-size 20})

(def ^:private permissions
  {:can-view true :can-create true :can-edit true :can-delete true :can-bulk-delete true})

(def ^:private schema
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify ports/ISchemaProvider
    (validate-entity-exists [_ _] true)
    (get-entity-config [_ _] entity-config)
    (list-available-entities [_] [:users])))

(def ^:private service
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify ports/IAdminService
    (list-entities [_ _ _]
      {:records [record] :total-count 100 :page-size 20 :page-number 2})))

(defn- request [uri headers]
  {:request-method :get
   :uri            uri
   :headers        headers
   :user           admin
   :path-params    {:entity "users"}
   :query-params   {"sort" "email" "page" "2"
                    "filters[email][op]" "eq"
                    "filters[email][value]" "user@example.com"}})

(defn- fragment
  "The body `entity-table-fragment-handler` answers a request aimed at `target`."
  [target]
  (:body ((list/entity-table-fragment-handler service schema {})
          (request "/web/admin/users/table" {"hx-request" "true" "hx-target" target}))))

(deftest ^:unit table-refresh-keeps-one-container-test
  ;; Search, refresh, sort, paging and filter controls fetched a fragment
  ;; rooted at their target's own id and swapped it innerHTML, nesting a copy
  ;; on every request (BOU-386).
  ;; The filter form and clear-all live in the filter drawer, so the page is
  ;; rendered with it open
  (let [page [:div
              (ui/entity-search-form :users entity-config "" filters)
              (ui/entity-list-page :users [record] entity-config tq 100 permissions {:filters filters})
              (ui/filter-drawer :users entity-config filters)]]
    (testing "#entity-table-container"
      (let [results (htmx-swap/ids-after-swaps page "entity-table-container"
                                               (fragment "entity-table-container"))]
        (is (<= 8 (count results)) "container, search, refresh, sort headers and pager counted")
        (is (every? #(= 1 (:ids %)) results) (pr-str (remove #(= 1 (:ids %)) results)))))

    (testing "#filter-table-container"
      (let [results (htmx-swap/ids-after-swaps page "filter-table-container"
                                               (fragment "filter-table-container"))]
        (is (<= 4 (count results)) "filter form, rows and clear-all counted")
        (is (every? #(= 1 (:ids %)) results) (pr-str (remove #(= 1 (:ids %)) results)))))))

(deftest ^:unit filter-fragment-matches-the-page-test
  ;; The fragment dropped the page's spacing class, so the layout shifted on
  ;; the first filter change.
  (is (re-find #"^\s*<div [^>]*class=\"space-y-3\"[^>]*id=\"filter-table-container\"|^\s*<div [^>]*id=\"filter-table-container\"[^>]*class=\"space-y-3\""
               (fragment "filter-table-container"))))

(deftest ^:unit the-list-url-answers-its-own-controls-test
  ;; The controls request the page URL and push it, so the address bar holds
  ;; sort, filters and page without rewriting a fragment URL in JS.
  (let [handler (list/entity-list-handler service schema {})
        answer  #(handler (request "/web/admin/users" %))]
    (testing "a request aimed at the table gets the table and the count out of band"
      (let [{:keys [body headers]} (answer {"hx-request" "true" "hx-target" "entity-table-container"})]
        (is (re-find #"^\s*<div[^>]*id=\"entity-table-container\"" body))
        (is (not (re-find #"<html" body)))
        (is (re-find #"<span[^>]*hx-swap-oob=\"true\"[^>]*>100</span>" body))
        (is (= "HX-Request, HX-Target" (get headers "Vary")))))
    (testing "the filter container gets the filter summary too"
      (is (re-find #"^\s*<div[^>]*id=\"filter-table-container\""
                   (:body (answer {"hx-request" "true" "hx-target" "filter-table-container"})))))
    (testing "a browser, another htmx target or a history restore gets the page"
      (doseq [headers [{}
                       {"hx-request" "true" "hx-target" "record-drawer"}
                       {"hx-request" "true" "hx-target" "entity-table-container"
                        "hx-history-restore-request" "true"}]]
        (let [response (answer headers)]
          (is (re-find #"class=\"[^\"]*entity-list-page" (:body response)) (pr-str headers))
          (is (= "HX-Request, HX-Target" (get-in response [:headers "Vary"]))))))))

(deftest ^:unit controls-outside-the-swap-keep-the-sort-test
  ;; The toolbar search and the filter drawer sit outside the table swap, so
  ;; they read the sort from the table rather than from their own URL.
  (let [table  (ui/entity-table :users [record] entity-config tq 100 permissions filters)
        inputs (fn [cls] (->> (tree-seq coll? seq table)
                              (filter #(and (vector? %) (= cls (get-in % [1 :class]))))
                              first
                              (tree-seq coll? seq)
                              (filter #(and (vector? %) (= :input (first %))))
                              (map #(get-in % [1 :name]))
                              set))
        drawer (str (ui/filter-drawer :users entity-config filters))]
    (is (= #{"sort" "dir" "page-size"} (inputs "table-sort")) "no page: a new search starts at one")
    (is (contains? (inputs "table-state") "filters[email][value]"))
    (is (re-find #"table-sort input" drawer) "the drawer sends the sort, not the old filters")))
