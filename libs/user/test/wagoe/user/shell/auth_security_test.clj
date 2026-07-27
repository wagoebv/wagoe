(ns wagoe.user.shell.auth-security-test
  "Security tests for JWT secret validation and algorithm pinning."
  (:require [wagoe.user.shell.auth :as auth]
            [buddy.sign.jwt :as jwt]
            [clojure.test :refer [deftest is testing]])
  (:import (java.util UUID)))

(deftest ^:unit ^:security jwt-secret-validation-test
  (testing "a missing or blank secret is rejected"
    (is (thrown? clojure.lang.ExceptionInfo (#'auth/validate-jwt-secret* nil)))
    (is (thrown? clojure.lang.ExceptionInfo (#'auth/validate-jwt-secret* ""))))
  (testing "a secret shorter than 32 characters is rejected"
    (is (thrown? clojure.lang.ExceptionInfo (#'auth/validate-jwt-secret* "too-short-secret"))))
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
