(ns wagoe.e2e.html.admin-sidebar-test
  "BOU-761: a pinned admin sidebar stays pinned and expanded on the next page."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.blockether.spel.core :as spel]
            [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as loc]
            [wagoe.e2e.fixtures :as fx]
            [wagoe.e2e.helpers.admin :as admin]))

(use-fixtures :each fx/with-fresh-seed)

;; Pinned is read from the pin button's aria-pressed: Alpine drops a data-*
;; attribute bound to false, but keeps aria-pressed="false".
(def ^:private pin "button.sidebar-pin")

(defn- sidebar [pg]
  {:pinned (loc/get-attribute (page/locator pg pin) "aria-pressed")
   :state  (loc/get-attribute (page/locator pg ".admin-shell") "data-sidebar-state")})

(defn- await-state!
  "Wait until the shell's data-sidebar-state is `state`. spel answers a timeout
   with an anomaly map rather than throwing, so a map is the failure."
  [pg state]
  (let [result (page/wait-for-function
                pg
                (str "document.querySelector('.admin-shell')?.dataset.sidebarState === '" state "'")
                {:timeout 5000.0})]
    (is (not (map? result)) (str "sidebar never became " state))))

(defn- leave-sidebar!
  "Hover the sidebar, then the content, so the sidebar sees a mouseleave."
  [pg]
  (loc/hover (page/locator pg "aside.admin-sidebar"))
  (loc/hover (page/locator pg "main.admin-content")))

(deftest ^:integration ^:e2e a-pinned-sidebar-survives-navigation
  (spel/with-testing-page [pg]
    (admin/login-as-admin! pg fx/*seed*)
    (await-state! pg "expanded")
    (is (= {:pinned "false" :state "expanded"} (sidebar pg)) "a fresh browser starts unpinned")

    (loc/click (page/locator pg pin))
    (page/navigate pg (admin/admin-url "/tenants"))
    (page/wait-for-load-state pg)
    (await-state! pg "expanded")

    (testing "the next page starts pinned and expanded"
      (is (= {:pinned "true" :state "expanded"} (sidebar pg))))

    (testing "leaving a pinned sidebar does not collapse it"
      (leave-sidebar! pg)
      (is (= {:pinned "true" :state "expanded"} (sidebar pg))))

    (testing "unpinned, leaving collapses it, and that survives a reload"
      (loc/click (page/locator pg pin))
      (leave-sidebar! pg)
      (await-state! pg "collapsed")
      (page/reload pg)
      (page/wait-for-load-state pg)
      (await-state! pg "collapsed")
      (is (= {:pinned "false" :state "collapsed"} (sidebar pg))))))
