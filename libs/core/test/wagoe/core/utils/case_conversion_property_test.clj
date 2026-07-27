(ns wagoe.core.utils.case-conversion-property-test
  "Property-based tests for the snake_case <-> kebab-case boundary conversions.

   These guard AGENTS.md Pitfall #1 (the historical `:password_hash` auth bug):
   the persistence boundary converts kebab-case internal keys to snake_case for
   the DB and back again, and any drift in that round-trip silently produces nil
   lookups. A `defspec` over generated keys proves the round-trip is identity for
   the whole key space the DB boundary can produce, not just the hand-picked
   examples in the unit tests.

   The conversions are blind `_`<->`-` swaps, so round-trip identity holds for
   any key that does not already contain the opposite separator. The generators
   below therefore emit snake_case keys (no `-`) and kebab-case keys (no `_`)."
  (:require [wagoe.core.utils.case-conversion :as cc]
            [clojure.string :as str]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

;; =============================================================================
;; Generators
;; =============================================================================

(def ^:private segment-gen
  "A single lowercase-alphanumeric key segment, e.g. \"user\", \"id\", \"mfa2\"."
  (gen/fmap str/join
            (gen/vector (gen/frequency [[80 (gen/fmap char (gen/choose 97 122))]   ; a-z
                                        [20 (gen/fmap char (gen/choose 48 57))]])   ; 0-9
                        1 8)))

(def ^:private snake-key-gen
  "snake_case keyword key: 1-4 segments joined by `_` (never contains `-`)."
  (gen/fmap (fn [segs] (keyword (str/join "_" segs)))
            (gen/vector segment-gen 1 4)))

(def ^:private kebab-key-gen
  "kebab-case keyword key: 1-4 segments joined by `-` (never contains `_`)."
  (gen/fmap (fn [segs] (keyword (str/join "-" segs)))
            (gen/vector segment-gen 1 4)))

(def ^:private value-gen
  "Values for the maps under test. Deliberately `=`-stable: the conversions only
   touch keys, so any value type works, but `gen/any-printable` can emit `##NaN`
   (and `(= ##NaN ##NaN)` is false), which would make identity assertions flaky
   for reasons unrelated to case conversion. These types are all self-equal."
  (gen/one-of [gen/string-ascii gen/small-integer gen/keyword
               gen/boolean (gen/return nil)]))

(def ^:private snake-map-gen
  "Map with snake_case keyword keys and `=`-stable values."
  (gen/map snake-key-gen value-gen {:max-elements 12}))

(def ^:private kebab-map-gen
  "Map with kebab-case keyword keys and `=`-stable values."
  (gen/map kebab-key-gen value-gen {:max-elements 12}))

;; =============================================================================
;; Round-trip identity — the documented auth-bug class
;; =============================================================================

(defspec ^:property snake->kebab->snake-map-is-identity 100
  (prop/for-all [m snake-map-gen]
                (= m (cc/kebab-case->snake-case-map (cc/snake-case->kebab-case-map m)))))

(defspec ^:property kebab->snake->kebab-map-is-identity 100
  (prop/for-all [m kebab-map-gen]
                (= m (cc/snake-case->kebab-case-map (cc/kebab-case->snake-case-map m)))))

(defspec ^:property snake->kebab->snake-string-is-identity 100
  (prop/for-all [segs (gen/vector segment-gen 1 4)]
                (let [s (str/join "_" segs)]
                  (= s (cc/kebab-case->snake-case-string (cc/snake-case->kebab-case-string s))))))

(defspec ^:property kebab->snake->kebab-string-is-identity 100
  (prop/for-all [segs (gen/vector segment-gen 1 4)]
                (let [s (str/join "-" segs)]
                  (= s (cc/snake-case->kebab-case-string (cc/kebab-case->snake-case-string s))))))

;; =============================================================================
;; Structure-preservation invariants
;; =============================================================================

(defspec ^:property map-conversion-preserves-values-and-count 100
  (prop/for-all [m snake-map-gen]
                (let [converted (cc/snake-case->kebab-case-map m)]
                  (and (= (count m) (count converted))
                       ;; value multiset is carried across untouched (order-insensitive)
                       (= (frequencies (vals m)) (frequencies (vals converted)))
                       ;; no snake residue leaks into an internal (kebab) key
                       (every? (fn [k] (not (str/includes? (name k) "_"))) (keys converted))))))
