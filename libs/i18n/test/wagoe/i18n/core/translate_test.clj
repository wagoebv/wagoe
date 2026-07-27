(ns wagoe.i18n.core.translate-test
  "Unit tests for the pure translation functions."
  (:require [wagoe.i18n.core.translate :as sut]
            [wagoe.i18n.shell.catalogue :as catalogue]
            [clojure.test :refer [deftest is testing]]))

(def ^:private catalogue
  {:en {:user/sign-in  "Sign in"
        :user/greeting {:one "Hello {name}" :many "Hello everyone"}
        :user/items    {:zero "No items" :one "{n} item" :many "{n} items"}
        :user/welcome  "Welcome, {name}!"}
   :nl {:user/sign-in  "Aanmelden"
        :user/greeting {:one "Hallo {name}" :many "Hallo iedereen"}
        :user/welcome  "Welkom, {name}!"}})

;; =============================================================================
;; Simple lookup
;; =============================================================================

(deftest ^:unit simple-lookup-test
  (testing "returns English translation for :en locale"
    (is (= "Sign in" (sut/t catalogue [:en] :user/sign-in))))

  (testing "returns Dutch translation for :nl locale"
    (is (= "Aanmelden" (sut/t catalogue [:nl] :user/sign-in))))

  (testing "falls back through locale chain when first locale missing a key"
    (let [cat {:en {:user/only-english "English only"}
               :nl {}}]
      (is (= "English only" (sut/t cat [:nl :en] :user/only-english)))))

  (testing "nil entries in the locale chain are skipped"
    (is (= "Sign in" (sut/t catalogue [nil :en] :user/sign-in)))))

;; =============================================================================
;; Missing key fallback
;; =============================================================================

(deftest ^:unit missing-key-fallback-test
  (testing "returns string representation of key when not found in any locale"
    (is (= "user/missing-key" (sut/t catalogue [:en] :user/missing-key))))

  (testing "key without namespace uses just the name"
    (is (= "bare-key" (sut/t catalogue [:en] :bare-key))))

  (testing "empty locale chain falls back to key string"
    (is (= "user/sign-in" (sut/t catalogue [] :user/sign-in)))))

;; =============================================================================
;; Interpolation
;; =============================================================================

(deftest ^:unit interpolation-test
  (testing "interpolates {param} placeholders"
    (is (= "Welcome, Alice!" (sut/t catalogue [:en] :user/welcome {:name "Alice"}))))

  (testing "interpolates Dutch translation"
    (is (= "Welkom, Bob!" (sut/t catalogue [:nl] :user/welcome {:name "Bob"}))))

  (testing "ignores extra params not in template"
    (is (= "Welcome, Alice!" (sut/t catalogue [:en] :user/welcome {:name "Alice" :extra "ignored"}))))

  (testing "leaves placeholder if param not provided"
    (is (= "Welcome, {name}!" (sut/t catalogue [:en] :user/welcome {})))))

;; =============================================================================
;; Plural forms
;; =============================================================================

(deftest ^:unit plural-forms-test
  (testing "n=1 selects :one form"
    (is (= "1 item" (sut/t catalogue [:en] :user/items {:n 1} 1))))

  (testing "n=5 selects :many form"
    (is (= "5 items" (sut/t catalogue [:en] :user/items {:n 5} 5))))

  (testing "n=0 selects :zero form"
    (is (= "No items" (sut/t catalogue [:en] :user/items {:n 0} 0))))

  (testing "falls back to :many when :one missing but n=1"
    (let [cat {:en {:greeting {:many "Hello"}}}]
      (is (= "Hello" (sut/t cat [:en] :greeting {} 1)))))

  (testing "plural form for Dutch greeting (n=3)"
    (is (= "Hallo iedereen" (sut/t catalogue [:nl] :user/greeting {} 3))))

  (testing "singular form for Dutch greeting (n=1)"
    (is (= "Hallo Alice" (sut/t catalogue [:nl] :user/greeting {:name "Alice"} 1)))))

;; =============================================================================
;; Locale chain fallback
;; =============================================================================

(deftest ^:unit locale-chain-fallback-test
  (testing "uses user locale first when available"
    (is (= "Aanmelden" (sut/t catalogue [:nl :en] :user/sign-in))))

  (testing "falls back to English when Dutch key missing"
    (let [cat {:en {:user/sign-in "Sign in"} :nl {}}]
      (is (= "Sign in" (sut/t cat [:nl :en] :user/sign-in)))))

  (testing "multiple fallback levels work correctly"
    (let [cat {:en {:key "English"} :fr {} :nl {}}]
      (is (= "English" (sut/t cat [:nl :fr :en] :key))))))

(deftest ^:unit catalogue-protocol-test
  (testing "MapCatalogue records are resolved via the ICatalogue protocol"
    (let [cat (catalogue/create-map-catalogue catalogue)]
      (is (= "Aanmelden" (sut/t cat [:nl :en] :user/sign-in)))
      (is (= "Sign in" (sut/t cat [:fr :en] :user/sign-in))))))
