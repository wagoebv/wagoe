(ns wagoe.shared.ui.core.form-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hiccup2.core :as h]
            [wagoe.platform.core.csrf :as csrf]
            [wagoe.shared.ui.core.form :as form]))

(def ^:private schema
  [:map
   [:name [:string {:min 2 :max 40}]]
   [:code [:re #"^[A-Z]{3}$"]]
   [:email [:re {:error/message "Invalid email format"} #"^[^@]+@[^@]+$"]]
   [:homepage-url {:optional true} :string]
   [:contact [:string {:html/type :tel}]]
   [:qty [:int {:min 1 :max 99}]]
   [:age {:optional true} [:maybe [:>= 18]]]
   [:price {:optional true} decimal?]
   [:due [:and {:html/type :date} [:re #"^\d{4}-\d{2}-\d{2}$"]]]
   [:status [:enum :draft :sent]]
   [:paid :boolean]
   [:meta {:optional true} :map]])

(deftest ^:unit attributes-follow-the-schema
  (testing "a required string with bounds"
    (is (= {:type "text" :required true :minlength 2 :maxlength 40}
           (form/input-attrs schema :name))))
  (testing "a regex is the pattern of a text field"
    (is (= {:type "text" :required true :pattern "^[A-Z]{3}$"}
           (form/input-attrs schema :code)))
    (is (nil? (:pattern (form/input-attrs [:map [:c [:re #"\d"]]] :c)))
        "malli finds an unanchored regex anywhere; a pattern must match it all"))
  (testing "email and url by name, any type by :html/type"
    (is (= "email" (:type (form/input-attrs schema :email))))
    (is (= "url" (:type (form/input-attrs schema :homepage-url))))
    (is (= "tel" (:type (form/input-attrs schema :contact))))
    (is (= "date" (:type (form/input-attrs schema :due)))))
  (testing "numbers: min and max, step any for a decimal"
    (is (= {:type "number" :required true :min 1 :max 99} (form/input-attrs schema :qty)))
    (is (= {:type "number" :min 18} (form/input-attrs schema :age)))
    (is (= {:type "number" :step "any"} (form/input-attrs schema :price))))
  (testing "optional and :maybe are not required; a checkbox never is"
    (is (not (:required (form/input-attrs schema :homepage-url))))
    (is (not (:required (form/input-attrs schema :age))))
    (is (= {:type "checkbox"} (form/input-attrs schema :paid))))
  (testing "an enum is a select, so it carries no input type"
    (is (= {:required true} (form/input-attrs schema :status))))
  (testing "a nested map is no form field"
    (is (not (some #{:meta} (form/field-keys schema))))
    (is (nil? (form/input-attrs schema :nope)))))

(deftest ^:unit field-key-only-names-fields-of-the-schema
  (is (= :qty (form/field-key schema "qty")))
  (is (nil? (form/field-key schema "meta")))
  (is (nil? (form/field-key schema "anything-else")))
  (is (nil? (form/field-key schema nil))))

(deftest ^:unit validate-decodes-what-the-browser-sent
  (let [{:keys [data errors]} (form/validate schema {"name" "Kettle" "code" "ABC" "email" "a@b.nl"
                                                     "contact" "06" "qty" "3" "age" "" "price" "9.99"
                                                     "due" "2026-10-17" "status" "sent"
                                                     "paid" ["false" "true"] "stray" "x"})]
    (is (nil? errors))
    (is (= {:name "Kettle" :code "ABC" :email "a@b.nl" :contact "06" :qty 3 :age nil :price 9.99M
            :due "2026-10-17" :status :sent :paid true}
           data))))

(deftest ^:unit errors-are-i18n-markers-per-field
  (let [{:keys [errors]} (form/validate schema {"name" "K" "code" "abc" "email" "nope" "qty" "100"
                                                "age" "12" "price" "abc" "status" "lost"
                                                "paid" "false" "due" "2026-10-17"})]
    (is (= {:name    [[:t :validation/too-short {:min 2}]]
            :code    [[:t :validation/pattern]]
            :email   ["Invalid email format"]
            :contact [[:t :validation/required]]
            :qty     [[:t :validation/too-large {:max 99}]]
            :age     [[:t :validation/too-small {:min 18}]]
            :price   [[:t :validation/not-a-number]]
            :status  [[:t :validation/not-an-option]]}
           errors)))
  (testing "plain-errors reads them in English"
    (is (= {:name ["At least 2 characters"] :qty ["Required"]}
           (form/plain-errors {:name [[:t :validation/too-short {:min 2}]]
                               :qty  [[:t :validation/required]]})))))

(deftest ^:unit the-english-fallback-matches-the-catalogue
  (let [catalogue (edn/read-string (slurp (io/resource "wagoe/i18n/translations/en.edn")))
        nl        (edn/read-string (slurp (io/resource "wagoe/i18n/translations/nl.edn")))]
    (is (= form/english (select-keys catalogue (keys form/english))))
    (is (= (set (keys form/english))
           (set (filter #(= "validation" (namespace %)) (keys nl)))))))

(deftest ^:unit the-field-wrapper
  (let [html (str (h/html (form/field schema :name {:value "K" :validate-url "/web/things/validate"
                                                    :errors ["Too short"]})))]
    (testing "has a stable id, the label and the derived attributes"
      (is (str/includes? html "id=\"name-field\""))
      (is (str/includes? html "<label for=\"name\">Name</label>"))
      (is (str/includes? html "minlength=\"2\""))
      (is (str/includes? html "required")))
    (testing "posts itself when it changes and is swapped whole"
      (is (str/includes? html "hx-post=\"/web/things/validate\""))
      (is (str/includes? html "hx-trigger=\"change delay:300ms\""))
      (is (str/includes? html "hx-target=\"closest .form-field\""))
      (is (str/includes? html "hx-swap=\"outerHTML\""))
      (is (str/includes? html "hx-vals=\"{&quot;__field&quot;:&quot;name&quot;}\"")))
    (testing "ties its errors to the control"
      (is (str/includes? html "aria-invalid=\"true\""))
      (is (str/includes? html "aria-describedby=\"name-error\""))
      (is (str/includes? html "<div class=\"field-errors\" id=\"name-error\"><span class=\"error\">Too short</span>"))))
  (testing "without errors the control is not marked invalid"
    (let [html (str (h/html (form/field schema :name {:value "Kettle"})))]
      (is (not (str/includes? html "aria-invalid")))
      (is (not (str/includes? html "field-errors")))
      (is (not (str/includes? html "hx-post"))))))

(deftest ^:unit controls-by-type
  (testing "an enum is a select with the value chosen"
    (let [html (str (h/html (form/field schema :status {:value :sent})))]
      (is (str/includes? html "<select"))
      (is (str/includes? html "<option selected=\"selected\" value=\"sent\">Sent</option>"))))
  (testing "a boolean is a checkbox with its unchecked fallback"
    (let [html (str (h/html (form/field schema :paid {:value "true" :validate-url "/v"})))]
      (is (str/includes? html "type=\"hidden\" value=\"false\""))
      (is (re-find #"<input checked[^>]*hx-post=\"/v\"[^>]*type=\"checkbox\"" html)))))

(deftest ^:unit exclusive-bounds-say-so
  (is (= {:n [[:t :validation/greater-than {:min 0}]]}
         (:errors (form/validate [:map [:n [:> 0]]] {"n" "0"}))))
  (is (= {:n [[:t :validation/less-than {:max 10}]]}
         (:errors (form/validate [:map [:n [:< 10]]] {"n" "10"})))))

(deftest ^:unit a-namespaced-enum-round-trips
  (let [s [:map [:status [:enum :order/open :order/paid]]]]
    (is (str/includes? (str (h/html (form/field s :status {:value :order/paid})))
                       "<option selected=\"selected\" value=\"order/paid\">"))
    (is (= {:data {:status :order/paid} :errors nil}
           (form/validate s {"status" "order/paid"})))))

(deftest ^:unit live-attrs-carry-extra-values
  (is (= "{\"__field\":\"name\",\"__id\":\"42\"}"
         (:hx-vals (form/live-attrs "/v" :name {"__id" "42"})))))

(deftest ^:unit a-form-carries-the-csrf-token
  (binding [csrf/*token* "tok"]
    (is (str/includes? (str (h/html (form/form {:method "post"} [:button "Go"])))
                       "<input name=\"__anti-forgery-token\" type=\"hidden\" value=\"tok\" />"))))
