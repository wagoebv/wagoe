(ns wagoe.user.shell.identity-repository-contract-test
  "user_identities round trips on the engine WAGOE_TEST_DB names."
  (:require [clojure.test :refer [deftest is testing]]
            [support.test-db :as test-db]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.identity-persistence :as sut]
            [wagoe.user.shell.persistence :as persistence])
  (:import (java.time Instant)
           (java.time.temporal ChronoUnit)))

(deftest ^:contract identity-links-round-trip
  (test-db/with-db "user_identities"
    (fn [ctx]
      (persistence/initialize-user-schema! ctx)
      ;; Twice: boot and the migration both run it.
      (persistence/initialize-user-schema! ctx)
      (let [users (persistence/create-user-repository ctx)
            repo  (sut/create-identity-repository ctx)
            user  (ports/create-user users {:email "ada@example.com" :name "Ada" :role :user
                                            :active true :password-hash "x"})
            link  {:user-id (:id user) :provider "google" :issuer "https://accounts.google.com"
                   :subject "1234567890" :email "ada@example.com"}]
        (ports/link-identity repo link)
        (is (= link (select-keys (ports/find-identity repo "https://accounts.google.com" "1234567890")
                                 (keys link))))
        (is (nil? (ports/find-identity repo "https://accounts.google.com" "other")))
        (testing "(issuer, subject) is unique"
          (is (thrown? Exception (ports/link-identity repo (assoc link :user-id (random-uuid))))))
        (testing "last login"
          (let [at (.truncatedTo (Instant/now) ChronoUnit/SECONDS)]
            (is (true? (ports/touch-identity repo (:issuer link) (:subject link) at)))
            (is (= at (:last-login-at (first (ports/find-identities-by-user repo (:id user))))))))
        (testing "erasing the user erases the link"
          (ports/hard-delete-user users (:id user))
          (is (empty? (ports/find-identities-by-user repo (:id user)))))
        (testing "unlink"
          (ports/link-identity repo link)
          (is (true? (ports/unlink-identity repo (:issuer link) (:subject link))))
          (is (nil? (ports/find-identity repo (:issuer link) (:subject link)))))))))
