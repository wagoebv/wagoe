(ns wagoe.workflow.core.transitions-test
  "Unit tests for pure workflow transition decision logic."
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.workflow.core.transitions :as t]))

;; =============================================================================
;; Test data
;; =============================================================================

(def ^:private order-def
  {:id             :order-workflow
   :initial-state  :pending
   :states         #{:pending :paid :shipped :delivered :cancelled}
   :transitions    [{:from :pending :to :paid
                     :required-permissions [:finance :admin]}
                    {:from :paid    :to :shipped
                     :guard        :payment-confirmed}
                    {:from :shipped :to :delivered
                     :name         :deliver}
                    {:from :pending :to :cancelled}
                    {:from :paid    :to :cancelled}]})

;; =============================================================================
;; find-transition-def
;; =============================================================================

(deftest ^:unit find-transition-def-test
  (testing "finds transition by :to when :name absent"
    (let [td (t/find-transition-def order-def :pending :paid)]
      (is (= :pending (:from td)))
      (is (= :paid (:to td)))))

  (testing "finds transition by :name when set"
    (let [td (t/find-transition-def order-def :shipped :deliver)]
      (is (= :shipped (:from td)))
      (is (= :delivered (:to td)))))

  (testing "returns nil when transition not defined"
    (is (nil? (t/find-transition-def order-def :delivered :paid))))

  (testing "returns nil when from-state not in workflow"
    (is (nil? (t/find-transition-def order-def :ghost :paid)))))

;; =============================================================================
;; allowed-transitions / allowed-transition-names
;; =============================================================================

(deftest ^:unit allowed-transitions-test
  (testing "returns all transitions from pending"
    (let [ts (t/allowed-transitions order-def :pending)]
      (is (= 2 (count ts)))
      (is (every? #(= :pending (:from %)) ts))))

  (testing "returns empty when state has no outgoing transitions"
    (is (empty? (t/allowed-transitions order-def :delivered)))))

(deftest ^:unit allowed-transition-names-test
  (testing "returns :to keyword when :name absent"
    (is (contains? (set (t/allowed-transition-names order-def :pending)) :paid)))

  (testing "returns :name when set"
    (is (contains? (set (t/allowed-transition-names order-def :shipped)) :deliver))))

;; =============================================================================
;; transition-exists?
;; =============================================================================

(deftest ^:unit transition-exists?-test
  (testing "true for valid transition"
    (is (true? (t/transition-exists? order-def :pending :paid))))

  (testing "false for invalid transition"
    (is (false? (t/transition-exists? order-def :delivered :paid)))))

;; =============================================================================
;; check-permissions
;; =============================================================================

(deftest ^:unit check-permissions-test
  (let [restricted-t (t/find-transition-def order-def :pending :paid)
        unrestricted-t (t/find-transition-def order-def :pending :cancelled)]

    (testing "allows when actor has a required role"
      (is (:allowed? (t/check-permissions restricted-t [:admin])))
      (is (:allowed? (t/check-permissions restricted-t [:finance :user]))))

    (testing "denies when actor has none of the required roles"
      (let [result (t/check-permissions restricted-t [:user])]
        (is (false? (:allowed? result)))
        (is (= :insufficient-permissions (:reason result)))
        (is (= [:finance :admin] (:required result)))
        (is (= [:user] (:provided result)))))

    (testing "allows when no permissions required"
      (is (:allowed? (t/check-permissions unrestricted-t [])))
      (is (:allowed? (t/check-permissions unrestricted-t nil))))

    (testing "allows when actor-roles is nil and no permissions required"
      (is (:allowed? (t/check-permissions unrestricted-t nil))))))

;; =============================================================================
;; evaluate-guard
;; =============================================================================

(deftest ^:unit evaluate-guard-test
  (let [guarded-t   (t/find-transition-def order-def :paid :shipped)
        unguarded-t (t/find-transition-def order-def :pending :cancelled)]

    (testing "allows when no guard defined"
      (is (:allowed? (t/evaluate-guard unguarded-t {} {}))))

    (testing "allows when guard returns true"
      (let [registry {:payment-confirmed (fn [_ctx] true)}]
        (is (:allowed? (t/evaluate-guard guarded-t registry {:paid? true})))))

    (testing "rejects when guard returns false"
      (let [registry {:payment-confirmed (fn [_ctx] false)}
            result   (t/evaluate-guard guarded-t registry {})]
        (is (false? (:allowed? result)))
        (is (= :guard-rejected (:reason result)))
        (is (= :payment-confirmed (:guard result)))))

    (testing "fails safe when guard key registered but no fn"
      (let [result (t/evaluate-guard guarded-t {} {})]
        (is (false? (:allowed? result)))
        (is (= :guard-not-registered (:reason result)))))))

;; =============================================================================
;; can-transition?
;; =============================================================================

(deftest ^:unit can-transition?-test
  (let [guards {:payment-confirmed (fn [_ctx] true)}]

    (testing "allows unrestricted transition"
      (let [result (t/can-transition? order-def :pending :cancelled [] nil nil)]
        (is (:allowed? result))
        (is (some? (:transition-def result)))))

    (testing "allows restricted transition when actor has role"
      (let [result (t/can-transition? order-def :pending :paid [:admin] nil nil)]
        (is (:allowed? result))))

    (testing "rejects when actor lacks permission"
      (let [result (t/can-transition? order-def :pending :paid [:user] nil nil)]
        (is (false? (:allowed? result)))
        (is (= :insufficient-permissions (:reason result)))))

    (testing "rejects non-existent transition"
      (let [result (t/can-transition? order-def :delivered :paid nil nil nil)]
        (is (false? (:allowed? result)))
        (is (= :transition-not-found (:reason result)))))

    (testing "runs guard when permissions pass"
      (let [result (t/can-transition? order-def :paid :shipped nil guards {:ok true})]
        (is (:allowed? result))))

    (testing "rejects when guard fails"
      (let [failing-guards {:payment-confirmed (fn [_] false)}
            result (t/can-transition? order-def :paid :shipped nil failing-guards {})]
        (is (false? (:allowed? result)))
        (is (= :guard-rejected (:reason result)))))))

;; =============================================================================
;; destination-state / side-effects
;; =============================================================================

(deftest ^:unit destination-state-test
  (testing "returns :to value"
    (let [td (t/find-transition-def order-def :pending :paid)]
      (is (= :paid (t/destination-state td))))))

(deftest ^:unit side-effects-test
  (testing "returns empty vector when none defined"
    (let [td (t/find-transition-def order-def :pending :paid)]
      (is (= [] (t/side-effects td)))))

  (testing "returns side-effect keys when defined"
    (let [td {:from :pending :to :paid :side-effects [:notify-finance :create-invoice]}]
      (is (= [:notify-finance :create-invoice] (t/side-effects td))))))

;; =============================================================================
;; available-transitions-with-status
;; =============================================================================

(deftest ^:unit available-transitions-with-status-test
  (testing "returns all reachable transitions with :enabled? and :id fields"
    (let [ts (t/available-transitions-with-status order-def :pending [:admin] nil nil)]
      (is (= 2 (count ts)))
      (is (every? #(contains? % :id) ts))
      (is (every? #(contains? % :enabled?) ts))))

  (testing "enabled? is true when permissions and guards pass"
    (let [ts (t/available-transitions-with-status order-def :pending [:admin] nil nil)
          ids (set (map :id ts))]
      (is (contains? ids :paid))
      (is (contains? ids :cancelled))
      (is (every? :enabled? ts))))

  (testing "enabled? is false when actor lacks permission"
    (let [ts     (t/available-transitions-with-status order-def :pending [:user] nil nil)
          by-id  (into {} (map (fn [t] [(:id t) t]) ts))]
      (is (false? (:enabled? (get by-id :paid))))
      (is (= :insufficient-permissions (:reason (get by-id :paid))))
      (is (true?  (:enabled? (get by-id :cancelled))))))

  (testing "enabled? is false when guard rejects"
    (let [failing-guards {:payment-confirmed (fn [_] false)}
          ts     (t/available-transitions-with-status order-def :paid nil failing-guards nil)
          by-id  (into {} (map (fn [t] [(:id t) t]) ts))]
      (is (false? (:enabled? (get by-id :shipped))))
      (is (= :guard-rejected (:reason (get by-id :shipped))))
      (is (true?  (:enabled? (get by-id :cancelled))))))

  (testing "includes :label when transition def has :label"
    (let [def-with-label {:id            :lab-workflow
                          :initial-state :a
                          :states        #{:a :b}
                          :transitions   [{:from :a :to :b :label "Approve"}]}
          ts (t/available-transitions-with-status def-with-label :a [] nil nil)]
      (is (= "Approve" (:label (first ts))))))

  (testing "omits :label key when transition def has none"
    (let [ts (t/available-transitions-with-status order-def :pending [:admin] nil nil)
          paid (first (filter #(= :paid (:id %)) ts))]
      (is (not (contains? paid :label)))))

  (testing "returns empty vector when state has no outgoing transitions"
    (is (empty? (t/available-transitions-with-status order-def :delivered nil nil nil)))))
