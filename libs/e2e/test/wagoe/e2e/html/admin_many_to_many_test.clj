(ns wagoe.e2e.html.admin-many-to-many-test
  "BOU-778: a ticket's labels (a few: checkboxes) and watching customers
   (many: a multiple combobox) are join tables. The form links them, the
   list shows them as chips and filters on them, and the join tables stay
   out of the sidebar."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [com.blockether.spel.core :as spel]
            [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as loc]
            [wagoe.e2e.fixtures :as fx]
            [wagoe.e2e.helpers.admin :as admin]))

(use-fixtures :each fx/with-fresh-seed)

(def ^:private customer "input[role='combobox']#customer-id")
(def ^:private watchers "input[role='combobox']#e2e-customers")
(def ^:private watcher-chips ".combobox-multiple .link-chips .link-chip")

(defn- pick! [pg input text]
  (loc/fill (page/locator pg input) text)
  (page/wait-for-selector pg (str "li[data-label='" text "']") {:timeout 10000.0})
  (loc/press (page/locator pg input) "ArrowDown")
  (loc/press (page/locator pg input) "Enter"))

(defn- chips [pg]
  (loc/count-elements (page/locator pg watcher-chips)))

(defn- list-text [pg]
  (page/evaluate pg "() => document.querySelector('table tbody').innerText"))

(deftest ^:integration ^:e2e a-ticket-is-linked-to-labels-and-watchers
  (spel/with-testing-page [pg]
    (admin/login-as-admin! pg fx/*seed*)
    (page/navigate pg (admin/admin-url "/e2e-tickets/new"))
    (page/wait-for-load-state pg)
    (page/wait-for-selector pg "form.entity-form" {:timeout 10000.0})

    (testing "the join tables are not in the sidebar"
      (is (zero? (loc/count-elements (page/locator pg ".admin-sidebar a[href='/web/admin/e2e-ticket-labels']"))))
      (is (zero? (loc/count-elements (page/locator pg ".admin-sidebar a[href='/web/admin/e2e-ticket-watchers']")))))

    (loc/fill (page/locator pg "input[name='subject']") "Printer on fire")
    (pick! pg customer "Customer 001")

    (testing "three labels are checkboxes"
      (is (= 3 (loc/count-elements (page/locator pg "input[type='checkbox'][name='e2e-labels']")))))
    (loc/click (page/locator pg "label:has-text('Bug') input[name='e2e-labels']"))

    (testing "each watcher picked is a chip; picking clears the text"
      (pick! pg watchers "Customer 007")
      (page/wait-for-function pg (str "document.querySelectorAll(\"" watcher-chips "\").length === 1")
                              {:timeout 10000.0 :polling 50.0})
      (is (= "" (loc/input-value (page/locator pg watchers))))
      (pick! pg watchers "Customer 008")
      (page/wait-for-function pg (str "document.querySelectorAll(\"" watcher-chips "\").length === 2")
                              {:timeout 10000.0 :polling 50.0})
      (is (= 2 (chips pg))))

    (testing "a chip's button removes it"
      (loc/click (page/locator pg (str watcher-chips ":has-text('Customer 008') button")))
      (is (= 1 (chips pg))))

    (admin/submit-entity-form! pg)

    (testing "the list shows the links as chips"
      (page/navigate pg (admin/admin-url "/e2e-tickets"))
      (page/wait-for-load-state pg)
      (is (= 1 (admin/table-row-count pg)))
      (let [shown (list-text pg)]
        (is (str/includes? shown "Bug"))
        (is (str/includes? shown "Customer 007"))
        (is (not (str/includes? shown "Customer 008")))))

    (testing "the list filters on has X and has no X"
      (let [bug (page/evaluate pg "() => fetch('/web/admin/e2e-tickets/new').then(r => r.text()).then(t => Array.from(new DOMParser().parseFromString(t, 'text/html').querySelectorAll('fieldset.link-checkboxes label')).find(l => l.textContent.trim() === 'Bug').querySelector('input').value)")]
        (page/navigate pg (admin/admin-url (str "/e2e-tickets?filters%5Be2e-labels%5D%5Bop%5D=has&filters%5Be2e-labels%5D%5Bvalue%5D=" bug)))
        (page/wait-for-load-state pg)
        (is (= 1 (admin/table-row-count pg)))
        (is (str/includes? (page/evaluate pg "() => document.body.innerText") "Bug"))
        (page/navigate pg (admin/admin-url (str "/e2e-tickets?filters%5Be2e-labels%5D%5Bop%5D=has-not&filters%5Be2e-labels%5D%5Bvalue%5D=" bug)))
        (page/wait-for-load-state pg)
        (is (zero? (admin/table-row-count pg)))))

    (testing "the edit form shows what was saved"
      (page/navigate pg (admin/admin-url "/e2e-tickets"))
      (page/wait-for-load-state pg)
      (page/navigate pg (str (admin/app-url "")
                             (page/evaluate pg "() => document.querySelector('table tbody td.c-title a').getAttribute('href')")))
      (page/wait-for-selector pg "form.entity-form" {:timeout 10000.0})
      (is (= 1 (chips pg)))
      (is (= "Bug" (page/evaluate pg "() => Array.from(document.querySelectorAll(\"input[name='e2e-labels']:checked\")).map(i => i.closest('label').textContent.trim()).join(',')"))))))
