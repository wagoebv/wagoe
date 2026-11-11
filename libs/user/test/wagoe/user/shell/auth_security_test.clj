(ns wagoe.user.shell.auth-security-test
  "Security tests for JWT secret validation, algorithm pinning and the bcrypt work factor."
  (:require [wagoe.user.shell.auth :as auth]
            [wagoe.user.shell.mfa-crypto :as mfa-crypto]
            [buddy.sign.jwt :as jwt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.util UUID)))

(defn- refusal [secret]
  (try (#'auth/validate-jwt-secret* secret) nil
       (catch clojure.lang.ExceptionInfo e e)))

(deftest ^:unit ^:security jwt-secret-validation-test
  (testing "a missing, blank or short secret is refused with WGE-103 (BOU-944)"
    (doseq [secret [nil "" "too-short-secret"]
            :let [e (refusal secret)]]
      (is (str/starts-with? (str (some-> e ex-message)) "WGE-103: ") (pr-str secret))
      (is (= "WGE-103" (:wagoe/error-code (ex-data e))))))
  (testing "a >= 32 character secret is accepted"
    (let [secret "this-secret-is-exactly-32-chars!"]
      (is (= 32 (count secret)))
      (is (= secret (#'auth/validate-jwt-secret* secret))))))

(deftest ^:unit ^:security jwt-algorithm-pinning-test
  ;; Relies on JWT_SECRET being set for the test run (>= 32 chars).
  (let [user {:id (UUID/randomUUID) :email "user@example.com" :role :user}]
    (testing "a token this service issued round-trips"
      (let [token (auth/create-jwt-token user 1)]
        (is (:valid? (auth/validate-jwt-token token)))))

    (testing "a token signed with a DIFFERENT algorithm is rejected (alg pinned)"
      (let [other (jwt/sign {:sub (str (:id user))}
                            "a-different-secret-at-least-32-chars"
                            {:alg :hs512})]
        (is (false? (:valid? (auth/validate-jwt-token other))))))

    (testing "a token signed with the right algorithm but a WRONG key is rejected"
      (let [forged (jwt/sign {:sub (str (:id user))}
                             "a-wrong-hs256-secret-at-least-32c!"
                             {:alg :hs256})]
        (is (false? (:valid? (auth/validate-jwt-token forged))))))))

(deftest ^:unit ^:security passwords-hash-at-work-factor-12
  ;; BOU-943. The cost is the third field of buddy-hashers' encoding.
  (let [[alg _salt cost :as hash] (str/split (auth/hash-password "correct horse") #"\$")]
    (is (= "bcrypt+sha512" alg))
    (is (= "12" cost))
    (is (auth/verify-password "correct horse" (str/join "$" hash)))
    (is (= "12" (nth (str/split (mfa-crypto/hash-backup-code "ab12-cd34") #"\$") 2))
        "backup codes too")))
