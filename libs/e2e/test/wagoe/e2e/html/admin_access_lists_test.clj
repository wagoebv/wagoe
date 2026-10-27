(ns wagoe.e2e.html.admin-access-lists-test
  "Groups, roles, permissions and workflow instances are lists like the
   admin's entity lists: search, sort and filters re-query the table in place
   and the URL keeps them; on a narrow screen a select sorts."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.blockether.spel.core :as spel]
            [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as loc]
            [wagoe.e2e.fixtures :as fx]
            [wagoe.e2e.helpers.admin :as admin]))

(use-fixtures :each fx/with-fresh-seed)

(defn- open! [pg path]
  (page/navigate pg (admin/app-url path))
  (page/wait-for-load-state pg))

(defn- search! [pg container query]
  (let [input (page/locator pg (str "#" container "-search"))]
    (admin/await-htmx-ready! pg (str "#" container "-search"))
    (loc/fill input query)
    (admin/after-swap! pg (str "#" container) #(loc/dispatch-event input "search"))))

(defn- titles [pg container]
  (page/evaluate pg (str "Array.from(document.querySelectorAll('#" container " tbody td.c-title > a'))"
                         ".map(td => td.textContent.trim())")))

(defn- text [pg selector]
  (str/trim (str (page/evaluate pg (str "document.querySelector(\"" selector "\")?.textContent")))))

(deftest ^:integration ^:e2e roles-are-searched-and-sorted-in-place
  (spel/with-testing-page [pg]
    (admin/login-as-admin! pg fx/*seed*)
    (open! pg "/web/admin/roles")
    (let [c "roles-table-container"]
      (testing "grouped by module, sorted by name"
        (is (pos? (loc/count-elements (page/locator pg (str "#" c " tr.list-group-row")))))
        (is (= "ascending" (loc/get-attribute (page/locator pg (str "#" c " th.field-name")) "aria-sort"))))

      (testing "a search narrows the list in place, and the count and URL follow"
        (page/evaluate pg "document.body.dataset.e2eMarker = 'alive'")
        (search! pg c "tenants")
        (is (= "alive" (page/evaluate pg "document.body.dataset.e2eMarker")) "no full reload")
        (is (seq (titles pg c)))
        (is (every? #(str/includes? % "tenants") (titles pg c)))
        (is (str/starts-with? (text pg (str "#" c "-count")) (str (count (titles pg c)) " ")))
        (is (str/includes? (page/url pg) "search=tenants")))

      (testing "a header sorts, keeping the search"
        (let [before (titles pg c)]
          (admin/after-swap! pg (str "#" c) #(loc/click (page/locator pg (str "#" c "-sort-name"))))
          (is (= "descending" (loc/get-attribute (page/locator pg (str "#" c " th.field-name")) "aria-sort")))
          (is (< 1 (count before)) "rows enough to see an order")
          (is (= (sort before) (sort (titles pg c))))
          (is (not= before (titles pg c)))
          (is (str/includes? (page/url pg) "dir=desc"))
          (is (str/includes? (page/url pg) "search=tenants"))))

      (testing "a reload keeps both"
        (page/reload pg)
        (page/wait-for-load-state pg)
        (is (= "tenants" (loc/input-value (page/locator pg (str "#" c "-search")))))
        (is (= "descending" (loc/get-attribute (page/locator pg (str "#" c " th.field-name")) "aria-sort"))))

      (testing "searched to nothing: says so and offers the way back"
        (search! pg c "no-such-role")
        (is (loc/is-visible? (page/locator pg (str "#" c " .empty-state-filtered"))))))))

(deftest ^:integration ^:e2e groups-and-permissions-are-searched-in-place
  (spel/with-testing-page [pg]
    (admin/login-as-admin! pg fx/*seed*)
    (testing "groups: Everyone's members are all users, and a search finds it"
      (open! pg "/web/admin/groups")
      (is (str/includes? (text pg "#groups-table-container tbody td.field-members") "All users"))
      (search! pg "groups-table-container" "every")
      (is (= ["Everyone"] (titles pg "groups-table-container"))))
    (testing "permissions: a search on a role finds the permissions it holds"
      (open! pg "/web/admin/permissions")
      (search! pg "permissions-table-container" "admin.users/viewer")
      (is (= ["admin.users/read"] (titles pg "permissions-table-container"))))))

(deftest ^:integration ^:e2e a-narrow-screen-sorts-with-a-select
  (spel/with-testing-page [pg]
    (admin/login-as-admin! pg fx/*seed*)
    (page/set-viewport-size! pg 390 844)
    (open! pg "/web/admin/roles")
    (let [c        "roles-table-container"
          selector (str "#" c " .card-sort select[name='dir']")]
      (is (loc/is-visible? (page/locator pg selector)))
      (is (not (loc/is-visible? (page/locator pg (str "#" c " thead")))) "rows are cards")
      (let [before (titles pg c)]
        (admin/await-htmx-ready! pg selector)
        (admin/after-swap! pg (str "#" c) #(loc/select-option (page/locator pg selector) "desc"))
        (is (str/includes? (page/url pg) "dir=desc"))
        (is (not= before (titles pg c)))))))

(deftest ^:integration ^:e2e workflow-filters-re-query-in-place
  (spel/with-testing-page [pg]
    (admin/login-as-admin! pg fx/*seed*)
    (open! pg "/web/admin/workflows")
    (testing "no Search button: a filter re-queries as it changes and pushes the URL"
      (is (zero? (loc/count-elements (page/locator pg ".workflow-page button[type='submit']"))))
      (page/evaluate pg "document.body.dataset.e2eMarker = 'alive'")
      (let [input (page/locator pg "#workflow-filter-workflow-id")]
        (admin/await-htmx-ready! pg "#workflow-filter-workflow-id")
        (loc/fill input "no-such-workflow")
        (admin/after-swap! pg "#workflows-table-container" #(loc/dispatch-event input "search")))
      (is (= "alive" (page/evaluate pg "document.body.dataset.e2eMarker")) "no full reload")
      (is (str/includes? (page/url pg) "workflow-id=no-such-workflow"))
      (is (loc/is-visible? (page/locator pg "#workflows-table-container .empty-state-filtered"))))
    (testing "a reload keeps the filter"
      (page/reload pg)
      (page/wait-for-load-state pg)
      (is (= "no-such-workflow" (loc/input-value (page/locator pg "#workflow-filter-workflow-id")))))))
