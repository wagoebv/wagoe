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

(defn- fragment
  "The body `entity-table-fragment-handler` answers a request aimed at `target`."
  [target]
  (let [schema  #_{:clj-kondo/ignore [:missing-protocol-method]}
                (reify ports/ISchemaProvider
                  (validate-entity-exists [_ _] true)
                  (get-entity-config [_ _] entity-config))
        service #_{:clj-kondo/ignore [:missing-protocol-method]}
                (reify ports/IAdminService
                  (list-entities [_ _ _]
                    {:records [record] :total-count 100 :page-size 20 :page-number 2}))
        handler (list/entity-table-fragment-handler service schema {})]
    (:body (handler {:request-method :get
                     :uri            "/web/admin/users/table"
                     :headers        {"hx-request" "true" "hx-target" target}
                     :user           admin
                     :path-params    {:entity "users"}
                     :query-params   {"sort" "email" "page" "2"
                                      "filters[email][op]" "eq"
                                      "filters[email][value]" "user@example.com"}}))))

(deftest ^:unit table-refresh-keeps-one-container-test
  ;; Search, refresh, sort, paging and filter controls fetched a fragment
  ;; rooted at their target's own id and swapped it innerHTML, nesting a copy
  ;; on every request (BOU-386).
  (let [page [:div
              (ui/entity-search-form :users entity-config "" filters)
              (ui/entity-list-page :users [record] entity-config tq 100 permissions {:filters filters})]]
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
