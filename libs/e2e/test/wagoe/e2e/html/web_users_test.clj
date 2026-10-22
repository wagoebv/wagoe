(ns wagoe.e2e.html.web-users-test
  "E2E browser tests for the user module's own pages in the admin shell:
   creating a user, revoking a session, and a user's pages as tabs."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [com.blockether.spel.core :as spel]
            [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as loc]
            [wagoe.e2e.fixtures :as fx]
            [wagoe.e2e.helpers.admin :as admin]
            [wagoe.e2e.helpers.users :as users]))

(use-fixtures :each fx/with-fresh-seed)

(deftest ^:integration ^:e2e create-user-lands-on-the-list
  (testing "The create form, in the admin shell, makes a user and returns to the list with a toast"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/app-url "/web/users/new"))
      (page/wait-for-load-state pg)
      (is (loc/is-visible? (page/locator pg ".admin-shell .admin-sidebar")))
      (loc/fill (page/locator pg "#name") "Lisa Hendriks")
      (loc/fill (page/locator pg "#email") "lisa.hendriks@meridian.dev")
      (loc/fill (page/locator pg "#password") "Str0ng-Pass-2026")
      (loc/click (page/locator pg "#create-user-form button[type='submit']"))
      (page/wait-for-url pg #".*/web/admin/users$" {:timeout 10000.0})
      (page/wait-for-selector pg "#toasts .alert.alert-success" {:timeout 10000.0})
      (is (str/includes? (loc/inner-text (page/locator pg "#toasts .alert-success")) "Lisa Hendriks"))
      (is (loc/is-visible? (page/locator pg "table.data-table >> text=lisa.hendriks@meridian.dev"))))))

(deftest ^:integration ^:e2e revoke-session-ends-it
  (testing "Revoking another session asks first, then ends it"
    (let [other (-> (users/create-session {:email    (-> fx/*seed* :admin :email)
                                           :password (-> fx/*seed* :admin :password)})
                    :body :sessionToken)]
      (is (true? (get-in (users/validate-session other) [:body :valid])))
      (spel/with-testing-page [pg]
        (admin/login-as-admin! pg fx/*seed*)
        (page/navigate pg (admin/app-url (str "/web/users/" (-> fx/*seed* :admin :id) "/sessions")))
        (page/wait-for-load-state pg)
        (is (loc/is-visible? (page/locator pg ".admin-shell .admin-sidebar")))
        (is (= 2 (loc/count-elements (page/locator pg "table.data-table tbody tr"))))
        ;; Only the other session offers Revoke
        (loc/click (page/locator pg "table.data-table tbody button:has-text('Revoke')"))
        (page/wait-for-selector pg "#revoke-session-confirm[open]" {:timeout 5000.0})
        (loc/click (page/locator pg "#revoke-session-confirm button[value='confirm']"))
        (page/wait-for-selector pg "#toasts .alert.alert-success" {:timeout 10000.0})
        (is (= 1 (loc/count-elements (page/locator pg "table.data-table tbody tr"))))
        (is (not (true? (get-in (users/validate-session other) [:body :valid])))
            "the revoked session no longer signs anyone in")))))

(defn- await! [pg js]
  (is (not (map? (page/wait-for-function pg js {:timeout 5000.0}))) js))

(defn- active-tab [pg]
  (str/trim (loc/inner-text (page/locator pg "nav.record-tabs a[aria-current='page']"))))

(defn- open-tab! [pg label url-re]
  (loc/click (page/locator pg (str "nav.record-tabs a:has-text('" label "')")))
  (page/wait-for-url pg url-re {:timeout 10000.0})
  (page/wait-for-selector pg "nav.record-tabs" {:timeout 10000.0}))

(deftest ^:integration ^:e2e a-users-pages-are-tabs-of-one-another
  (testing "From the admin list: Details, Sessions and Rights, each tab marked when open"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (let [{:keys [id email]} (:user fx/*seed*)]
        (page/navigate pg (admin/admin-url "/users"))
        (page/wait-for-load-state pg)
        (loc/click (page/locator pg (str "table.data-table tbody tr:has-text('" email "') a.row-nav-hint")))
        (page/wait-for-url pg (re-pattern (str ".*/web/admin/users/" id "(\\?.*)?$")) {:timeout 10000.0})
        (is (= "Details" (active-tab pg)))
        (open-tab! pg "Sessions" (re-pattern (str ".*/web/users/" id "/sessions$")))
        (is (= "Sessions" (active-tab pg)))
        (open-tab! pg "Rights" (re-pattern (str ".*/web/users/" id "/rights$")))
        (is (= "Rights" (active-tab pg)))
        (open-tab! pg "Details" (re-pattern (str ".*/web/admin/users/" id "$")))
        (is (= "Details" (active-tab pg))))))
  (testing "The old user page redirects to the admin's"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (let [id (-> fx/*seed* :user :id)]
        (page/navigate pg (admin/app-url (str "/web/users/" id)))
        (page/wait-for-url pg (re-pattern (str ".*/web/admin/users/" id "$")) {:timeout 10000.0})
        (is (loc/is-visible? (page/locator pg "nav.record-tabs")))))))

(deftest ^:integration ^:e2e users-table-pushes-its-own-url
  (testing "Sorting pushes the page's URL once; a refresh adds no history entry"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/app-url "/web/users"))
      (page/wait-for-load-state pg)
      (let [before (page/evaluate pg "history.length")]
        (loc/click (page/locator pg "#users-table-container th.field-email"))
        (await! pg "location.pathname === '/web/users' && location.search.includes('sort=email')")
        (is (= (inc before) (page/evaluate pg "history.length")))
        (page/evaluate pg (str "window.e2eSettled = false;"
                               "document.body.addEventListener('htmx:afterSettle', () => window.e2eSettled = true, {once: true});"
                               "htmx.trigger(document.body, 'userUpdated')"))
        (await! pg "window.e2eSettled")
        (is (= (inc before) (page/evaluate pg "history.length")))
        (is (str/includes? (page/url pg) "sort=email"))))))
