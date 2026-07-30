(ns wagoe.core.utils.type-conversion-test
  "Unit tests for wagoe.core.utils.type-conversion namespace."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.core.utils.type-conversion :as type-conversion])
  (:import [java.util UUID]
           [java.time Instant]))

(deftest ^:unit uuid->string-test
  (testing "UUID to string conversion"
    (let [uuid (UUID/fromString "123e4567-e89b-12d3-a456-426614174000")
          result (type-conversion/uuid->string uuid)]
      (is (= "123e4567-e89b-12d3-a456-426614174000" result)))
    (is (nil? (type-conversion/uuid->string nil)))))

(deftest ^:unit string->uuid-test
  (testing "String to UUID conversion"
    (let [uuid-str "123e4567-e89b-12d3-a456-426614174000"
          result (type-conversion/string->uuid uuid-str)]
      (is (instance? UUID result))
      (is (= uuid-str (.toString result))))
    (is (nil? (type-conversion/string->uuid "not-a-uuid")))
    (is (nil? (type-conversion/string->uuid nil)))
    (is (nil? (type-conversion/string->uuid "")))))

(deftest ^:unit instant->string-test
  (testing "Instant to string conversion"
    (let [instant (Instant/parse "2023-12-25T14:30:00.123Z")
          result (type-conversion/instant->string instant)]
      (is (= "2023-12-25T14:30:00.123Z" result)))
    (is (nil? (type-conversion/instant->string nil)))))

(deftest ^:unit string->instant-test
  (testing "String to Instant conversion"
    (let [iso-string "2023-12-25T14:30:00.123Z"
          result (type-conversion/string->instant iso-string)]
      (is (instance? Instant result))
      (is (= iso-string (.toString result))))
    (is (nil? (type-conversion/string->instant "not-a-timestamp")))
    (is (nil? (type-conversion/string->instant nil)))
    (is (nil? (type-conversion/string->instant "")))))

(deftest ^:unit keyword->string-test
  (testing "Keyword to string conversion"
    (is (= "admin" (type-conversion/keyword->string :admin)))
    (is (nil? (type-conversion/keyword->string nil)))))

(deftest ^:unit string->keyword-test
  (testing "String to keyword conversion"
    (is (= :admin (type-conversion/string->keyword "admin")))
    (is (nil? (type-conversion/string->keyword nil)))
    (is (nil? (type-conversion/string->keyword "")))))

(deftest ^:unit boolean->int-test
  (testing "Boolean to integer conversion"
    (is (= 1 (type-conversion/boolean->int true)))
    (is (= 0 (type-conversion/boolean->int false)))
    (is (= 0 (type-conversion/boolean->int nil)))))

(deftest ^:unit int->boolean-test
  (testing "Integer to boolean conversion"
    (is (= true (type-conversion/int->boolean 1)))
    (is (= false (type-conversion/int->boolean 0)))
    (is (= false (type-conversion/int->boolean 42)))
    (is (= false (type-conversion/int->boolean -1)))
    (is (= false (type-conversion/int->boolean nil)))))

(deftest ^:unit case-conversion-test
  (testing "Case conversions"
    (let [snake-map {:user_id "123" :created_at "2023-12-25"}
          kebab-map {:user-id "123" :created-at "2023-12-25"}]
      (is (= kebab-map (type-conversion/snake-case->kebab-case snake-map)))
      (is (= snake-map (type-conversion/kebab-case->snake-case kebab-map)))
      (is (= {} (type-conversion/snake-case->kebab-case {})))
      (is (nil? (type-conversion/snake-case->kebab-case nil))))))
