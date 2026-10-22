(ns wagoe.e2e.html.admin-accessibility-test
  "The admin list, drawer and detail page have no serious axe violations; a
   sort header is a column header with a button the keyboard sorts with; focus
   returns to the row after the drawer closes and stays on the control after a
   sort or page swap."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.blockether.spel.core :as spel]
            [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as loc]
            [wagoe.e2e.fixtures :as fx]
            [wagoe.e2e.helpers.admin :as admin])
  (:import (com.microsoft.playwright.options AriaRole)))

(use-fixtures :each fx/with-fresh-seed)

(def ^:private axe-path "META-INF/resources/webjars/axe-core/4.13.0/axe.min.js")

(def ^:private axe-source
  (delay (slurp (or (io/resource axe-path)
                    (throw (ex-info "axe-core is not on the classpath; its webjar version moved?"
                                    {:type :wagoe.e2e/missing-axe :path axe-path}))))))

(defn- serious-violations
  "axe's serious and critical violations on the page, with the first few
   offending nodes of each. After the load transitions: mid-fade, axe measures
   colours nobody reads."
  [pg]
  (page/evaluate pg "Promise.all(document.getAnimations().map(a => a.finished.catch(() => null)))")
  (when (page/evaluate pg "typeof axe === 'undefined'")
    (page/evaluate pg @axe-source))
  (page/evaluate pg "axe.run(document, {resultTypes: ['violations']}).then(r => r.violations
                       .filter(v => v.impact === 'serious' || v.impact === 'critical')
                       .map(v => v.id + ': ' + v.nodes.slice(0, 3).map(n => n.target.join(' ') + ' (' + n.failureSummary + ')').join(', ')))"))

(defn- wait-for! [pg expr what]
  (let [r (page/wait-for-function pg expr {:timeout 10000.0 :polling 50.0})]
    (when (map? r)
      (throw (ex-info (str "never true: " what) {:type :wagoe.e2e/timeout :expr expr})))))

(defn- active-id [pg]
  (page/evaluate pg "document.activeElement && document.activeElement.id"))

(defn- open-users! [pg path]
  (admin/login-as-admin! pg fx/*seed*)
  (page/navigate pg (admin/admin-url path))
  (page/wait-for-load-state pg))

(deftest ^:integration ^:e2e list-drawer-and-detail-pass-axe
  (spel/with-testing-page [pg]
    (admin/login-as-admin! pg fx/*seed*)
    (doseq [scheme [:light :dark]]
      (page/emulate-media! pg {:color-scheme scheme})
      (page/navigate pg (admin/admin-url "/users?page-size=1"))
      (page/wait-for-load-state pg)
      (testing (str (name scheme) ": the list")
        (is (empty? (serious-violations pg))))
      (testing (str (name scheme) ": the drawer")
        (loc/click (page/locator pg "table.data-table tbody tr.clickable-row td.c-title a"))
        (wait-for! pg "!!document.querySelector('#record-drawer')" "drawer open")
        (is (empty? (serious-violations pg))))
      (testing (str (name scheme) ": the detail page")
        (page/navigate pg (admin/app-url (page/evaluate pg "document.querySelector('tr.clickable-row').dataset.href")))
        (page/wait-for-load-state pg)
        (is (empty? (serious-violations pg)))))))

(deftest ^:integration ^:e2e sort-headers-are-column-headers-the-keyboard-sorts
  (spel/with-testing-page [pg]
    (open-users! pg "/users")
    (let [th     "table.data-table th.field-name"
          sort   (fn [] (page/evaluate pg (str "document.querySelector('" th "').getAttribute('aria-sort')")))
          button (page/locator pg (str th " .sort-button"))]
      (is (pos? (loc/count-elements (page/get-by-role pg AriaRole/COLUMNHEADER {:name "Name" :exact true}))))
      (is (pos? (loc/count-elements (page/get-by-role pg AriaRole/BUTTON {:name "Name" :exact true}))))
      (is (nil? (page/evaluate pg "document.querySelector('table.data-table th[role], table.data-table th[tabindex]')"))
          "no header overrides its role or takes focus itself")
      (doseq [expected ["ascending" "descending"]]
        (admin/await-htmx-ready! pg (str th " .sort-button"))
        (loc/focus button)
        (admin/after-table-swap! pg #(page/keyboard-press pg "Enter"))
        (is (= expected (sort)) "Enter sorts, and the header says how")
        (wait-for! pg "document.activeElement !== document.body" "focus back")
        (is (= "entity-table-container-sort-name" (active-id pg)))))))

(deftest ^:integration ^:e2e focus-stays-on-the-pager-after-a-page-swap
  (spel/with-testing-page [pg]
    (open-users! pg "/users?page-size=1")
    (testing "next, now disabled on the last page: the current page takes focus"
      (loc/focus (page/locator pg ".table-pagination [data-page='next']"))
      (admin/after-table-swap! pg #(page/keyboard-press pg "Enter"))
      (wait-for! pg "document.activeElement && document.activeElement.getAttribute('aria-current') === 'page'"
                 "focus on the current page")
      (is (= "entity-table-container-page-2" (active-id pg))))
    (testing "a page number keeps focus"
      (admin/await-htmx-ready! pg "#entity-table-container-page-1")
      (loc/focus (page/locator pg "#entity-table-container-page-1"))
      (admin/after-table-swap! pg #(page/keyboard-press pg "Enter"))
      (wait-for! pg "document.activeElement !== document.body" "focus back")
      (is (= "entity-table-container-page-1" (active-id pg)))
      (is (= "page" (page/evaluate pg "document.activeElement.getAttribute('aria-current')"))))))

(deftest ^:integration ^:e2e closing-the-drawer-returns-focus-to-its-row
  (spel/with-testing-page [pg]
    (open-users! pg "/users")
    (let [href       (page/evaluate pg "document.querySelectorAll('tr.clickable-row')[1].dataset.href")
          open!      (fn []
                       (loc/click (page/locator pg (str "tr.clickable-row[data-href='" href "'] td.c-title a")))
                       (wait-for! pg "!!document.querySelector('#record-drawer')" "drawer open"))
          focused    #(page/evaluate pg "document.activeElement && document.activeElement.dataset.href")
          closed?    "!document.querySelector('#record-drawer')"]
      (is (str/starts-with? href "/web/admin/users/"))
      (testing "Escape"
        (open!)
        ;; keyboard.js leaves Esc to a full-screen browser, which a headless
        ;; viewport the size of its screen looks like
        (page/evaluate pg "Object.defineProperty(window.screen, 'height', {get: () => 100000})")
        (page/keyboard-press pg "Escape")
        (wait-for! pg closed? "drawer closed")
        (is (= href (focused))))
      (testing "the close button"
        (open!)
        (loc/click (page/locator pg "#record-drawer .drawer-close"))
        (wait-for! pg closed? "drawer closed")
        (is (= href (focused)))))))
