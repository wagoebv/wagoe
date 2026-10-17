(ns wagoe.platform.shell.reported-errors
  "The throwables already sent to the error tracker, so a 5xx that crosses a
   service and then the HTTP layer is reported once (BOU-714). Keys are weak:
   remembering a throwable does not keep it alive."
  (:import [java.util Collections Map WeakHashMap]))

(set! *warn-on-reflection* true)

;; Throwable does not override equals, so the map compares by identity.
(defonce ^:private ^Map reported (Collections/synchronizedMap (WeakHashMap.)))

(defn- cause-chain [^Throwable t]
  (take-while some? (iterate ex-cause t)))

(defn report-once!
  "Calls `(report!)` unless `t`, or a throwable it wraps, was reported already."
  [^Throwable t report!]
  (when-not (and t
                 (locking reported
                   (or (some #(.containsKey reported %) (cause-chain t))
                       (do (.put reported t true) false))))
    (report!)))
