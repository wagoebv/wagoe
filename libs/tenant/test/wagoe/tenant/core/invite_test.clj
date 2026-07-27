(ns wagoe.tenant.core.invite-test
  (:require [wagoe.tenant.core.invite :as sut]
            [clojure.test :refer [deftest is testing]])
  (:import (java.time Instant)
           (java.util UUID)))

^{:kaocha.testable/meta {:unit true :tenant true}}

(def invite-id (UUID/fromString "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
(def tenant-id (UUID/fromString "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
(def user-id (UUID/fromString "cccccccc-cccc-cccc-cccc-cccccccccccc"))
(def now (Instant/parse "2026-04-10T12:00:00Z"))
(def later (Instant/parse "2026-04-11T12:00:00Z"))
(def expiry (Instant/parse "2026-04-17T12:00:00Z"))

(deftest ^:unit prepare-invite-test
  (testing "creates a pending invite with explicit id and normalized email"
    (let [invite (sut/prepare-invite* {:invite-id invite-id
                                       :tenant-id tenant-id
                                       :email " Contractor@Example.NL "
                                       :role :contractor
                                       :token-hash "hashed-token"
                                       :expires-at expiry
                                       :metadata {:source :test}}
                                      now)]
      (is (= invite-id (:id invite)))
      (is (= tenant-id (:tenant-id invite)))
      (is (= "contractor@example.nl" (:email invite)))
      (is (= :contractor (:role invite)))
      (is (= :pending (:status invite)))
      (is (= "hashed-token" (:token-hash invite)))
      (is (= expiry (:expires-at invite)))
      (is (= {:source :test} (:metadata invite)))
      (is (= now (:created-at invite)))
      (is (nil? (:updated-at invite))))))

(deftest ^:unit expired-test
  (testing "returns true when invite expiry is before now"
    (is (true? (sut/expired? {:expires-at now} later))))

  (testing "returns false when invite expiry is equal to now"
    (is (false? (sut/expired? {:expires-at now} now))))

  (testing "returns false when invite has no expiry"
    (is (nil? (sut/expired? {} now)))))

(deftest ^:unit accept-invite-test
  (testing "transitions invite to accepted"
    (let [invite {:id invite-id :status :pending}
          accepted (sut/accept-invite invite user-id later)]
      (is (= :accepted (:status accepted)))
      (is (= user-id (:accepted-by-user-id accepted)))
      (is (= later (:accepted-at accepted)))
      (is (= later (:updated-at accepted))))))

(deftest ^:unit revoke-invite-test
  (testing "transitions invite to revoked"
    (let [invite {:id invite-id :status :pending}
          revoked (sut/revoke-invite invite later)]
      (is (= :revoked (:status revoked)))
      (is (= later (:revoked-at revoked)))
      (is (= later (:updated-at revoked))))))
