(ns wagoe.e2e.html.admin-lookups-test
  "BOU-762: where the admin typed an id or an email, it picks from a lookup —
   a foreign-key filter in the list's filter drawer, and a group's new member."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [com.blockether.spel.core :as spel]
            [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as loc]
            [wagoe.e2e.fixtures :as fx]
            [wagoe.e2e.helpers.admin :as admin]))

(use-fixtures :each fx/with-fresh-seed)

(defn- text [pg selector]
  (str (page/evaluate pg (str "() => document.querySelector(\"" selector "\")?.textContent"))))

(defn- pick!
  "Type `typed` into the combobox `selector`, and pick the option labelled `label`."
  [pg selector typed label]
  (loc/fill (page/locator pg selector) typed)
  (page/wait-for-selector pg (str selector "-listbox li[data-label='" label "']") {:timeout 10000.0})
  (loc/press (page/locator pg selector) "ArrowDown")
  (loc/press (page/locator pg selector) "Enter"))

(defn- create-ticket! [pg subject customer]
  (page/navigate pg (admin/admin-url "/e2e-tickets/new"))
  (page/wait-for-selector pg "form.entity-form" {:timeout 10000.0})
  (loc/fill (page/locator pg "input[name='subject']") subject)
  (pick! pg "#customer-id" customer customer)
  (admin/submit-entity-form! pg))

(defn- filter-by-customer! [pg customer]
  (page/navigate pg (admin/admin-url "/e2e-tickets"))
  (page/wait-for-load-state pg)
  (loc/click (page/locator pg "button.filter-open"))
  ;; The drawer is swapped in: its combobox works once htmx and Alpine have
  ;; processed it, a moment after it appears.
  (page/wait-for-function pg (str "(() => { const i = document.querySelector('#filter-customer-id');"
                                  " return !!(i && i['htmx-internal-data'] && i.closest('.combobox')._x_dataStack); })()")
                          {:timeout 10000.0 :polling 50.0})
  (pick! pg "#filter-customer-id" customer customer)
  (loc/click (page/locator pg ".filter-drawer-form button[type='submit']"))
  ;; The chip names the customer (the list may come back with the last filter)
  ;; wait-for-selector answers a failed wait with an anomaly map, not a throw
  (is (not (map? (page/wait-for-selector pg (str ".filter-chip:has-text('" customer "')") {:timeout 10000.0})))
      (str "a chip names " customer)))

(deftest ^:integration ^:e2e a-list-is-filtered-by-a-customer-picked-by-name
  (spel/with-testing-page [pg]
    (admin/login-as-admin! pg fx/*seed*)
    (create-ticket! pg "Printer on fire" "Customer 142")

    (testing "the customer filter is a combobox, as the form's field is"
      (filter-by-customer! pg "Customer 142")
      (is (= 1 (admin/table-row-count pg)))
      (is (str/includes? (text pg "table.data-table tbody") "Printer on fire")))

    (testing "another customer: the ticket is filtered out"
      (filter-by-customer! pg "Customer 143")
      (is (not (str/includes? (text pg "#filter-table-container") "Printer on fire"))))))

(deftest ^:integration ^:e2e a-group-member-is-picked-by-email
  (spel/with-testing-page [pg]
    (admin/login-as-admin! pg fx/*seed*)
    (page/navigate pg (admin/admin-url "/groups"))
    (page/wait-for-selector pg "button[data-modal-open='group-create-modal']" {:timeout 10000.0})
    (loc/click (page/locator pg "button[data-modal-open='group-create-modal']"))
    (loc/fill (page/locator pg "#group-name") "Finance")
    (loc/click (page/locator pg "form.group-create-form button[type='submit']"))
    (page/wait-for-selector pg "form.group-member-form" {:timeout 10000.0})

    (let [email (-> fx/*seed* :user :email)]
      (testing "typing the email offers the user"
        (loc/fill (page/locator pg "#user-id") email)
        (page/wait-for-selector pg (str "#user-id-listbox li[data-label*='" email "']") {:timeout 10000.0})
        (loc/press (page/locator pg "#user-id") "ArrowDown")
        (loc/press (page/locator pg "#user-id") "Enter")
        (is (not (str/blank? (loc/input-value (page/locator pg "input[type='hidden'][name='user-id']"))))))

      (testing "adding them shows the rights it changes first (BOU-852)"
        (loc/click (page/locator pg "form.group-member-form button[type='submit']"))
        (page/wait-for-selector pg "form.preview-confirm-form" {:timeout 10000.0})
        (is (str/includes? (text pg "body") email)))

      (testing "confirmed, they are listed as a member"
        (loc/click (page/locator pg "form.preview-confirm-form button[type='submit']"))
        (page/wait-for-selector pg "section.group-members table" {:timeout 10000.0})
        (is (str/includes? (text pg "section.group-members table") email))))))
