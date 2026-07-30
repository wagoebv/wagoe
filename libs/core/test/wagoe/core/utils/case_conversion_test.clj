(ns wagoe.core.utils.case-conversion-test
  "Unit tests for wagoe.core.utils.case-conversion namespace."
  (:require [wagoe.core.utils.case-conversion :as case-conversion]
            [clojure.test :refer [deftest is testing]]))

(deftest ^:unit camel-case->kebab-case-map-test
  (testing "camelCase to kebab-case map conversion"
    (testing "Basic camelCase conversion"
      (let [input {:userId "123" :firstName "John" :lastName "Doe"}
            expected {:user-id "123" :first-name "John" :last-name "Doe"}
            result (case-conversion/camel-case->kebab-case-map input)]
        (is (= expected result))))

    (testing "Already kebab-case keys remain unchanged"
      (let [input {:user-id "123" :first-name "John"}
            result (case-conversion/camel-case->kebab-case-map input)]
        (is (= input result))))

    (testing "Mixed case keys"
      (let [input {:userId "123" :user-name "John" :XMLHttpRequest "value"}
            expected {:user-id "123" :user-name "John" :xmlhttp-request "value"}
            result (case-conversion/camel-case->kebab-case-map input)]
        (is (= expected result))))

    (testing "Nil-safe behavior"
      (is (nil? (case-conversion/camel-case->kebab-case-map nil))))

    (testing "Empty map"
      (is (= {} (case-conversion/camel-case->kebab-case-map {}))))))

(deftest ^:unit kebab-case->camel-case-map-test
  (testing "kebab-case to camelCase map conversion"
    (testing "Basic kebab-case conversion"
      (let [input {:user-id "123" :first-name "John" :last-name "Doe"}
            expected {:userId "123" :firstName "John" :lastName "Doe"}
            result (case-conversion/kebab-case->camel-case-map input)]
        (is (= expected result))))

    (testing "Already camelCase keys remain unchanged"
      (let [input {:userId "123" :firstName "John"}
            result (case-conversion/kebab-case->camel-case-map input)]
        (is (= input result))))

    (testing "Multi-dash conversion"
      (let [input {:user-first-name "John" :xml-http-request "value"}
            expected {:userFirstName "John" :xmlHttpRequest "value"}
            result (case-conversion/kebab-case->camel-case-map input)]
        (is (= expected result))))

    (testing "Nil-safe behavior"
      (is (nil? (case-conversion/kebab-case->camel-case-map nil))))

    (testing "Empty map"
      (is (= {} (case-conversion/kebab-case->camel-case-map {}))))))

(deftest ^:unit string-case-conversions-test
  (testing "String case conversions"
    (testing "camelCase to kebab-case string conversion"
      (is (= "user-id" (case-conversion/camel-case->kebab-case-string "userId")))
      (is (= "first-name" (case-conversion/camel-case->kebab-case-string "firstName")))
      (is (= "xmlhttp-request" (case-conversion/camel-case->kebab-case-string "XMLHttpRequest")))
      (is (= "already-kebab" (case-conversion/camel-case->kebab-case-string "already-kebab")))
      (is (nil? (case-conversion/camel-case->kebab-case-string nil))))

    (testing "kebab-case to camelCase string conversion"
      (is (= "userId" (case-conversion/kebab-case->camel-case-string "user-id")))
      (is (= "firstName" (case-conversion/kebab-case->camel-case-string "first-name")))
      (is (= "xmlHttpRequest" (case-conversion/kebab-case->camel-case-string "xml-http-request")))
      (is (= "alreadyCamel" (case-conversion/kebab-case->camel-case-string "alreadyCamel")))
      (is (nil? (case-conversion/kebab-case->camel-case-string nil))))))

(deftest ^:unit deep-transform-keys-test
  (testing "Deep transformation of nested structures"
    (testing "Nested maps"
      (let [input {:userId "123"
                   :userInfo {:firstName "John"
                              :lastName "Doe"
                              :contactInfo {:emailAddress "john@example.com"}}}
            expected {:user-id "123"
                      :user-info {:first-name "John"
                                  :last-name "Doe"
                                  :contact-info {:email-address "john@example.com"}}}
            result (case-conversion/deep-transform-keys
                    case-conversion/camel-case->kebab-case-string
                    input)]
        (is (= expected result))))

    (testing "Maps with vectors"
      (let [input {:userList [{:userId "1" :firstName "John"}
                              {:userId "2" :firstName "Jane"}]}
            expected {:user-list [{:user-id "1" :first-name "John"}
                                  {:user-id "2" :first-name "Jane"}]}
            result (case-conversion/deep-transform-keys
                    case-conversion/camel-case->kebab-case-string
                    input)]
        (is (= expected result))))

    (testing "Maps with lists"
      (let [input {:userData '({:userId "1"} {:userId "2"})}
            expected {:user-data '({:user-id "1"} {:user-id "2"})}
            result (case-conversion/deep-transform-keys
                    case-conversion/camel-case->kebab-case-string
                    input)]
        (is (= expected result))))

    (testing "Non-keyword keys preserved"
      (let [input {"userId" "123" :firstName "John" 1 "numeric-key"}
            expected {"userId" "123" :first-name "John" 1 "numeric-key"}
            result (case-conversion/deep-transform-keys
                    case-conversion/camel-case->kebab-case-string
                    input)]
        (is (= expected result))))

    (testing "Primitive values preserved"
      (let [input {:count 42 :active true :name "test" :data nil}
            expected {:count 42 :active true :name "test" :data nil}
            result (case-conversion/deep-transform-keys
                    case-conversion/camel-case->kebab-case-string
                    input)]
        (is (= expected result))))

    (testing "Empty structures"
      (is (= {} (case-conversion/deep-transform-keys
                 case-conversion/camel-case->kebab-case-string
                 {})))
      (is (= [] (case-conversion/deep-transform-keys
                 case-conversion/camel-case->kebab-case-string
                 [])))
      (is (= '() (case-conversion/deep-transform-keys
                  case-conversion/camel-case->kebab-case-string
                  '()))))))

(deftest ^:unit roundtrip-conversion-test
  (testing "Roundtrip conversions preserve data"
    (let [original {:user-id "123" :first-name "John" :contact-info {:email-address "test"}}]
      (is (= original
             (-> original
                 case-conversion/kebab-case->camel-case-map
                 case-conversion/camel-case->kebab-case-map))))

    (let [original {:userId "123" :firstName "John" :contactInfo {:emailAddress "test"}}]
      (is (= original
             (-> original
                 case-conversion/camel-case->kebab-case-map
                 case-conversion/kebab-case->camel-case-map))))))
