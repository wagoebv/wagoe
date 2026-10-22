(ns wagoe.e2e.html.admin-form-inputs-test
  "BOU-798: a decimal field takes a fraction, and a file field uploads its
   file. Before, the browser refused 12.50 (step 1) and htmx posted the file
   url-encoded, so it never arrived."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [com.blockether.spel.core :as spel]
            [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as loc]
            [wagoe.e2e.fixtures :as fx]
            [wagoe.e2e.helpers.admin :as admin])
  (:import [java.io File]))

(use-fixtures :each fx/with-fresh-seed)

(defn- open-new-product! [pg]
  (admin/login-as-admin! pg fx/*seed*)
  (page/navigate pg (admin/admin-url "/e2e-products/new"))
  (page/wait-for-load-state pg)
  (page/wait-for-selector pg "form.entity-form" {:timeout 10000.0}))

(defn- input-value [pg field]
  (loc/input-value (page/locator pg (str "form.entity-form input[name='" field "']"))))

(defn- text [pg]
  (page/evaluate pg "() => document.body.innerText"))

(defn- open-saved-product!
  "A create lands on the list; open the product named `product` in it."
  [pg product]
  (let [href (page/evaluate pg (str "() => { const a = [...document.querySelectorAll('table.data-table tbody tr a')]"
                                    ".find(a => /e2e-products\\/[0-9a-f-]{36}/.test(a.href)"
                                    " && a.closest('tr').innerText.includes('" product "'));"
                                    " return a ? a.href : null; }"))]
    (is (some? href) "the list shows the saved product")
    (page/navigate pg href)
    (page/wait-for-load-state pg)
    (page/wait-for-selector pg "form.entity-form" {:timeout 10000.0})))

(deftest ^:integration ^:e2e a-decimal-field-saves-a-fraction
  (doseq [price ["12.50" "0.01"]]
    (testing price
      (spel/with-testing-page [pg]
        (open-new-product! pg)
        (loc/fill (page/locator pg "input[name='name']") (str "Widget " price))
        (loc/fill (page/locator pg "input[name='price']") price)
        (admin/submit-entity-form! pg)
        (open-saved-product! pg (str "Widget " price))
        (is (== (bigdec price) (some-> (input-value pg "price") bigdec)))))))

(deftest ^:integration ^:e2e a-file-field-uploads-and-keeps-its-file
  (let [file (doto (File/createTempFile "datasheet" ".txt") (.deleteOnExit))]
    (spit file "hello datasheet")
    (spel/with-testing-page [pg]
      (open-new-product! pg)
      (loc/fill (page/locator pg "input[name='name']") "Widget")
      (loc/set-input-files! (page/locator pg "input[name='datasheet']") (.getPath file))
      (admin/submit-entity-form! pg)
      (open-saved-product! pg "Widget")

      (testing "the file arrived"
        (is (str/includes? (text pg) "A file of 15 bytes is stored")))

      (testing "an edit that leaves the file input empty keeps it"
        (loc/fill (page/locator pg "input[name='name']") "Renamed")
        (admin/submit-entity-form! pg)
        (is (= "Renamed" (input-value pg "name")))
        (is (str/includes? (text pg) "A file of 15 bytes is stored")))

      (testing "the stored file can be removed"
        ;; The optional fields may start folded away
        (page/evaluate pg "() => document.querySelectorAll('details.form-section-optional').forEach(d => d.open = true)")
        (loc/check (page/locator pg "input[name='__remove'][value='datasheet']"))
        (admin/submit-entity-form! pg)
        (is (not (str/includes? (text pg) "is stored")))
        (is (zero? (loc/count-elements (page/locator pg "input[name='__remove']")))
            "nothing left to remove")))))
