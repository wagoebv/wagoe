(ns wagoe.e2e.html.admin-create-with-children-test
  "BOU-570: an order whose lines have a has-many :min is created with its
   lines in one form. Rows are added and removed in the browser; too few is
   refused on the page."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [com.blockether.spel.core :as spel]
            [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as loc]
            [wagoe.e2e.fixtures :as fx]
            [wagoe.e2e.helpers.admin :as admin]))

(use-fixtures :each fx/with-fresh-seed)

(def ^:private description-inputs
  ;; A textarea: the admin gives a field named description one.
  "form.entity-form .child-row [name$='.description']")

(defn- open-new-order! [pg]
  (admin/login-as-admin! pg fx/*seed*)
  (page/navigate pg (admin/admin-url "/e2e-orders/new"))
  (page/wait-for-load-state pg)
  (page/wait-for-selector pg "form.entity-form" {:timeout 10000.0}))

(defn- rows [pg]
  (loc/count-elements (page/locator pg description-inputs)))

(defn- text [pg]
  (page/evaluate pg "() => document.body.innerText"))

(deftest ^:integration ^:e2e an-order-is-created-with-its-lines
  (spel/with-testing-page [pg]
    (open-new-order! pg)
    (testing "the form starts with :min line rows"
      (is (= 1 (rows pg))))

    (loc/fill (page/locator pg "input[name='number']") "ORD-1")
    (loc/fill (loc/nth-element (page/locator pg description-inputs) 0) "Hours")

    (testing "a row is added through HTMX"
      (loc/click (page/locator pg "fieldset.child-rows button[hx-get]"))
      (page/wait-for-function pg (str "document.querySelectorAll(\"" description-inputs "\").length === 2")
                              {:timeout 10000.0 :polling 50.0})
      (is (= 2 (rows pg))))
    (loc/fill (loc/nth-element (page/locator pg description-inputs) 1) "Travel")

    (admin/submit-entity-form! pg)

    (testing "the order was created"
      (page/navigate pg (admin/admin-url "/e2e-orders"))
      (page/wait-for-load-state pg)
      (is (= 1 (admin/table-row-count pg))))

    (testing "and its lines with it"
      (page/navigate pg (admin/admin-url "/e2e-order-lines"))
      (page/wait-for-load-state pg)
      (is (= 2 (admin/table-row-count pg)))
      (let [shown (text pg)]
        (is (str/includes? shown "Hours"))
        (is (str/includes? shown "Travel"))))))

(deftest ^:integration ^:e2e an-order-without-lines-is-refused
  (spel/with-testing-page [pg]
    (open-new-order! pg)
    (loc/fill (page/locator pg "input[name='number']") "ORD-2")
    (testing "the only row can be removed in the browser"
      (loc/click (page/locator pg ".child-row button:has-text('Remove')"))
      (is (zero? (rows pg))))

    (admin/submit-entity-form! pg)
    (testing "the refusal is on the page"
      (is (str/includes? (text pg) "at least 1 required, 0 given"))
      (is (= 1 (rows pg)) "with a row to fill in"))

    (testing "and no order was created"
      (page/navigate pg (admin/admin-url "/e2e-orders"))
      (page/wait-for-load-state pg)
      (is (admin/has-empty-state? pg)))))
