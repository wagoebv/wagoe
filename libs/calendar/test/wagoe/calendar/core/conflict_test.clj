(ns wagoe.calendar.core.conflict-test
  "Unit tests for conflict detection — pure functions, no I/O."
  (:require [wagoe.calendar.core.conflict :as sut]
            [clojure.test :refer [deftest is testing]])
  (:import [java.time Instant]))

;; =============================================================================
;; Test data helpers
;; =============================================================================

(defn- make-event
  ([start-str end-str]
   (make-event start-str end-str nil))
  ([start-str end-str rrule]
   (cond-> {:id       (java.util.UUID/randomUUID)
            :title    "Test"
            :start    (Instant/parse start-str)
            :end      (Instant/parse end-str)
            :timezone "UTC"}
     rrule (assoc :recurrence rrule))))

;; =============================================================================
;; overlaps?
;; =============================================================================

(deftest ^:unit overlaps-test
  (testing "fully overlapping events"
    (let [a (make-event "2026-03-10T09:00:00Z" "2026-03-10T11:00:00Z")
          b (make-event "2026-03-10T09:30:00Z" "2026-03-10T10:30:00Z")]
      (is (true? (sut/overlaps? a b)))))
  (testing "partially overlapping events"
    (let [a (make-event "2026-03-10T09:00:00Z" "2026-03-10T10:30:00Z")
          b (make-event "2026-03-10T10:00:00Z" "2026-03-10T11:00:00Z")]
      (is (true? (sut/overlaps? a b)))))
  (testing "events sharing only a boundary do NOT overlap"
    (let [a (make-event "2026-03-10T09:00:00Z" "2026-03-10T10:00:00Z")
          b (make-event "2026-03-10T10:00:00Z" "2026-03-10T11:00:00Z")]
      (is (false? (sut/overlaps? a b)))))
  (testing "non-overlapping events — a before b"
    (let [a (make-event "2026-03-10T09:00:00Z" "2026-03-10T10:00:00Z")
          b (make-event "2026-03-10T11:00:00Z" "2026-03-10T12:00:00Z")]
      (is (false? (sut/overlaps? a b)))))
  (testing "non-overlapping events — b before a"
    (let [a (make-event "2026-03-10T11:00:00Z" "2026-03-10T12:00:00Z")
          b (make-event "2026-03-10T09:00:00Z" "2026-03-10T10:00:00Z")]
      (is (false? (sut/overlaps? a b)))))
  (testing "overlaps? is symmetric"
    (let [a (make-event "2026-03-10T09:00:00Z" "2026-03-10T10:30:00Z")
          b (make-event "2026-03-10T10:00:00Z" "2026-03-10T11:00:00Z")]
      (is (= (sut/overlaps? a b) (sut/overlaps? b a))))))

;; =============================================================================
;; conflicts?
;; =============================================================================

(deftest ^:unit conflicts-non-recurring-test
  (let [ws (Instant/parse "2026-03-10T00:00:00Z")
        we (Instant/parse "2026-03-11T00:00:00Z")]
    (testing "two overlapping non-recurring events conflict"
      (let [a (make-event "2026-03-10T09:00:00Z" "2026-03-10T10:30:00Z")
            b (make-event "2026-03-10T10:00:00Z" "2026-03-10T11:00:00Z")]
        (is (true? (sut/conflicts? a b ws we)))))
    (testing "two non-overlapping events do not conflict"
      (let [a (make-event "2026-03-10T09:00:00Z" "2026-03-10T10:00:00Z")
            b (make-event "2026-03-10T11:00:00Z" "2026-03-10T12:00:00Z")]
        (is (false? (sut/conflicts? a b ws we)))))))

(deftest ^:unit conflicts-recurring-test
  (let [ws (Instant/parse "2026-03-09T00:00:00Z")
        we (Instant/parse "2026-03-14T00:00:00Z")]
    (testing "recurring events with overlapping occurrences conflict"
      ;; Daily 09:00–10:00 vs daily 09:30–10:30 — every day overlaps
      (let [a (make-event "2026-03-09T09:00:00Z" "2026-03-09T10:00:00Z" "FREQ=DAILY")
            b (make-event "2026-03-09T09:30:00Z" "2026-03-09T10:30:00Z" "FREQ=DAILY")]
        (is (true? (sut/conflicts? a b ws we)))))
    (testing "recurring events with non-overlapping occurrences do not conflict"
      ;; Daily 09:00–10:00 vs daily 11:00–12:00 — no overlap
      (let [a (make-event "2026-03-09T09:00:00Z" "2026-03-09T10:00:00Z" "FREQ=DAILY")
            b (make-event "2026-03-09T11:00:00Z" "2026-03-09T12:00:00Z" "FREQ=DAILY")]
        (is (false? (sut/conflicts? a b ws we)))))))

;; =============================================================================
;; find-conflicts
;; =============================================================================

(deftest ^:unit find-conflicts-test
  (let [ws (Instant/parse "2026-03-10T00:00:00Z")
        we (Instant/parse "2026-03-11T00:00:00Z")]
    (testing "no events → no conflicts"
      (is (empty? (sut/find-conflicts [] ws we))))
    (testing "single event → no conflicts"
      (let [a (make-event "2026-03-10T09:00:00Z" "2026-03-10T10:00:00Z")]
        (is (empty? (sut/find-conflicts [a] ws we)))))
    (testing "two non-overlapping events → no conflicts"
      (let [a (make-event "2026-03-10T09:00:00Z" "2026-03-10T10:00:00Z")
            b (make-event "2026-03-10T11:00:00Z" "2026-03-10T12:00:00Z")]
        (is (empty? (sut/find-conflicts [a b] ws we)))))
    (testing "two overlapping events → one conflict"
      (let [a (make-event "2026-03-10T09:00:00Z" "2026-03-10T10:30:00Z")
            b (make-event "2026-03-10T10:00:00Z" "2026-03-10T11:00:00Z")
            conflicts (vec (sut/find-conflicts [a b] ws we))]
        (is (= 1 (count conflicts)))
        (let [c (first conflicts)]
          (is (= (:id a) (:id (:event-a c))))
          (is (= (:id b) (:id (:event-b c))))
          (is (= (Instant/parse "2026-03-10T10:00:00Z") (:overlap-start c)))
          (is (= (Instant/parse "2026-03-10T10:30:00Z") (:overlap-end c))))))
    (testing "three events with two conflicts"
      (let [a (make-event "2026-03-10T09:00:00Z" "2026-03-10T10:30:00Z")
            b (make-event "2026-03-10T10:00:00Z" "2026-03-10T11:00:00Z")
            c (make-event "2026-03-10T10:15:00Z" "2026-03-10T11:30:00Z")
            conflicts (vec (sut/find-conflicts [a b c] ws we))]
        ;; a∩b, a∩c, b∩c = 3 pairs
        (is (= 3 (count conflicts)))))))
