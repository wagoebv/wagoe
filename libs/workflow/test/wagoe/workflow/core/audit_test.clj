(ns wagoe.workflow.core.audit-test
  "Unit tests for pure audit entry construction."
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.workflow.core.audit :as audit])
  (:import [java.time Instant]
           [java.util UUID]))

;; =============================================================================
;; Test data
;; =============================================================================

(def ^:private entry-id   (UUID/fromString "00000000-0000-0000-0000-000000000001"))
(def ^:private instance-id (UUID/fromString "00000000-0000-0000-0000-000000000002"))
(def ^:private entity-id  (UUID/fromString "00000000-0000-0000-0000-000000000003"))
(def ^:private actor-id   (UUID/fromString "00000000-0000-0000-0000-000000000004"))
(def ^:private now        (Instant/parse "2026-01-01T12:00:00Z"))

(def ^:private instance
  {:id            instance-id
   :workflow-id   :order-workflow
   :entity-type   :order
   :entity-id     entity-id
   :current-state :pending
   :created-at    now
   :updated-at    now})

(def ^:private transition-def
  {:from :pending :to :paid :required-permissions [:admin]})

(def ^:private named-transition-def
  {:from :shipped :to :delivered :name :deliver})

;; =============================================================================
;; create-audit-entry
;; =============================================================================

(deftest ^:unit create-audit-entry-test
  (testing "sets all required fields"
    (let [entry (audit/create-audit-entry
                 entry-id instance transition-def
                 actor-id [:admin] {:order-amount 100} now)]
      (is (= entry-id (:id entry)))
      (is (= instance-id (:instance-id entry)))
      (is (= :order-workflow (:workflow-id entry)))
      (is (= :order (:entity-type entry)))
      (is (= entity-id (:entity-id entry)))
      (is (= :pending (:from-state entry)))
      (is (= :paid (:to-state entry)))
      (is (= :paid (:transition entry)))
      (is (= now (:occurred-at entry)))))

  (testing "uses :name as transition key when defined"
    (let [instance-at-shipped (assoc instance :current-state :shipped)
          entry (audit/create-audit-entry
                 entry-id instance-at-shipped named-transition-def
                 nil nil nil now)]
      (is (= :deliver (:transition entry)))))

  (testing "includes actor-id and actor-roles when provided"
    (let [entry (audit/create-audit-entry
                 entry-id instance transition-def
                 actor-id [:admin :finance] nil now)]
      (is (= actor-id (:actor-id entry)))
      (is (= [:admin :finance] (:actor-roles entry)))))

  (testing "omits actor-id and actor-roles when nil"
    (let [entry (audit/create-audit-entry
                 entry-id instance transition-def
                 nil nil nil now)]
      (is (not (contains? entry :actor-id)))
      (is (not (contains? entry :actor-roles)))))

  (testing "includes context when provided"
    (let [ctx {:order-amount 50}
          entry (audit/create-audit-entry
                 entry-id instance transition-def
                 nil nil ctx now)]
      (is (= ctx (:context entry)))))

  (testing "omits context when nil"
    (let [entry (audit/create-audit-entry
                 entry-id instance transition-def
                 nil nil nil now)]
      (is (not (contains? entry :context))))))

;; =============================================================================
;; audit-summary
;; =============================================================================

(deftest ^:unit audit-summary-test
  (testing "returns concise summary with key fields"
    (let [entry   (audit/create-audit-entry
                   entry-id instance transition-def
                   actor-id [:admin] nil now)
          summary (audit/audit-summary entry)]
      (is (= entry-id (:id summary)))
      (is (= instance-id (:instance-id summary)))
      (is (= :paid (:transition summary)))
      (is (= :pending (:from-state summary)))
      (is (= :paid (:to-state summary)))
      (is (= actor-id (:actor-id summary)))
      (is (= now (:occurred-at summary))))))
