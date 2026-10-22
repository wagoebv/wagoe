(ns wagoe.e2e.html.admin-audit-test
  "The audit trail in the admin shell: filters re-query the table in place,
   the URL keeps them, and a row's details open in the modal."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [com.blockether.spel.core :as spel]
            [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as loc]
            [wagoe.e2e.fixtures :as fx]
            [wagoe.e2e.helpers.admin :as admin]))

(use-fixtures :each fx/with-fresh-seed)

(defn- await! [pg js]
  (is (not (map? (page/wait-for-function pg js {:timeout 5000.0}))) js))

(defn- rows [pg]
  (loc/count-elements (page/locator pg "table.data-table tbody tr")))

(deftest ^:integration ^:e2e the-audit-trail-is-an-admin-list
  (spel/with-testing-page [pg]
    (admin/login-as-admin! pg fx/*seed*)
    (page/navigate pg (admin/app-url "/web/audit"))
    (page/wait-for-load-state pg)

    (testing "in the shell, the sidebar marking it, the seed's entries listed"
      (is (loc/is-visible? (page/locator pg ".admin-shell")))
      (is (= "active" (loc/get-attribute (page/locator pg "li:has(> a[href='/web/audit'])") "class")))
      (is (pos? (rows pg))))

    (testing "a filter re-queries the table in place and pushes the URL"
      (page/evaluate pg "document.body.dataset.e2eMarker = 'alive'")
      (loc/select-option (page/locator pg "#audit-filter-result") "failure")
      (await! pg "!!document.querySelector('#audit-table-container .empty-state-filtered')")
      (is (= "alive" (page/evaluate pg "document.body.dataset.e2eMarker")) "no full reload")
      (is (str/includes? (page/url pg) "result=failure"))
      (is (= "0 entries" (str/trim (loc/text-content (page/locator pg "#audit-record-count"))))))

    (testing "a reload keeps the filter"
      (page/reload pg)
      (page/wait-for-load-state pg)
      (is (= "failure" (loc/input-value (page/locator pg "#audit-filter-result"))))
      (is (loc/is-visible? (page/locator pg ".empty-state-filtered"))))

    (testing "back shows the previous filter in its input, not the one chosen since"
      ;; The page as rendered selects no action, so only the URL knows login
      (loc/select-option (page/locator pg "#audit-filter-action") "login")
      (await! pg "location.search.includes('action=login')")
      (loc/select-option (page/locator pg "#audit-filter-action") "create")
      (await! pg "location.search.includes('action=create')")
      (page/go-back pg)
      (await! pg "location.search.includes('action=login')")
      (await! pg "document.getElementById('audit-filter-action').value === 'login'")
      (loc/select-option (page/locator pg "#audit-filter-action") "")
      (await! pg "location.search.includes('action=&')"))

    (testing "a row's details open in the modal"
      (loc/select-option (page/locator pg "#audit-filter-result") "")
      (await! pg "!!document.querySelector('#audit-table-container table.data-table')")
      (loc/click (loc/nth-element (page/locator pg ".audit-detail-trigger") 0))
      (await! pg "document.getElementById('audit-detail-modal').open")
      (is (not= "-" (str/trim (loc/text-content (page/locator pg "#audit-detail-action"))))))))
