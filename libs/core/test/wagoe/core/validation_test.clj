(ns wagoe.core.validation-test
  "What this namespace is for: compiling a Malli schema once and reusing it.

   Compiling a validator is roughly ten times the cost of running one, and the
   whole namespace exists to pay that once. Nothing tested it — the tests here
   covered a second copy of the validation-result API that lived alongside it
   and that nothing called (BOU-323)."
  (:require [wagoe.core.validation :as validation]
            [clojure.test :refer [deftest is testing]]
            [malli.transform :as mt]))

(def TestUserSchema
  [:map
   [:name :string]
   [:age :int]])

(deftest ^:unit validates-and-explains
  (is (true? (validation/valid? TestUserSchema {:name "Ada" :age 36})))
  (is (false? (validation/valid? TestUserSchema {:name "Ada" :age "36"})))

  (testing "explain names the field that was wrong"
    (let [errors (:errors (validation/explain TestUserSchema {:name "Ada" :age "36"}))]
      (is (= [:age] (:in (first errors)))))))

(deftest ^:unit compiled-schemas-are-cached
  ;; The reason for the memoize. Without it every call recompiles, and the
  ;; namespace is doing nothing that malli.core does not already do.
  (is (identical? (validation/validator TestUserSchema)
                  (validation/validator TestUserSchema)))
  (is (identical? (validation/explainer TestUserSchema)
                  (validation/explainer TestUserSchema)))

  (testing "the decoder caches on both schema and transformer"
    (is (identical? (validation/decoder TestUserSchema mt/string-transformer)
                    (validation/decoder TestUserSchema mt/string-transformer)))
    (is (not (identical? (validation/decoder TestUserSchema mt/string-transformer)
                         (validation/decoder TestUserSchema mt/json-transformer)))
        "a different transformer is a different decoder"))

  (testing "and a decoded value still validates"
    (is (true? (validation/valid?
                TestUserSchema
                ((validation/decoder TestUserSchema mt/string-transformer)
                 {:name "Ada" :age "36"}))))))
