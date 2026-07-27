(ns wagoe.calendar.shell.registry-test
  "Unit tests for the event type definition registry (shell state)."
  (:require [wagoe.calendar.shell.registry :as registry]
            [clojure.test :refer [deftest is testing use-fixtures]]))

;; =============================================================================
;; Fixture — clear registry between tests
;; =============================================================================

(use-fixtures :each
  (fn [f]
    (registry/clear-registry!)
    (f)
    (registry/clear-registry!)))

;; =============================================================================
;; defevent macro and registry
;; =============================================================================

(registry/defevent test-appointment-event
  {:id    :test-appointment
   :label "Test Appointment"})

(deftest ^:unit defevent-macro-test
  (testing "defevent binds var to definition map"
    (is (= :test-appointment (:id test-appointment-event)))
    (is (= "Test Appointment" (:label test-appointment-event))))
  (testing "defevent registers in the registry"
    (registry/register-event-type! test-appointment-event)
    (is (= test-appointment-event (registry/get-event-type :test-appointment))))
  (testing "list-event-types includes registered id"
    (registry/register-event-type! test-appointment-event)
    (is (some #{:test-appointment} (registry/list-event-types)))))

(deftest ^:unit register-event-type-test
  (testing "programmatic registration"
    (let [defn {:id :booking :label "Booking"}]
      (registry/register-event-type! defn)
      (is (= defn (registry/get-event-type :booking)))))
  (testing "get-event-type returns nil for unknown id"
    (is (nil? (registry/get-event-type :unknown-event-xyz)))))

(deftest ^:unit clear-registry-test
  (testing "clear-registry! empties the registry"
    (registry/register-event-type! {:id :temp-event})
    (registry/clear-registry!)
    (is (empty? (registry/list-event-types)))))
