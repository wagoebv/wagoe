(ns wagoe.shared.ui.core.components-test
  "Unit tests for shared UI components.
   
   Tests verify that UI component functions generate correct Hiccup structures
   with proper HTML attributes, classes, and content."
  (:require [wagoe.shared.ui.core.components :as components]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]))

;; =============================================================================
;; Input Components Tests
;; =============================================================================

(deftest ^:unit text-input-test
  (testing "Basic text input generation"
    (let [result (components/text-input :name "John Doe")]
      (is (= [:input {:type "text" :name "name" :id "name" :value "John Doe"
                      :class "form-control ui-input"}]
             result))))

  (testing "Text input with additional options"
    (let [result (components/text-input :email "test@example.com"
                                        {:class "custom-class" :placeholder "Enter email"})]
      (is (= [:input {:type "text" :name "email" :id "email" :value "test@example.com"
                      :class "custom-class form-control ui-input" :placeholder "Enter email"}]
             result))))

  (testing "Text input with nil value"
    (let [result (components/text-input :description nil)]
      (is (= [:input {:type "text" :name "description" :id "description" :value ""
                      :class "form-control ui-input"}]
             result)))))

(deftest ^:unit email-input-test
  (testing "Email input generation"
    (let [result (components/email-input :user-email "user@domain.com")]
      (is (= [:input {:type "email" :name "user-email" :id "user-email"
                      :value "user@domain.com" :class "form-control ui-input"}]
             result))))

  (testing "Email input with validation attributes"
    (let [result (components/email-input :email "" {:required true :pattern ".*@.*"})]
      (is (= [:input {:type "email" :name "email" :id "email" :value ""
                      :required true :pattern ".*@.*" :class "form-control ui-input"}]
             result)))))

(deftest ^:unit password-input-test
  (testing "Password input generation"
    (let [result (components/password-input :password "secret123")]
      (is (= [:input {:type "password" :name "password" :id "password"
                      :value "secret123" :class "form-control ui-input"}]
             result))))

  (testing "Password input with security attributes"
    (let [result (components/password-input :new-password ""
                                            {:autocomplete "new-password" :minlength 8})]
      (is (= [:input {:type "password" :name "new-password" :id "new-password"
                      :value "" :autocomplete "new-password" :minlength 8
                      :class "form-control ui-input"}]
             result)))))

(deftest ^:unit textarea-test
  (testing "Basic textarea generation"
    (let [result (components/textarea :description "Long description text")]
      (is (= [:textarea {:name "description" :id "description" :rows 4 :cols 50
                         :class "form-control ui-input"}
              "Long description text"]
             result))))

  (testing "Textarea with size attributes"
    (let [result (components/textarea :notes "" {:rows 5 :cols 50})]
      (is (= [:textarea {:name "notes" :id "notes" :rows 5 :cols 50
                         :class "form-control ui-input"} ""]
             result)))))

(deftest ^:unit select-field-test
  (testing "Basic select field generation"
    (let [options [[:admin "Administrator"] [:user "Regular User"]]
          result (components/select-field :role options :user)]
      (is (vector? result))
      (is (= :select (first result)))
      (is (= {:name "role" :id "role" :class "form-control ui-input"} (second result)))
      (is (= 3 (count result))) ; select tag + attributes + options
      (let [option-elements (nth result 2)]
        (is (some #(= [:option {:value "user" :selected true} "Regular User"] %)
                  option-elements))
        (is (some #(= [:option {:value "admin" :selected false} "Administrator"] %)
                  option-elements)))))

  (testing "Select field with no selection"
    (let [options [[:pending "Pending"] [:active "Active"]]
          result (components/select-field :status options nil)]
      (is (vector? result))
      (is (= :select (first result))))))

(deftest ^:unit checkbox-test
  (testing "Checked checkbox generation"
    (let [result (components/checkbox :active true)]
      (is (list? result))
      (is (= 2 (count result)))
      (is (= [:input {:type "hidden" :name "active" :value "false"}]
             (first result)))
      (is (= [:input {:type "checkbox" :name "active" :id "active" :value "true"
                      :checked true :class "form-checkbox"}]
             (second result)))))

  (testing "Unchecked checkbox generation"
    (let [result (components/checkbox :notifications false)]
      (is (list? result))
      (is (= 2 (count result)))
      (is (= [:input {:type "hidden" :name "notifications" :value "false"}]
             (first result)))
      (is (= [:input {:type "checkbox" :name "notifications" :id "notifications"
                      :value "true" :class "form-checkbox"}]
             (second result)))))

  (testing "Checkbox with additional attributes"
    (let [result (components/checkbox :terms true {:value "accepted" :required true})]
      (is (list? result))
      (is (= 2 (count result)))
      (is (= [:input {:type "hidden" :name "terms" :value "false"}]
             (first result)))
      (is (= [:input {:type "checkbox" :name "terms" :id "terms" :value "accepted"
                      :checked true :required true :class "form-checkbox"}]
             (second result))))))

;; =============================================================================
;; Form Structure Tests
;; =============================================================================

(deftest ^:unit form-field-test
  (testing "Form field with label and input"
    (let [input-html [:input {:type "text" :name "name" :id "name"}]
          result (components/form-field :name "Full Name" input-html nil)]
      (is (vector? result))
      (is (= :div (first result)))
      (is (some #(and (vector? %) (= :label (first %))) (rest result)))
      (is (some #(= input-html %) (rest result)))))

  (testing "Form field with validation errors"
    (let [input-html [:input {:type "email" :name "email" :id "email"}]
          errors ["Email is required" "Email format is invalid"]
          result (components/form-field :email "Email Address" input-html errors)]
      (is (vector? result))
      (let [error-elements (filter #(and (vector? %)
                                         (= :div.field-errors (first %)))
                                   (rest result))]
        (is (seq error-elements))))))

(deftest ^:unit button-test
  (testing "Basic button generation"
    (let [result (components/button "Click Me")]
      (is (= [:button {:type "button"} "Click Me"]
             result))))

  (testing "Button with custom attributes"
    (let [result (components/button "Save" {:class "btn-primary" :disabled true})]
      (is (= [:button {:type "button" :class "btn-primary" :disabled true} "Save"]
             result)))))

(deftest ^:unit submit-button-test
  (testing "Submit button generation"
    (let [result (components/submit-button "Submit Form")]
      (is (= [:button {:type "submit" :class "button primary"} "Submit Form"]
             result))))

  (testing "Submit button with styling"
    (let [result (components/submit-button "Create User" {:class "btn-success"})]
      (is (= [:button {:type "submit" :class "btn-success"} "Create User"]
             result)))))

(deftest ^:unit link-button-test
  (testing "Link button generation"
    (let [result (components/link-button "View Details" "/users/123")]
      (is (= [:a {:href "/users/123" :class "button"} "View Details"]
             result))))

  (testing "Link button with custom class"
    (let [result (components/link-button "Edit" "/users/123/edit" {:class "btn-secondary"})]
      (is (= [:a {:href "/users/123/edit" :class "btn-secondary"} "Edit"]
             result)))))

;; =============================================================================
;; Data Display Tests
;; =============================================================================

(deftest ^:unit data-table-test
  (testing "Basic data table generation"
    (let [headers ["ID" "Name" "Email"]
          rows [["1" "John Doe" "john@example.com"]
                ["2" "Jane Smith" "jane@example.com"]]
          result (components/data-table headers rows)]
      (is (vector? result))
      (is (= :table (first result)))
      ; Should contain thead with headers
      (let [thead (first (filter #(and (vector? %) (= :thead (first %))) result))]
        (is thead)
        ; Look for :th elements within thead structure - use tree-seq with coll? to traverse lazy sequences
        (is (some #(and (vector? %) (= :th (first %)) (= "ID" (second %)))
                  (tree-seq coll? seq thead))))
      ; Should contain tbody with rows
      (let [tbody (first (filter #(and (vector? %) (= :tbody (first %))) result))]
        (is tbody)
        ; Look for :td elements within tbody structure - use tree-seq with coll? to traverse lazy sequences
        (is (some #(and (vector? %) (= :td (first %)) (= "John Doe" (second %)))
                  (tree-seq coll? seq tbody))))))

  (testing "Empty table generation"
    (let [headers ["Column 1" "Column 2"]
          rows []
          result (components/data-table headers rows)]
      (is (vector? result))
      (is (= :div.empty-state (first result))))))

(deftest ^:unit table-wrapper-test
  (testing "Wraps table with default wrapper classes"
    (let [table [:table [:tbody [:tr [:td "x"]]]]
          result (components/table-wrapper table)]
      (is (= :div (first result)))
      (is (str/includes? (get-in result [1 :class]) "table-wrapper"))
      (is (= table (nth result 2)))))

  (testing "Accepts options map and appends children"
    (let [table [:table [:tbody [:tr [:td "x"]]]]
          pagination [:div.pagination "Page 1"]
          result (components/table-wrapper table {:id "audit-table-wrap"} pagination)]
      (is (= :div (first result)))
      (is (= "audit-table-wrap" (get-in result [1 :id])))
      (is (= table (nth result 2)))
      (is (= pagination (nth result 3)))))

  (testing "Accepts children without options map"
    (let [table [:table [:tbody [:tr [:td "x"]]]]
          pagination [:div.pagination "Page 1"]
          result (components/table-wrapper table pagination)]
      (is (= :div (first result)))
      (is (= table (nth result 2)))
      (is (= pagination (nth result 3))))))

;; =============================================================================
;; Message Components Tests
;; =============================================================================

(deftest ^:unit success-message-test
  (testing "Success message generation"
    (let [result (components/success-message "Operation completed successfully")]
      (is (vector? result))
      (is (= :div (first result)))
      (is (string? (get-in result [1 :class])))
      (is (str/includes? (get-in result [1 :class]) "success"))
      (is (= "Operation completed successfully" (nth result 2)))))

  (testing "Success message with custom class"
    (let [result (components/success-message "Saved!" {:class "alert-success custom"})]
      (is (vector? result))
      (is (str/includes? (get-in result [1 :class]) "custom")))))

(deftest ^:unit error-message-test
  (testing "Error message generation"
    (let [result (components/error-message "Something went wrong")]
      (is (vector? result))
      (is (= :div (first result)))
      (is (string? (get-in result [1 :class])))
      (is (str/includes? (get-in result [1 :class]) "error"))
      (is (= "Something went wrong" (nth result 2))))))

(deftest ^:unit info-message-test
  (testing "Info message generation"
    (let [result (components/info-message "Please note this information")]
      (is (vector? result))
      (is (= :div (first result)))
      (is (string? (get-in result [1 :class])))
      (is (str/includes? (get-in result [1 :class]) "info"))
      (is (= "Please note this information" (nth result 2))))))

(deftest ^:unit validation-errors-test
  (testing "Validation errors list generation"
    (let [errors ["Name is required" "Email format is invalid" "Password too short"]
          result (components/validation-errors errors)]
      (is (vector? result))
      (is (= :div.validation-errors (first result)))
      ; Should contain a heading and a ul
      (let [ul-element (first (filter #(and (vector? %) (= :ul (first %))) result))]
        (is ul-element)
        ; The ul element is [:ul (lazy-seq-of-lis)] - need to get the lazy seq and realize it
        (let [li-sequence (second ul-element) ; Get the lazy sequence
              list-items (vec li-sequence)] ; Realize it to a vector
          (is (= 3 (count list-items)))
          (is (some #(= "Name is required" (second %)) list-items))))))

  (testing "Empty validation errors"
    (let [result (components/validation-errors [])]
      (is (nil? result))))

  (testing "Nil validation errors"
    (let [result (components/validation-errors nil)]
      (is (nil? result)))))

;; =============================================================================
;; HTML Rendering Tests
;; =============================================================================

(deftest ^:unit render-html-test
  (testing "Hiccup to HTML string conversion"
    (let [hiccup [:div {:class "container"} [:p "Hello World"]]
          result (components/render-html hiccup)]
      (is (string? result))
      (is (str/includes? result "<div"))
      (is (str/includes? result "class=\"container\""))
      (is (str/includes? result "<p>Hello World</p>"))))

  (testing "Complex Hiccup structure rendering"
    (let [hiccup [:form {:method "post" :action "/submit"}
                  [:input {:type "text" :name "name"}]
                  [:button {:type "submit"} "Submit"]]
          result (components/render-html hiccup)]
      (is (string? result))
      (is (str/includes? result "<form"))
      (is (str/includes? result "method=\"post\""))
      (is (str/includes? result "<input"))
      (is (str/includes? result "<button")))))

;; =============================================================================
;; HTMX Integration Tests
;; =============================================================================

(deftest ^:unit htmx-attrs-test
  (testing "HTMX attributes extraction"
    (let [opts {:hx-get "/api/users" :hx-trigger "click" :class "btn"}
          result (components/htmx-attrs opts)]
      (is (map? result))
      (is (= "/api/users" (:hx-get result)))
      (is (= "click" (:hx-trigger result)))
      (is (not (contains? result :class))))) ; non-htmx attrs should be filtered out

  (testing "No HTMX attributes"
    (let [opts {:class "btn" :id "submit-btn"}
          result (components/htmx-attrs opts)]
      (is (map? result))
      (is (empty? result))))

  (testing "Mixed HTMX and regular attributes"
    (let [opts {:hx-post "/api/create" :hx-swap "outerHTML" :disabled true :name "form"}
          result (components/htmx-attrs opts)]
      (is (map? result))
      (is (= "/api/create" (:hx-post result)))
      (is (= "outerHTML" (:hx-swap result)))
      (is (not (contains? result :disabled)))
      (is (not (contains? result :name))))))
