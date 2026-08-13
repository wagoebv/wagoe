(ns wagoe.platform.shell.rpc.breaker
  "Circuit-breaker state, kept where every replica can see it.

   A breaker in one JVM's atom protects that JVM. With N replicas a failing
   service gets N breakers, each of which has to trip on its own, so it still
   takes N times the load — and when the window elapses, all N probe at once,
   which is the stampede the breaker was meant to prevent. Keeping the state in
   the cache port makes it one breaker.

   The cache adapter decides how far that goes: Redis shares it across
   replicas, the in-memory one does not. That is the same trade the rate
   limiter makes, and it is a property of the adapter rather than of this
   namespace."
  (:require [wagoe.cache.ports :as cache]
            [wagoe.platform.core.circuit-breaker :as cb]
            [clojure.tools.logging :as log]))

(def ^:private key-prefix "wagoe:rpc:breaker:")

(defn- failures-key [base-url] (str key-prefix base-url ":failures"))
(defn- opened-key   [base-url] (str key-prefix base-url ":opened-at"))
(defn- probe-key    [base-url] (str key-prefix base-url ":probe"))

(defn- window-seconds
  "How long breaker state outlives the window it describes.

   Twice the open window: long enough that a breaker which has tripped is still
   honoured when the window ends, so `open` becomes `half-open` rather than
   vanishing into `closed` and letting every caller through at once."
  [config]
  (max 1 (* 2 (quot (:open-ms config (:open-ms cb/default-config)) 1000))))

(defn- read-state
  [cache-component base-url]
  (try
    {:failures     (or (cache/get-value cache-component (failures-key base-url)) 0)
     :opened-at-ms (:at (cache/get-value cache-component (opened-key base-url)))}
    (catch Exception e
      ;; A cache that is down must not stop calls going out. Failing open is
      ;; the safe direction: the worst case is the behaviour of no breaker at
      ;; all, which is what every caller had before this existed.
      (log/warn e "circuit breaker could not read its state; allowing the call"
                {:base-url base-url})
      nil)))

(defn allow?
  "Whether to attempt a call to `base-url`.

   Half-open lets exactly one caller through. The probe is a lease taken with
   `set-if-absent!` — atomic in Redis — so N replicas reaching the end of the
   window together produce one trial request rather than N. Without it the
   recovery is its own thundering herd."
  [cache-component config base-url now-ms]
  (if-not cache-component
    true
    (let [breaker (read-state cache-component base-url)]
      (if (nil? breaker)
        true
        (case (cb/state breaker config now-ms)
          :closed    true
          :open      false
          :half-open (try
                       (boolean
                        (cache/set-if-absent! cache-component (probe-key base-url)
                                              {:at now-ms}
                                              (max 1 (quot (:open-ms config
                                                                     (:open-ms cb/default-config))
                                                           1000))))
                       (catch Exception e
                         (log/warn e "circuit breaker could not take the probe lease"
                                   {:base-url base-url})
                         true)))))))

(defn record-failure!
  "Count a failure, and open the breaker if it is the one that crosses the line.

   The count is an atomic increment, not read-modify-write. A shared breaker
   exists for the case where many callers hit one outage at the same moment,
   and that is exactly when a read-modify-write loses increments: each reads the
   same value and writes back the same successor, so a burst of twenty failures
   advances the counter by one and the breaker never trips.

   `set-if-absent!` on the open marker keeps the moment the *first* crosser
   opened it, rather than every subsequent failure pushing the window forward."
  [cache-component config base-url now-ms]
  (when cache-component
    (try
      (let [was       (cb/state (or (read-state cache-component base-url) {}) config now-ms)
            failures  (cache/increment! cache-component (failures-key base-url))
            threshold (:failure-threshold config (:failure-threshold cb/default-config))]
        ;; Only on the first of a run: gives the run a lifetime, so failures
        ;; minutes apart do not accumulate as though they were consecutive.
        (when (= 1 failures)
          (cache/expire! cache-component (failures-key base-url) (window-seconds config)))
        (if (= was :half-open)
          ;; The probe failed. Reopen from now — `set-if-absent!` would find the
          ;; old marker and leave it, so the window would run out from the
          ;; *original* outage and traffic would resume against a service that
          ;; has just demonstrated it is still down.
          (do (cache/set-value! cache-component (opened-key base-url)
                                {:at now-ms} (window-seconds config))
              ;; Release the lease so the next window can be probed.
              (cache/delete-key! cache-component (probe-key base-url))
              (log/warn "circuit re-opened after a failed probe" {:base-url base-url}))

          (when (and (>= failures threshold)
                     (cache/set-if-absent! cache-component (opened-key base-url)
                                           {:at now-ms} (window-seconds config)))
            (log/warn "circuit opened" {:base-url base-url :failures failures}))))
      (catch Exception e
        (log/warn e "circuit breaker could not record a failure" {:base-url base-url})))))

(defn record-success!
  "Close the breaker: the service answered."
  [cache-component base-url]
  (when cache-component
    (try
      (cache/delete-key! cache-component (failures-key base-url))
      (cache/delete-key! cache-component (opened-key base-url))
      (cache/delete-key! cache-component (probe-key base-url))
      (catch Exception e
        (log/warn e "circuit breaker could not record a success" {:base-url base-url})))))

(defn open-error
  "The error for a call the breaker declined to make."
  [cache-component config base-url operation now-ms]
  (cb/error operation base-url
            (some-> (read-state cache-component base-url)
                    (cb/retry-after-ms config now-ms))))
