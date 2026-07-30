(ns wagoe.i18n.shell.render-test
  "Unit tests for Hiccup marker resolution and rendering."
  (:require [wagoe.i18n.shell.render :as sut]
            [clojure.test :refer [deftest is testing]]))

(def ^:private t-fn
  (fn
    ([key] (case key
             :user/sign-in "Sign in"
             :user/title   "Hello"
             :user/missing (str (namespace key) "/" (name key))
             (str (namespace key) "/" (name key))))
    ([key params] (case key
                    :user/greeting (str "Hello " (:name params))
                    (str (namespace key) "/" (name key))))
    ([key _params n] (case key
                       :user/items (if (= 1 n) "1 item" (str n " items"))
                       (str (namespace key) "/" (name key))))))

;; =============================================================================
;; resolve-markers
;; =============================================================================

(deftest ^:unit resolve-markers-simple-test
  (testing "resolves [:t :key] in child position"
    (let [result (sut/resolve-markers [:p [:t :user/sign-in]] t-fn)]
      (is (= [:p "Sign in"] result))))

  (testing "resolves [:t :key] as root node"
    (let [result (sut/resolve-markers [:t :user/title] t-fn)]
      (is (= "Hello" result))))

  (testing "resolves multiple markers in same element"
    (let [result (sut/resolve-markers [:div [:t :user/sign-in] " | " [:t :user/title]] t-fn)]
      (is (= [:div "Sign in" " | " "Hello"] result)))))

(deftest ^:unit resolve-markers-params-test
  (testing "resolves [:t :key params] with interpolation"
    (let [result (sut/resolve-markers [:t :user/greeting {:name "Alice"}] t-fn)]
      (is (= "Hello Alice" result)))))

(deftest ^:unit resolve-markers-plural-test
  (testing "resolves [:t :key params n] with plural"
    (let [result (sut/resolve-markers [:t :user/items {:n 5} 5] t-fn)]
      (is (= "5 items" result))))

  (testing "resolves [:t :key nil 1] with nil params and count"
    (let [result (sut/resolve-markers [:t :user/items nil 1] t-fn)]
      (is (= "1 item" result)))))

(deftest ^:unit resolve-markers-unknown-key-test
  (testing "unknown key passes through as string, no exception"
    (let [result (sut/resolve-markers [:t :user/missing] t-fn)]
      (is (= "user/missing" result)))))

(deftest ^:unit resolve-markers-nested-test
  (testing "resolves markers at arbitrary nesting depth"
    (let [result (sut/resolve-markers
                  [:div [:section [:p [:t :user/sign-in]]]]
                  t-fn)]
      (is (= [:div [:section [:p "Sign in"]]] result))))

  (testing "non-marker vectors are left untouched"
    (let [result (sut/resolve-markers [:div [:p "Static text"]] t-fn)]
      (is (= [:div [:p "Static text"]] result)))))

(deftest ^:unit dev-mode-test
  (testing "dev mode wraps resolved marker in [:span {:data-i18n ...}]"
    (let [result (sut/resolve-markers [:t :user/sign-in] t-fn {:dev? true})]
      (is (= [:span {:data-i18n ":user/sign-in"} "Sign in"] result))))

  (testing "dev mode is disabled by default"
    (let [result (sut/resolve-markers [:t :user/sign-in] t-fn)]
      (is (= "Sign in" result)))))

;; =============================================================================
;; render
;; =============================================================================

(deftest ^:unit render-test
  (testing "render returns HTML string"
    (let [html (sut/render [:div [:t :user/sign-in]] t-fn)]
      (is (string? html))
      (is (.contains html "Sign in"))))

  (testing "render passes through string input unchanged"
    (is (= "already-html" (sut/render "already-html" t-fn))))

  (testing "render resolves markers before converting to HTML"
    (let [html (sut/render [:p [:t :user/title]] t-fn)]
      (is (.contains html "<p>")
          "should contain p tag")
      (is (.contains html "Hello")
          "should contain translated content"))))
