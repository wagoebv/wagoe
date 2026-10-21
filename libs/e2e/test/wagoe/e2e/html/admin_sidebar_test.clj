(ns wagoe.e2e.html.admin-sidebar-test
  "The admin sidebar is open or closed, and only its toggle changes that."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.blockether.spel.core :as spel]
            [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as loc]
            [wagoe.e2e.fixtures :as fx]
            [wagoe.e2e.helpers.admin :as admin]))

(use-fixtures :each fx/with-fresh-seed)

(def ^:private toggle "button.sidebar-toggle")

(defn- await! [pg js]
  ;; spel answers a timeout with an anomaly map rather than throwing
  (let [result (page/wait-for-function pg js {:timeout 5000.0})]
    (is (not (map? result)) (str "never true: " js))))

(defn- await-ready! [pg]
  (await! pg "!!window.Alpine && !!window.Alpine.store('sidebar')"))

(defn- sidebar
  "The state on <html>, what the toggle says, and what is stored."
  [pg]
  (page/evaluate pg (str "[document.documentElement.dataset.sidebar,"
                         " document.querySelector('" toggle "').getAttribute('aria-expanded'),"
                         " localStorage.getItem('wagoe-admin-sidebar')]")))

(defn- hover-in-and-out! [pg]
  (loc/hover (page/locator pg "aside.admin-sidebar"))
  (loc/hover (page/locator pg "main.admin-content")))

(defn- click-toggle! [pg state]
  (loc/click (page/locator pg toggle))
  (await! pg (str "document.documentElement.dataset.sidebar === '" state "'")))

(defn- visit! [pg url]
  (page/navigate pg url)
  (await-ready! pg))

(deftest ^:integration ^:e2e the-sidebar-keeps-its-state-until-toggled
  (spel/with-testing-page [pg]
    (admin/login-as-admin! pg fx/*seed*)
    (await-ready! pg)
    (is (= ["open" "true" nil] (sidebar pg)) "a fresh browser starts open")

    (testing "hovering does nothing"
      (hover-in-and-out! pg)
      (is (= ["open" "true" nil] (sidebar pg))))

    (testing "closed stays closed: next page, reload, another admin page"
      (click-toggle! pg "closed")
      (is (= ["closed" "false" "closed"] (sidebar pg)))
      (hover-in-and-out! pg)
      (is (= ["closed" "false" "closed"] (sidebar pg)) "hovering a closed sidebar does not open it")
      (visit! pg (admin/admin-url "/tenants"))
      (is (= ["closed" "false" "closed"] (sidebar pg)))
      (page/reload pg)
      (await-ready! pg)
      (is (= ["closed" "false" "closed"] (sidebar pg)))
      (visit! pg (admin/app-url "/web/profile"))
      (is (= ["closed" "false" "closed"] (sidebar pg))))

    (testing "open stays open"
      (click-toggle! pg "open")
      (visit! pg (admin/admin-url "/tenants"))
      (is (= ["open" "true" "open"] (sidebar pg)))
      (page/reload pg)
      (await-ready! pg)
      (is (= ["open" "true" "open"] (sidebar pg)))
      (hover-in-and-out! pg)
      (is (= ["open" "true" "open"] (sidebar pg))))))

(deftest ^:integration ^:e2e a-pinned-closed-sidebar-from-before-stays-closed
  (spel/with-testing-page [pg]
    (admin/login-as-admin! pg fx/*seed*)
    (page/evaluate pg (str "localStorage.removeItem('wagoe-admin-sidebar');"
                           "localStorage.setItem('wagoe-admin-sidebar-pinned', 'true');"
                           "localStorage.setItem('wagoe-admin-sidebar-state', '\"collapsed\"')"))
    (page/reload pg)
    (await-ready! pg)
    (is (= ["closed" "false" "closed"] (sidebar pg)))
    (is (nil? (page/evaluate pg "localStorage.getItem('wagoe-admin-sidebar-pinned')"))
        "the old keys are read once")))

(deftest ^:integration ^:e2e on-a-small-screen-the-sidebar-is-a-drawer
  (spel/with-testing-page [pg]
    (admin/login-as-admin! pg fx/*seed*)
    (page/set-viewport-size! pg 390 844)
    (visit! pg (admin/admin-url "/tenants"))
    (let [drawer-open? #(= "true" (loc/get-attribute (page/locator pg ".admin-shell") "data-sidebar-open"))
          open!        (fn []
                         (loc/click (page/locator pg toggle))
                         (await! pg "document.querySelector('.admin-shell').dataset.sidebarOpen === 'true'"))]
      (is (not (drawer-open?)) "closed by default")
      (is (= ["open" "false" nil] (sidebar pg)) "the toggle speaks for the drawer")

      (testing "Escape closes it"
        (open!)
        (is (= "true" (loc/get-attribute (page/locator pg toggle) "aria-expanded")))
        (page/keyboard-press pg "Escape")
        (await! pg "!document.querySelector('.admin-shell').dataset.sidebarOpen"))

      (testing "the backdrop closes it"
        (open!)
        (loc/click (page/locator pg ".admin-overlay") {:position {:x 370 :y 400}})
        (await! pg "!document.querySelector('.admin-shell').dataset.sidebarOpen"))

      (testing "following a link closes it"
        (open!)
        (loc/click (page/locator pg ".admin-sidebar-footer a:not([href='/web'])"))
        (page/wait-for-url pg #".*/web/admin/?$" {:timeout 10000.0})
        (await-ready! pg)
        (is (not (drawer-open?))))

      (testing "the desktop state is untouched"
        (is (nil? (page/evaluate pg "localStorage.getItem('wagoe-admin-sidebar')")))
        (page/set-viewport-size! pg 1280 800)
        (await! pg "document.querySelector('button.sidebar-toggle').getAttribute('aria-expanded') === 'true'")
        (is (= ["open" "true" nil] (sidebar pg)))))))
