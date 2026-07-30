(ns wagoe.i18n.shell.catalogue-test
  "Contract tests for the catalogue loader and MapCatalogue."
  (:require [wagoe.i18n.shell.catalogue :as sut]
            [wagoe.i18n.ports :as ports]
            [clojure.test :refer [deftest is testing]]))

;; =============================================================================
;; load-catalogue
;; =============================================================================

(deftest ^:contract load-catalogue-test
  (testing "load-catalogue returns map with :en key"
    (let [cat (sut/load-catalogue "wagoe/i18n/translations")]
      (is (map? cat))
      (is (contains? cat :en))))

  (testing "load-catalogue returns map with :nl key"
    (let [cat (sut/load-catalogue "wagoe/i18n/translations")]
      (is (contains? cat :nl))))

  (testing "loaded catalogue has keyword keys in nested maps"
    (let [cat (sut/load-catalogue "wagoe/i18n/translations")]
      (is (keyword? (first (keys (:en cat)))))))

  (testing "skips locales that have no EDN file on classpath"
    (let [cat (sut/load-catalogue "wagoe/i18n/translations" [:en :zz])]
      (is (contains? cat :en))
      (is (not (contains? cat :zz)))))

  (testing "merges multiple catalogue paths with later paths overriding earlier ones"
    (let [cat (sut/load-catalogue ["wagoe/i18n/translations"
                                   "wagoe/i18n/test_overrides"])]
      (is (= "Continue" (get-in cat [:en :user/button-signin])))
      (is (= "Doorgaan" (get-in cat [:nl :user/button-signin])))
      (is (= "Hello from override" (get-in cat [:en :zzpguard/test-key]))))))

;; =============================================================================
;; MapCatalogue (ICatalogue protocol)
;; =============================================================================

(deftest ^:contract map-catalogue-lookup-test
  (let [data {:en {:user/sign-in "Sign in"
                   :user/welcome {:one "Hello {name}" :many "Hello all"}}
              :nl {:user/sign-in "Aanmelden"}}
        cat  (sut/create-map-catalogue data)]

    (testing "lookup returns translation for valid locale+key"
      (is (= "Sign in" (ports/lookup cat :en :user/sign-in))))

    (testing "lookup returns Dutch translation"
      (is (= "Aanmelden" (ports/lookup cat :nl :user/sign-in))))

    (testing "lookup returns nil for missing key"
      (is (nil? (ports/lookup cat :en :user/missing))))

    (testing "lookup returns nil for missing locale"
      (is (nil? (ports/lookup cat :fr :user/sign-in))))

    (testing "lookup returns plural map for plural entry"
      (is (map? (ports/lookup cat :en :user/welcome))))))

(deftest ^:contract map-catalogue-available-locales-test
  (let [data {:en {:a "A"} :nl {:b "B"} :de {:c "C"}}
        cat  (sut/create-map-catalogue data)]

    (testing "available-locales returns set of all loaded locales"
      (is (= #{:en :nl :de} (ports/available-locales cat))))))

;; =============================================================================
;; Round-trip: load → lookup
;; =============================================================================

(deftest ^:contract round-trip-test
  (testing "round-trip: load en.edn, lookup known key, get expected string"
    (let [data (sut/load-catalogue "wagoe/i18n/translations")
          cat  (sut/create-map-catalogue data)]
      (is (= "Sign In" (ports/lookup cat :en :user/button-signin)))
      (is (= "Inloggen" (ports/lookup cat :nl :user/button-signin))))))
