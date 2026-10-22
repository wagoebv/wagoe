(ns wagoe.e2e.html.web-users-test
  "E2E browser tests for the user module's own pages in the admin shell:
   creating a user and revoking a session."
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

(deftest ^:integration ^:e2e deactivate-asks-the-confirm-modal
  (testing "Deactivate asks in the modal, Reactivate undoes it"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/app-url (str "/web/users/" (-> fx/*seed* :user :id))))
      (page/wait-for-load-state pg)
      (loc/click (page/locator pg "#deactivate-user"))
      (page/wait-for-selector pg "#deactivate-confirm[open]" {:timeout 5000.0})
      (loc/click (page/locator pg "#deactivate-confirm button[value='confirm']"))
      (page/wait-for-selector pg "#reactivate-user" {:timeout 10000.0})
      (is (not (loc/is-checked? (page/locator pg "#user-detail input#active"))))
      (loc/click (page/locator pg "#reactivate-user"))
      (page/wait-for-selector pg "#deactivate-user" {:timeout 10000.0})
      (is (loc/is-checked? (page/locator pg "#user-detail input#active"))))))

(deftest ^:integration ^:e2e users-table-pushes-its-own-url
  (testing "Sorting pushes the page's URL once; a refresh adds no history entry"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/app-url "/web/users"))
      (page/wait-for-load-state pg)
      (let [before (page/evaluate pg "history.length")]
        (loc/click (page/locator pg "#users-table-container th.field-email .sort-button"))
        (await! pg "location.pathname === '/web/users' && location.search.includes('sort=email')")
        (is (= (inc before) (page/evaluate pg "history.length")))
        (page/evaluate pg (str "window.e2eSettled = false;"
                               "document.body.addEventListener('htmx:afterSettle', () => window.e2eSettled = true, {once: true});"
                               "htmx.trigger(document.body, 'userUpdated')"))
        (await! pg "window.e2eSettled")
        (is (= (inc before) (page/evaluate pg "history.length")))
        (is (str/includes? (page/url pg) "sort=email"))))))
