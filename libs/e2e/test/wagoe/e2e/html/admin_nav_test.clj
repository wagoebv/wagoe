(ns wagoe.e2e.html.admin-nav-test
  "The admin sidebar groups pages by module, keeps one group open at a time,
   and shows a collapsed group's pages in a flyout."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.blockether.spel.core :as spel]
            [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as loc]
            [wagoe.e2e.fixtures :as fx]
            [wagoe.e2e.helpers.admin :as admin]))

(use-fixtures :each fx/with-fresh-seed)

(defn- toggle [pg module]
  (page/locator pg (str "[x-data^=\"navGroup('" module "'\"] > button.nav-module-toggle")))

(defn- expanded [pg module]
  (loc/get-attribute (toggle pg module) "aria-expanded"))

(defn- link [pg href]
  (page/locator pg (str "nav.admin-sidebar-nav a[href='" href "']")))

(defn- await! [pg js]
  (is (not (map? (fx/wait-for-js pg js {:timeout 5000.0}))) js))

(deftest ^:integration ^:e2e the-sidebar-groups-pages-by-module
  (spel/with-testing-page [pg]
    (admin/login-as-admin! pg fx/*seed*)
    (page/navigate pg (admin/admin-url "/users"))
    (await! pg "!!window.Alpine && !!document.querySelector('.nav-module')")

    (testing "the current page's module is open, its page marked; the rest closed"
      (is (= "true" (expanded pg "user")))
      (is (= "false" (expanded pg "tenant")))
      (is (= "active" (loc/get-attribute (page/locator pg "li:has(> a[href='/web/admin/users'])") "class")))
      (is (loc/is-visible? (link pg "/web/admin/groups")) "the module's own pages sit beside its entities")
      (is (loc/is-visible? (link pg "/web/audit")))
      (is (not (loc/is-visible? (link pg "/web/admin/tenants")))))

    (testing "an entity no module claims is under Other"
      (is (= 1 (loc/count-elements (page/locator pg "[x-data^=\"navGroup('other'\"] a[href='/web/admin/e2e-orders']")))))

    (testing "opening a group by keyboard closes the others"
      (loc/press (toggle pg "tenant") "Enter")
      (is (= "true" (expanded pg "tenant")))
      (is (loc/is-visible? (link pg "/web/admin/tenants")))
      (is (= "false" (expanded pg "user")))
      (is (not (loc/is-visible? (link pg "/web/admin/groups")))))

    (testing "the next page opens its own group, and only that one"
      (page/navigate pg (admin/admin-url "/e2e-orders"))
      (await! pg "document.querySelector(\"[x-data^=\\\"navGroup('other'\\\"] > button\")?.getAttribute('aria-expanded') === 'true'")
      (is (= "false" (expanded pg "tenant")))
      (is (= "false" (expanded pg "user"))))

    (testing "the current page's group opens again, though it was closed"
      (loc/click (toggle pg "other"))
      (is (= "false" (expanded pg "other")))
      (page/reload pg)
      (await! pg "!!window.Alpine && !!document.querySelector('.nav-module')")
      (is (= "true" (expanded pg "other"))))

    (testing "closed, a module's pages open in a flyout"
      (loc/click (page/locator pg "button.sidebar-toggle"))
      (await! pg "document.documentElement.dataset.sidebar === 'closed'")
      (is (not (loc/is-visible? (link pg "/web/admin/groups"))))
      (loc/hover (toggle pg "user"))
      (is (loc/is-visible? (link pg "/web/admin/groups"))))))

(deftest ^:integration ^:e2e the-dashboard-has-the-sidebar-sections
  (spel/with-testing-page [pg]
    (admin/login-as-admin! pg fx/*seed*)
    (page/navigate pg (admin/admin-url "/"))
    (let [read (fn [js] (page/evaluate pg js))
          sidebar (read (str "[...document.querySelectorAll('nav.admin-sidebar-nav .nav-module')]"
                             ".map(g => [g.querySelector('.nav-module-toggle .nav-text').textContent,"
                             " [...g.querySelectorAll('a')].map(a => [a.getAttribute('href'), a.querySelector('.nav-text').textContent]).sort()])"))
          ;; Entity tiles first, then the other pages as links
          dashboard (read (str "[...document.querySelectorAll('.admin-home-section')]"
                               ".map(s => [s.querySelector('h2').textContent,"
                               " [...s.querySelectorAll('a.entity-card-link, a.dashboard-link')]"
                               ".map(a => [a.getAttribute('href'), a.querySelector('.entity-card-title, .dashboard-link-label').textContent]).sort()])"))]
      (is (seq dashboard))
      (is (= sidebar dashboard) "the same groups in the same order, with the same items and labels"))))

(deftest ^:integration ^:e2e the-sidebar-width-can-be-set
  (spel/with-testing-page [pg]
    (admin/login-as-admin! pg fx/*seed*)
    (page/navigate pg (admin/admin-url "/"))
    (await! pg "!!window.Alpine && !!document.querySelector('.sidebar-resizer')")
    (let [width  "Math.round(document.getElementById('admin-sidebar').getBoundingClientRect().width)"
          is-w   #(await! pg (str width " === " %))
          handle (page/locator pg ".sidebar-resizer")]
      (is-w 240)
      (testing "arrow keys on the edge widen it, and the width survives a reload"
        (loc/press handle "ArrowRight")
        (is-w 256)
        (page/reload pg)
        (await! pg "!!document.querySelector('.sidebar-resizer')")
        (is-w 256))

      (testing "a double-click resets it"
        (loc/dblclick handle)
        (is-w 240)))))
