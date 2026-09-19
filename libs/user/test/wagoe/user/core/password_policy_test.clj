(ns wagoe.user.core.password-policy-test
  "BOU-388: one policy, two spellings. `validate-password-constraint` read
   `:require-numbers?` — what config.edn writes — and `meets-password-policy?`
   read `:require-numbers`, which nothing writes. Configuration reached one
   validator and not the other."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.user.core.authentication :as auth]
            [wagoe.user.core.password-policy :as policy]
            [wagoe.user.core.validation :as validation]))

(deftest ^:unit normalize-reads-both-spellings
  (testing "the ?-suffixed spelling config.edn writes"
    (is (false? (:require-numbers? (policy/normalize {:require-numbers? false})))))

  (testing "and the unsuffixed one older callers pass"
    (is (false? (:require-numbers? (policy/normalize {:require-numbers false})))))

  (testing "the ?-suffixed spelling wins when both are present"
    (is (true? (:require-numbers? (policy/normalize {:require-numbers? true
                                                     :require-numbers  false})))))

  (testing "unset flags fall back to the documented defaults"
    (let [p (policy/normalize nil)]
      (is (= 8 (:min-length p)))
      (is (= 255 (:max-length p)))
      (is (true? (:require-numbers? p)))
      (is (false? (:require-uppercase? p)))
      (is (false? (:require-lowercase? p)))
      (is (false? (:require-special-chars? p)))))

  (testing "an explicit nil is not an answer"
    (is (= 8 (:min-length (policy/normalize {:min-length nil}))))))

(deftest ^:unit both-validators-see-the-configured-policy
  ;; The dev config's shape: digits switched off with the ?-suffixed key.
  (let [configured {:require-numbers? false :min-length 8}]
    (testing "meets-password-policy? honours it"
      (is (:valid? (auth/meets-password-policy? "nodigitshere" configured nil))))

    (testing "and so does the constraint validator behind it"
      (is (validation/valid-password? "nodigitshere" {:password-policy configured})))

    (testing "with the flag on, both still refuse"
      (let [strict {:require-numbers? true :min-length 8}]
        (is (not (:valid? (auth/meets-password-policy? "nodigitshere" strict nil))))
        (is (not (validation/valid-password? "nodigitshere"
                                             {:password-policy strict})))))))

(deftest ^:unit a-policy-violation-names-the-rule-it-broke
  ;; The CLI printed "Missing fields: :password" for this, because the error
  ;; carried a field and no code anyone read (BOU-447).
  (let [{:keys [errors]} (validation/validate-comprehensive-user-creation
                          {:email "a@b.com" :name "Alice" :password "alllowercase"
                           :role :user}
                          {:password-policy {:min-length 8 :require-numbers? true}}
                          {})
        password-errors (filter #(= :password (:field %)) errors)]
    (is (seq password-errors))
    (is (every? #(not= :missing-required-field (:code %)) password-errors)
        "a password that was sent and rejected is not a missing field")
    (is (some #(re-find #"at least one number" (:message %)) password-errors))))
