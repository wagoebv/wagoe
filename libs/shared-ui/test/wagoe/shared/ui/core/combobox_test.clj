(ns wagoe.shared.ui.core.combobox-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hiccup2.core :as h]
            [wagoe.shared.ui.core.combobox :as combobox]))

(defn- html [hiccup] (str (h/html hiccup)))

(defn- input-tag [page attr-re]
  (re-find (re-pattern (str "<input[^>]*" attr-re "[^>]*>")) page))

(deftest ^:unit combobox-markup
  (let [page (html (combobox/combobox :parent-id {:url "/opts" :value "42" :label "Acme" :required true}))]
    (testing "a hidden input submits the value"
      (let [hidden (input-tag page "type=\"hidden\"")]
        (is (str/includes? hidden "name=\"parent-id\""))
        (is (str/includes? hidden "value=\"42\""))))
    (testing "the text input is the ARIA combobox, shows the label, and is not submitted"
      (let [text (input-tag page "role=\"combobox\"")]
        (is (str/includes? text "id=\"parent-id\""))
        (is (str/includes? text "value=\"Acme\""))
        (is (str/includes? text "aria-controls=\"parent-id-listbox\""))
        (is (str/includes? text "aria-autocomplete=\"list\""))
        (is (str/includes? text "required"))
        (is (not (str/includes? text "name=")))))
    (testing "typing asks the server, after a delay, for the listbox's options"
      (let [text (input-tag page "role=\"combobox\"")]
        (is (str/includes? text "hx-get=\"/opts\""))
        (is (str/includes? text "delay:300ms"))
        (is (str/includes? text "hx-target=\"#parent-id-listbox\""))))
    (is (re-find #"<ul[^>]*id=\"parent-id-listbox\"[^>]*role=\"listbox\"" page))
    (is (str/includes? page "x-data=\"combobox\""))))

(deftest ^:unit a-readonly-combobox-asks-nothing
  (let [text (input-tag (html (combobox/combobox :p {:url "/opts" :readonly true})) "role=\"combobox\"")]
    (is (str/includes? text "readonly"))
    (is (not (str/includes? text "hx-get")))))

(deftest ^:unit options-markup
  (let [page (html (combobox/options :parent-id [["1" "Acme"] ["2" "Beta & Co"]] "None"))]
    (is (= 2 (count (re-seq #"role=\"option\"" page))))
    (is (re-find #"<li[^>]*data-value=\"1\"[^>]*id=\"parent-id-option-0\"" page))
    (is (str/includes? page "data-label=\"Beta &amp; Co\""))
    (is (str/includes? page "aria-selected=\"false\"")))
  (testing "no items: one option saying so, which cannot be picked"
    (let [page (html (combobox/options :p [] "None"))]
      (is (str/includes? page "aria-disabled=\"true\""))
      (is (str/includes? page "None"))
      (is (not (str/includes? page "data-value"))))))

(deftest ^:unit query-reads-the-typed-text
  (is (= "ac" (combobox/query {:query-params {"q" " ac "}})))
  (is (nil? (combobox/query {:query-params {"q" "  "}})))
  (is (nil? (combobox/query {:query-params {}}))))
