(ns wagoe.user.core.user-validation-snapshot-test
  "Snapshot tests for user validation results.
   
   These tests capture the complete validation result structure and ensure
   it remains stable across code changes. Snapshots are stored in EDN format
   under test/snapshots/validation/ for easy review.
   
   To update snapshots:
     UPDATE_SNAPSHOTS=true clojure -M:test:db/h2 --focus wagoe.user.core.user-validation-snapshot-test
   
   See: src/wagoe/shared/core/validation/snapshot.clj for snapshot utilities"
  (:require [clojure.test :refer [deftest testing]]
            [wagoe.core.validation.snapshot-io :as snapshot-io]
            [wagoe.user.core.user :as user-core]
            [support.validation-helpers :as vh])
  (:import (java.util UUID)
           (java.time Instant)))

;; Tag for Phase 3
(alter-meta! *ns* assoc :kaocha/tags [:phase3 :snapshot])

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def fixed-user-id
  (UUID/fromString "00000000-0000-0000-0000-000000000002"))

(def fixed-timestamp
  (Instant/parse "2025-01-01T00:00:00Z"))

(def valid-user-request
  {:email "test@example.com"
   :name "Test User"
   :role :user})

;; =============================================================================
;; Email Validation Snapshots
;; =============================================================================

(deftest ^:unit email-validation-success-snapshot
  (testing "Valid email produces success result"
    (let [request (assoc valid-user-request :email "valid@example.com")
          result (user-core/validate-user-creation-request request vh/test-validation-config)]
      (snapshot-io/check-snapshot!
       result
       {:ns (ns-name *ns*)
        :test 'email-validation-success
        :seed 42}))))

(deftest ^:unit email-validation-invalid-format-snapshot
  (testing "Invalid email format produces structured error"
    (let [request (assoc valid-user-request :email "not-an-email")
          result (user-core/validate-user-creation-request request vh/test-validation-config)]
      (snapshot-io/check-snapshot!
       result
       {:ns (ns-name *ns*)
        :test 'email-validation-invalid-format
        :seed 43}))))

(deftest ^:unit email-validation-missing-snapshot
  (testing "Missing email produces required field error"
    (let [request (dissoc valid-user-request :email)
          result (user-core/validate-user-creation-request request vh/test-validation-config)]
      (snapshot-io/check-snapshot!
       result
       {:ns (ns-name *ns*)
        :test 'email-validation-missing
        :seed 44}))))

;; =============================================================================
;; Name Validation Snapshots
;; =============================================================================

(deftest ^:unit name-validation-too-short-snapshot
  (testing "Empty name produces length validation error"
    (let [request (assoc valid-user-request :name "")
          result (user-core/validate-user-creation-request request vh/test-validation-config)]
      (snapshot-io/check-snapshot!
       result
       {:ns (ns-name *ns*)
        :test 'name-validation-too-short
        :seed 45}))))

(deftest ^:unit name-validation-too-long-snapshot
  (testing "Name over 255 characters produces length validation error"
    (let [too-long-name (apply str (repeat 256 "a"))
          request (assoc valid-user-request :name too-long-name)
          result (user-core/validate-user-creation-request request vh/test-validation-config)]
      (snapshot-io/check-snapshot!
       result
       {:ns (ns-name *ns*)
        :test 'name-validation-too-long
        :seed 46}))))

(deftest ^:unit name-validation-boundary-snapshot
  (testing "Name at exactly 255 characters passes validation"
    (let [max-length-name (apply str (repeat 255 "a"))
          request (assoc valid-user-request :name max-length-name)
          result (user-core/validate-user-creation-request request vh/test-validation-config)]
      (snapshot-io/check-snapshot!
       result
       {:ns (ns-name *ns*)
        :test 'name-validation-boundary
        :seed 47}))))

;; =============================================================================
;; Multiple Field Validation Snapshots
;; =============================================================================

(deftest ^:unit multiple-validation-errors-snapshot
  (testing "Multiple missing fields produce aggregated errors"
    (let [request {:role :user} ; missing email, name
          result (user-core/validate-user-creation-request request vh/test-validation-config)]
      (snapshot-io/check-snapshot!
       result
       {:ns (ns-name *ns*)
        :test 'multiple-validation-errors
        :seed 48}))))

(deftest ^:unit complete-valid-user-snapshot
  (testing "All valid fields pass validation"
    (let [result (user-core/validate-user-creation-request valid-user-request vh/test-validation-config)]
      (snapshot-io/check-snapshot!
       result
       {:ns (ns-name *ns*)
        :test 'complete-valid-user
        :seed 49}))))

;; =============================================================================
;; Business Rule Validation Snapshots
;; =============================================================================

(deftest ^:unit email-change-forbidden-snapshot
  (testing "Email change produces business rule error"
    (let [current-user {:id fixed-user-id
                        :email "old@example.com"
                        :name "Test User"
                        :role :user
                        :active true
                        :created-at fixed-timestamp
                        :updated-at nil
                        :deleted-at nil}
          updated-user (assoc current-user :email "new@example.com")
          changes (user-core/calculate-user-changes current-user updated-user)
          result (user-core/validate-user-business-rules updated-user changes)]
      (snapshot-io/check-snapshot!
       result
       {:ns (ns-name *ns*)
        :test 'email-change-forbidden
        :seed 51}))))

;; =============================================================================
;; User Preparation Snapshots
;; =============================================================================

(deftest ^:unit prepare-user-for-creation-snapshot
  (testing "User preparation adds required fields with business defaults"
    (let [result (user-core/prepare-user-for-creation
                  valid-user-request
                  fixed-timestamp
                  fixed-user-id)]
      (snapshot-io/check-snapshot!
       result
       {:ns (ns-name *ns*)
        :test 'prepare-user-for-creation
        :seed 52}))))

(deftest ^:unit prepare-user-for-soft-deletion-snapshot
  (testing "Soft deletion sets deleted-at and active flags"
    (let [user {:id fixed-user-id
                :email "test@example.com"
                :name "Test User"
                :role :user
                :active true
                :created-at fixed-timestamp
                :updated-at nil
                :deleted-at nil}
          result (user-core/prepare-user-for-soft-deletion user fixed-timestamp)]
      (snapshot-io/check-snapshot!
       result
       {:ns (ns-name *ns*)
        :test 'prepare-user-for-soft-deletion
        :seed 53}))))
