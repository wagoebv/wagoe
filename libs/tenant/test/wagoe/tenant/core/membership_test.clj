(ns wagoe.tenant.core.membership-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.tenant.core.membership :as sut])
  (:import (java.time Instant)
           (java.util UUID)))

^{:kaocha.testable/meta {:unit true :tenant true}}

(def user-id        (UUID/fromString "11111111-1111-1111-1111-111111111111"))
(def tenant-id      (UUID/fromString "22222222-2222-2222-2222-222222222222"))
(def membership-id  (UUID/fromString "33333333-3333-3333-3333-333333333333"))
(def alt-membership-id (UUID/fromString "44444444-4444-4444-4444-444444444444"))
(def now            (Instant/parse "2026-04-10T12:00:00Z"))

;; =============================================================================
;; prepare-invitation
;; =============================================================================

(deftest ^:unit prepare-invitation-test
  (testing "creates a membership map with invited status"
    (let [m (sut/prepare-invitation* membership-id user-id tenant-id :member now)]
      (is (= membership-id (:id m)))
      (is (= tenant-id (:tenant-id m)))
      (is (= user-id (:user-id m)))
      (is (= :member (:role m)))
      (is (= :invited (:status m)))
      (is (= now (:invited-at m)))
      (is (nil? (:accepted-at m)))
      (is (= now (:created-at m)))
      (is (nil? (:updated-at m)))))

  (testing "different injected ids produce different memberships"
    (let [m1 (sut/prepare-invitation* membership-id user-id tenant-id :admin now)
          m2 (sut/prepare-invitation* alt-membership-id user-id tenant-id :admin now)]
      (is (not= (:id m1) (:id m2))))))

(deftest ^:unit prepare-active-membership-test
  (testing "creates an active membership with explicit id"
    (let [m (sut/prepare-active-membership* membership-id user-id tenant-id :admin now)]
      (is (= membership-id (:id m)))
      (is (= :active (:status m)))
      (is (= now (:accepted-at m)))
      (is (= now (:created-at m))))))

;; =============================================================================
;; accept-invitation
;; =============================================================================

(deftest ^:unit accept-invitation-test
  (testing "transitions status to :active and sets accepted-at"
    (let [membership (sut/prepare-invitation* membership-id user-id tenant-id :member now)
          later      (Instant/parse "2099-01-01T00:00:00Z")
          accepted   (sut/accept-invitation membership later)]
      (is (= :active (:status accepted)))
      (is (= later (:accepted-at accepted)))
      (is (= later (:updated-at accepted)))))

  (testing "preserves all other fields"
    (let [membership (sut/prepare-invitation* membership-id user-id tenant-id :admin now)
          accepted   (sut/accept-invitation membership now)]
      (is (= (:id membership) (:id accepted)))
      (is (= (:tenant-id membership) (:tenant-id accepted)))
      (is (= (:user-id membership) (:user-id accepted)))
      (is (= :admin (:role accepted))))))

;; =============================================================================
;; suspend-membership
;; =============================================================================

(deftest ^:unit suspend-membership-test
  (testing "sets status to :suspended"
    (let [m       (-> (sut/prepare-invitation* membership-id user-id tenant-id :member now)
                      (sut/accept-invitation now))
          later   (Instant/parse "2099-06-01T00:00:00Z")
          result  (sut/suspend-membership m later)]
      (is (= :suspended (:status result)))
      (is (= later (:updated-at result))))))

;; =============================================================================
;; revoke-membership
;; =============================================================================

(deftest ^:unit revoke-membership-test
  (testing "sets status to :revoked"
    (let [m      (sut/prepare-invitation* membership-id user-id tenant-id :viewer now)
          result (sut/revoke-membership m now)]
      (is (= :revoked (:status result)))
      (is (= now (:updated-at result))))))

;; =============================================================================
;; update-role
;; =============================================================================

(deftest ^:unit update-role-test
  (testing "updates role and updated-at"
    (let [m      (-> (sut/prepare-invitation* membership-id user-id tenant-id :member now)
                     (sut/accept-invitation now))
          result (sut/update-role m :admin now)]
      (is (= :admin (:role result)))
      (is (= now (:updated-at result)))))

  (testing "preserves status"
    (let [m      (-> (sut/prepare-invitation* membership-id user-id tenant-id :member now)
                     (sut/accept-invitation now))
          result (sut/update-role m :viewer now)]
      (is (= :active (:status result))))))

;; =============================================================================
;; active-member?
;; =============================================================================

(deftest ^:unit active-member-test
  (testing "returns true for :active membership"
    (let [m (-> (sut/prepare-invitation* membership-id user-id tenant-id :member now)
                (sut/accept-invitation now))]
      (is (true? (sut/active-member? m)))))

  (testing "returns false for :invited membership"
    (let [m (sut/prepare-invitation* membership-id user-id tenant-id :member now)]
      (is (false? (sut/active-member? m)))))

  (testing "returns false for :suspended membership"
    (let [m (-> (sut/prepare-invitation* membership-id user-id tenant-id :member now)
                (sut/accept-invitation now)
                (sut/suspend-membership now))]
      (is (false? (sut/active-member? m)))))

  (testing "returns false for :revoked membership"
    (let [m (-> (sut/prepare-invitation* membership-id user-id tenant-id :member now)
                (sut/revoke-membership now))]
      (is (false? (sut/active-member? m))))))

;; =============================================================================
;; has-role?
;; =============================================================================

(deftest ^:unit has-role-test
  (testing "returns true when membership role is in allowed set"
    (let [m (-> (sut/prepare-invitation* membership-id user-id tenant-id :admin now)
                (sut/accept-invitation now))]
      (is (true? (sut/has-role? m #{:admin})))))

  (testing "returns false when membership role is not in allowed set"
    (let [m (-> (sut/prepare-invitation* membership-id user-id tenant-id :viewer now)
                (sut/accept-invitation now))]
      (is (false? (sut/has-role? m #{:admin})))))

  (testing "works with multiple allowed roles"
    (let [m (-> (sut/prepare-invitation* membership-id user-id tenant-id :member now)
                (sut/accept-invitation now))]
      (is (true? (sut/has-role? m #{:admin :member :viewer}))))))
