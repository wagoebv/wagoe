(ns wagoe.calendar.core.recurrence-property-test
  "Property tests for RRULE occurrence expansion. Generates constrained RRULEs
   (FREQ/INTERVAL/COUNT) and asserts the invariants that must hold for any of
   them, complementing the fixed-example unit tests."
  (:require [wagoe.calendar.core.recurrence :as sut]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop])
  (:import [java.time Duration Instant]))

;; Fixed UTC event + a wide window so a bounded COUNT always fits.
(def ^:private event-start (Instant/parse "2026-01-05T09:00:00Z"))
(def ^:private window-start (Instant/parse "2026-01-01T00:00:00Z"))
(def ^:private window-end (Instant/parse "2031-01-01T00:00:00Z"))

(defn- event [rrule]
  {:id         "prop-event"
   :title      "Prop Event"
   :start      event-start
   :end        (.plusSeconds event-start 1800)
   :timezone   "UTC"
   :recurrence rrule})

(defn- occs [rrule]
  (sut/occurrences (event rrule) window-start window-end))

(def ^:private freq-gen (gen/elements ["DAILY" "WEEKLY" "MONTHLY"]))
(def ^:private interval-gen (gen/choose 1 4))
(def ^:private count-gen (gen/choose 1 10))

(defn- rrule [freq interval cnt]
  (str "FREQ=" freq
       (when (> interval 1) (str ";INTERVAL=" interval))
       ";COUNT=" cnt))

;; =============================================================================
;; Invariants
;; =============================================================================

(defspec ^:property occurrences-lie-within-window 100
  (prop/for-all [freq freq-gen, iv interval-gen, cnt count-gen]
                (every? (fn [^Instant o]
                          (and (not (.isBefore o window-start))
                               (.isBefore o window-end)))
                        (occs (rrule freq iv cnt)))))

(defspec ^:property occurrences-strictly-ascending 100
  (prop/for-all [freq freq-gen, iv interval-gen, cnt count-gen]
                (let [os (occs (rrule freq iv cnt))]
                  ;; sorted AND no duplicates == strictly increasing
                  (and (= os (sort os))
                       (or (< (count os) 2) (apply distinct? os))))))

(defspec ^:property first-occurrence-at-or-after-start 100
  (prop/for-all [freq freq-gen, iv interval-gen, cnt count-gen]
                (let [os (occs (rrule freq iv cnt))]
                  (or (empty? os)
                      (not (.isBefore ^Instant (first os) event-start))))))

(defspec ^:property count-is-an-upper-bound 100
  (prop/for-all [freq freq-gen, cnt count-gen]
                (let [os (occs (str "FREQ=" freq ";COUNT=" cnt))]
                  (and (<= (count os) cnt)
                       (pos? (count os))))))

(defspec ^:property daily-interval-spacing-is-exact 100
  (prop/for-all [iv interval-gen, cnt (gen/choose 2 8)]
                (let [os (occs (str "FREQ=DAILY;INTERVAL=" iv ";COUNT=" cnt))]
                  (every? (fn [[^Instant a ^Instant b]]
                            (= (* iv 86400) (.getSeconds (Duration/between a b))))
                          (partition 2 1 os)))))
