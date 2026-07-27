(ns wagoe.user.core.audit-test
  "Unit tests for audit core functions.
   
   These test pure functions that create audit log entries.
   No database interaction - testing pure data transformations."
  (:require [wagoe.user.core.audit :as audit]
            [clojure.test :refer [deftest is testing]])
  (:import (java.util UUID)))

;; =============================================================================
;; Test Data
;; =============================================================================

(def test-actor-id (UUID/randomUUID))
(def test-target-id (UUID/randomUUID))
(def test-actor-email "admin@example.com")
(def test-target-email "user@example.com")
(def test-ip "192.168.1.100")
(def test-user-agent "Mozilla/5.0 (Test Browser)")

;; =============================================================================
;; Create User Audit Entry Tests
;; =============================================================================

(deftest ^:unit create-user-audit-entry-test
  (testing "Creates audit entry for user creation with all fields"
    (let [target-user {:id test-target-id
                       :email test-target-email
                       :name "Test User"
                       :role :user
                       :active true}
          entry (audit/create-user-audit-entry
                 test-actor-id
                 test-actor-email
                 target-user
                 test-ip
                 test-user-agent)]

      (is (= :create (:action entry)))
      (is (= test-actor-id (:actor-id entry)))
      (is (= test-actor-email (:actor-email entry)))
      (is (= test-target-id (:target-user-id entry)))
      (is (= test-target-email (:target-user-email entry)))
      (is (= {:created true} (:changes entry)))
      (is (= "user" (get-in entry [:metadata :role])))
      (is (= true (get-in entry [:metadata :active])))
      (is (= test-ip (:ip-address entry)))
      (is (= test-user-agent (:user-agent entry)))
      (is (= :success (:result entry)))))

  (testing "Creates audit entry with nil IP and user-agent"
    (let [target-user {:id test-target-id
                       :email test-target-email
                       :role :admin
                       :active true}
          entry (audit/create-user-audit-entry
                 test-actor-id
                 test-actor-email
                 target-user
                 nil
                 nil)]

      (is (nil? (:ip-address entry)))
      (is (nil? (:user-agent entry)))
      (is (= "admin" (get-in entry [:metadata :role]))))))

;; =============================================================================
;; Update User Audit Entry Tests
;; =============================================================================

(deftest ^:unit update-user-audit-entry-test
  (testing "Creates audit entry with field changes"
    (let [old-values {:role :user :name "Old Name" :active true}
          new-values {:role :admin :name "New Name" :active true}
          entry (audit/update-user-audit-entry
                 test-actor-id
                 test-actor-email
                 test-target-id
                 test-target-email
                 old-values
                 new-values
                 test-ip
                 test-user-agent)]

      (is (= :update (:action entry)))
      (is (= test-actor-id (:actor-id entry)))
      (is (= test-target-id (:target-user-id entry)))
      (is (= :success (:result entry)))

      ;; Check changed fields
      (let [fields (get-in entry [:changes :fields])]
        (is (= 2 (count fields)))
        (is (= 2 (get-in entry [:metadata :field-count])))

        ;; Find role change
        (let [role-change (first (filter #(= "role" (:field %)) fields))]
          (is (= ":user" (:old role-change)))
          (is (= ":admin" (:new role-change))))

        ;; Find name change
        (let [name-change (first (filter #(= "name" (:field %)) fields))]
          (is (= "Old Name" (:old name-change)))
          (is (= "New Name" (:new name-change)))))))

  (testing "Creates audit entry with no changes (same values)"
    (let [values {:role :user :name "Same Name"}
          entry (audit/update-user-audit-entry
                 test-actor-id
                 test-actor-email
                 test-target-id
                 test-target-email
                 values
                 values
                 nil
                 nil)]

      (is (= 0 (count (get-in entry [:changes :fields]))))
      (is (= 0 (get-in entry [:metadata :field-count]))))

  (testing "Creates audit entry with only some fields changed"
    (let [old-values {:role :user :name "Name" :active true}
          new-values {:role :user :name "Name" :active false}
          entry (audit/update-user-audit-entry
                 test-actor-id
                 test-actor-email
                 test-target-id
                 test-target-email
                 old-values
                 new-values
                 test-ip
                 test-user-agent)]

      (is (= 1 (count (get-in entry [:changes :fields]))))
      (let [active-change (first (get-in entry [:changes :fields]))]
        (is (= "active" (:field active-change)))
        (is (= "true" (:old active-change)))
        (is (= "false" (:new active-change))))))))

;; =============================================================================
;; Deactivate User Audit Entry Tests
;; =============================================================================

(deftest ^:unit deactivate-user-audit-entry-test
  (testing "Creates audit entry for user deactivation"
    (let [entry (audit/deactivate-user-audit-entry
                 test-actor-id
                 test-actor-email
                 test-target-id
                 test-target-email
                 test-ip
                 test-user-agent)]

      (is (= :deactivate (:action entry)))
      (is (= test-actor-id (:actor-id entry)))
      (is (= test-target-id (:target-user-id entry)))
      (is (= {:active {:old true :new false}} (:changes entry)))
      (is (= :success (:result entry)))))

  (testing "Creates audit entry with nil context"
    (let [entry (audit/deactivate-user-audit-entry
                 test-actor-id
                 test-actor-email
                 test-target-id
                 test-target-email
                 nil
                 nil)]

      (is (nil? (:ip-address entry)))
      (is (nil? (:user-agent entry))))))

;; =============================================================================
;; Activate User Audit Entry Tests
;; =============================================================================

(deftest ^:unit activate-user-audit-entry-test
  (testing "Creates audit entry for user activation"
    (let [entry (audit/activate-user-audit-entry
                 test-actor-id
                 test-actor-email
                 test-target-id
                 test-target-email
                 test-ip
                 test-user-agent)]

      (is (= :activate (:action entry)))
      (is (= test-actor-id (:actor-id entry)))
      (is (= test-target-id (:target-user-id entry)))
      (is (= {:active {:old false :new true}} (:changes entry)))
      (is (= :success (:result entry))))))

;; =============================================================================
;; Delete User Audit Entry Tests
;; =============================================================================

(deftest ^:unit delete-user-audit-entry-test
  (testing "Creates audit entry for hard delete"
    (let [entry (audit/delete-user-audit-entry
                 test-actor-id
                 test-actor-email
                 test-target-id
                 test-target-email
                 test-ip
                 test-user-agent)]

      (is (= :delete (:action entry)))
      (is (= test-actor-id (:actor-id entry)))
      (is (= test-target-id (:target-user-id entry)))
      (is (= {:deleted true} (:changes entry)))
      (is (= :success (:result entry)))))

  (testing "Includes permanent flag in metadata for hard delete"
    (let [entry (audit/delete-user-audit-entry
                 test-actor-id
                 test-actor-email
                 test-target-id
                 test-target-email
                 test-ip
                 test-user-agent)]

      (is (true? (get-in entry [:metadata :permanent]))))))

;; =============================================================================
;; Role Change Audit Entry Tests
;; =============================================================================

(deftest ^:unit role-change-audit-entry-test
  (testing "Creates audit entry for role change from user to admin"
    (let [entry (audit/role-change-audit-entry
                 test-actor-id
                 test-actor-email
                 test-target-id
                 test-target-email
                 :user
                 :admin
                 test-ip
                 test-user-agent)]

      (is (= :role-change (:action entry)))
      (is (= test-actor-id (:actor-id entry)))
      ;; Role values are converted to strings
      (is (= {:role {:old "user" :new "admin"}} (:changes entry)))
      (is (= :success (:result entry)))))

  (testing "Creates audit entry for role change from admin to user"
    (let [entry (audit/role-change-audit-entry
                 test-actor-id
                 test-actor-email
                 test-target-id
                 test-target-email
                 :admin
                 :user
                 test-ip
                 test-user-agent)]

      ;; Role values are converted to strings
      (is (= {:role {:old "admin" :new "user"}} (:changes entry))))))

;; =============================================================================
;; Bulk Action Audit Entry Tests
;; =============================================================================

(deftest ^:unit bulk-action-audit-entry-test
  (testing "Creates audit entry for bulk action"
    (let [user-ids [(UUID/randomUUID) (UUID/randomUUID) (UUID/randomUUID)]
          entry (audit/bulk-action-audit-entry
                 test-actor-id
                 test-actor-email
                 :deactivate
                 user-ids
                 test-ip
                 test-user-agent
                 {:reason "Cleanup inactive users"})]

      (is (= :bulk-action (:action entry)))
      (is (= test-actor-id (:actor-id entry)))
      (is (= "deactivate" (get-in entry [:metadata :bulk-action-type])))
      (is (= 3 (get-in entry [:metadata :affected-user-count])))
      (is (= "Cleanup inactive users" (get-in entry [:metadata :reason])))
      (is (= 3 (count (get-in entry [:metadata :user-ids]))))
      (is (= :success (:result entry)))))

  (testing "Converts user IDs to strings in metadata"
    (let [user-ids [(UUID/randomUUID)]
          entry (audit/bulk-action-audit-entry
                 test-actor-id
                 test-actor-email
                 :delete
                 user-ids
                 nil
                 nil
                 {})
          stored-ids (get-in entry [:metadata :user-ids])]
      (is (every? string? stored-ids)))))

;; =============================================================================
;; Login Audit Entry Tests
;; =============================================================================

(deftest ^:unit login-audit-entry-test
  (testing "Creates audit entry for successful login"
    (let [entry (audit/login-audit-entry
                 test-target-id
                 test-target-email
                 test-ip
                 test-user-agent
                 true
                 nil)]

      (is (= :login (:action entry)))
      (is (= test-target-id (:actor-id entry)))
      (is (= test-target-id (:target-user-id entry)))
      (is (= test-target-email (:actor-email entry)))
      (is (= test-target-email (:target-user-email entry)))
      (is (= :success (:result entry)))
      (is (nil? (:error-message entry)))))

  (testing "Creates audit entry for failed login"
    (let [error-msg "Invalid password"
          entry (audit/login-audit-entry
                 test-target-id
                 test-target-email
                 test-ip
                 test-user-agent
                 false
                 error-msg)]

      (is (= :login (:action entry)))
      (is (= :failure (:result entry)))
      (is (= error-msg (:error-message entry)))))

  (testing "Records IP and user-agent for login attempts"
    (let [entry (audit/login-audit-entry
                 test-target-id
                 test-target-email
                 test-ip
                 test-user-agent
                 true
                 nil)]

      (is (= test-ip (:ip-address entry)))
      (is (= test-user-agent (:user-agent entry))))))

;; =============================================================================
;; Logout Audit Entry Tests
;; =============================================================================

(deftest ^:unit logout-audit-entry-test
  (testing "Creates audit entry for logout"
    (let [entry (audit/logout-audit-entry
                 test-target-id
                 test-target-email
                 test-ip
                 test-user-agent)]

      (is (= :logout (:action entry)))
      (is (= test-target-id (:actor-id entry)))
      (is (= test-target-id (:target-user-id entry)))
      (is (= test-target-email (:actor-email entry)))
      (is (= :success (:result entry)))))

  (testing "Handles nil IP and user-agent"
    (let [entry (audit/logout-audit-entry
                 test-target-id
                 test-target-email
                 nil
                 nil)]

      (is (nil? (:ip-address entry)))
      (is (nil? (:user-agent entry))))))

;; =============================================================================
;; Sanitize Audit Metadata Tests
;; =============================================================================

(deftest ^:unit sanitize-audit-metadata-test
  (testing "Removes sensitive fields from metadata"
    (let [metadata {:username "testuser"
                    :password "secret123"
                    :email "user@example.com"
                    :role "admin"}
          sanitized (audit/sanitize-audit-metadata metadata)]

      (is (contains? sanitized :username))
      (is (contains? sanitized :email))
      (is (contains? sanitized :role))
      (is (not (contains? sanitized :password)))))

  (testing "Removes specific sensitive fields from metadata"
    (let [metadata {:username "testuser"
                    :password "secret123"
                    :password-hash "$2a$..."
                    :session-token "sess456"
                    :reset-token "reset789"
                    :email "user@example.com"
                    :role "admin"}
          sanitized (audit/sanitize-audit-metadata metadata)]

      ;; These should remain
      (is (contains? sanitized :username))
      (is (contains? sanitized :email))
      (is (contains? sanitized :role))

      ;; These should be removed
      (is (not (contains? sanitized :password)))
      (is (not (contains? sanitized :password-hash)))
      (is (not (contains? sanitized :session-token)))
      (is (not (contains? sanitized :reset-token)))))

  (testing "Returns empty map for empty input"
    (let [sanitized (audit/sanitize-audit-metadata {})]
      (is (empty? sanitized))))

  (testing "Returns nil for nil input"
    (let [sanitized (audit/sanitize-audit-metadata nil)]
      (is (nil? sanitized)))))
