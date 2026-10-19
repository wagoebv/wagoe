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
  (page/wait-for-selector pg "#filter-customer-id" {:timeout 10000.0})
  (pick! pg "#filter-customer-id" customer customer)
  (loc/click (page/locator pg ".filter-drawer-form button[type='submit']"))
  (page/wait-for-selector pg ".filter-chip" {:timeout 10000.0}))

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
    (page/wait-for-selector pg "form.group-create-form" {:timeout 10000.0})
    (loc/fill (page/locator pg "#group-name") "Finance")
    (loc/click (page/locator pg "form.group-create-form button[type='submit']"))
    (page/wait-for-selector pg "form.group-member-form" {:timeout 10000.0})

    (let [email (-> fx/*seed* :user :email)]
      (testing "typing part of the email offers the user"
        (loc/fill (page/locator pg "#user-id") (first (str/split email #"@")))
        (page/wait-for-selector pg (str "#user-id-listbox li[data-label*='" email "']") {:timeout 10000.0})
        (loc/press (page/locator pg "#user-id") "ArrowDown")
        (loc/press (page/locator pg "#user-id") "Enter")
        (is (not (str/blank? (loc/input-value (page/locator pg "input[type='hidden'][name='user-id']"))))))

      (testing "adding them lists them as a member"
        (loc/click (page/locator pg "form.group-member-form button[type='submit']"))
        (page/wait-for-selector pg "section.group-members table" {:timeout 10000.0})
        (is (str/includes? (text pg "section.group-members table") email))))))
