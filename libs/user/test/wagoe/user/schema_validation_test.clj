(ns wagoe.user.schema-validation-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [wagoe.user.schema :as schema]))

(def valid-user
  {:id (java.util.UUID/randomUUID)
   :email "test@example.com"
   :name "Test User"
   :role :user
   :active true
   :user-id (java.util.UUID/randomUUID)
   :created-at (java.time.Instant/now)})

(deftest ^:unit validate-user-test
  (testing "validates complete valid user"
    (is (true? (schema/validate-user valid-user))))

  (testing "fails on missing required field"
    (is (false? (schema/validate-user (dissoc valid-user :email)))))

  (testing "fails on type mismatch"
    (is (false? (schema/validate-user (assoc valid-user :active "true")))))

  (testing "passes with optional fields absent"
    (is (true? (schema/validate-user (dissoc valid-user :login-count)))))

  (testing "passes with optional fields present"
    (is (true? (schema/validate-user (assoc valid-user
                                            :login-count 5
                                            :last-login (java.time.Instant/now)
                                            :date-format :us
                                            :time-format :12h
                                            :avatar-url "https://example.com/avatar.jpg"
                                            :updated-at (java.time.Instant/now)
                                            :deleted-at nil))))))
