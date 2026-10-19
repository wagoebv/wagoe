(ns wagoe.e2e.html.admin-combobox-test
  "BOU-754: a foreign key whose parent has more rows than a select holds is a
   combobox. The user types, picks an option with the keyboard, and the
   record is saved with that parent."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [com.blockether.spel.core :as spel]
            [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as loc]
            [wagoe.e2e.fixtures :as fx]
            [wagoe.e2e.helpers.admin :as admin]))

(use-fixtures :each fx/with-fresh-seed)

(def ^:private combobox "input[role='combobox']#customer-id")

(deftest ^:integration ^:e2e a-ticket-is-saved-with-the-customer-picked-in-the-combobox
  (spel/with-testing-page [pg]
    (admin/login-as-admin! pg fx/*seed*)
    (page/navigate pg (admin/admin-url "/e2e-tickets/new"))
    (page/wait-for-load-state pg)
    (page/wait-for-selector pg "form.entity-form" {:timeout 10000.0})

    (testing "the customer is a combobox, not a select"
      (is (zero? (loc/count-elements (page/locator pg "select[name='customer-id']"))))
      (is (= 1 (loc/count-elements (page/locator pg combobox)))))

    (loc/fill (page/locator pg "input[name='subject']") "Printer on fire")
    (loc/fill (page/locator pg combobox) "Customer 142")
    (page/wait-for-selector pg "#customer-id-listbox li[data-label='Customer 142']" {:timeout 10000.0})

    (testing "the arrow keys move through the options"
      (loc/press (page/locator pg combobox) "ArrowDown")
      (is (= "customer-id-option-0"
             (loc/get-attribute (page/locator pg combobox) "aria-activedescendant")))
      (is (= "true" (loc/get-attribute (page/locator pg combobox) "aria-expanded"))))

    (testing "Enter picks the option into the hidden input"
      (loc/press (page/locator pg combobox) "Enter")
      (is (= "Customer 142" (loc/input-value (page/locator pg combobox))))
      (is (not (str/blank? (loc/input-value (page/locator pg "input[type='hidden'][name='customer-id']")))))
      (is (= "false" (loc/get-attribute (page/locator pg combobox) "aria-expanded"))))

    (admin/submit-entity-form! pg)

    (testing "the ticket was saved with that customer"
      (page/navigate pg (admin/admin-url "/e2e-tickets"))
      (page/wait-for-load-state pg)
      (is (= 1 (admin/table-row-count pg)))
      (page/navigate pg (str (admin/app-url "")
                             (page/evaluate pg "() => document.querySelector('table tbody td.c-title a').getAttribute('href')")))
      (page/wait-for-selector pg "form.entity-form" {:timeout 10000.0})
      (is (= "Customer 142" (loc/input-value (page/locator pg combobox))) "the edit form shows it"))))
