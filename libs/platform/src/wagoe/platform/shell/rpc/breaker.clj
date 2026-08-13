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

(defn- state-key [base-url] (str key-prefix base-url))
(defn- probe-key [base-url] (str key-prefix base-url ":probe"))

(defn- read-state
  [cache-component base-url]
  (try
    (or (cache/get-value cache-component (state-key base-url)) {})
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
  "Count a failure, and open the breaker if it is the one that crosses the line."
  [cache-component config base-url now-ms]
  (when cache-component
    (try
      (let [breaker (or (read-state cache-component base-url) {})
            next'   (cb/on-failure breaker config now-ms)]
        (cache/set-value! cache-component (state-key base-url) next'
                          ;; Outlive the open window, so a breaker that has
                          ;; tripped is not forgotten before it can be honoured.
                          (max 1 (* 2 (quot (:open-ms config (:open-ms cb/default-config))
                                            1000))))
        (when (and (:opened-at-ms next') (not (:opened-at-ms breaker)))
          (log/warn "circuit opened" {:base-url base-url :failures (:failures next')})))
      (catch Exception e
        (log/warn e "circuit breaker could not record a failure" {:base-url base-url})))))

(defn record-success!
  "Close the breaker: the service answered."
  [cache-component base-url]
  (when cache-component
    (try
      (cache/delete-key! cache-component (state-key base-url))
      (cache/delete-key! cache-component (probe-key base-url))
      (catch Exception e
        (log/warn e "circuit breaker could not record a success" {:base-url base-url})))))

(defn open-error
  "The error for a call the breaker declined to make."
  [cache-component config base-url operation now-ms]
  (cb/error operation base-url
            (some-> (read-state cache-component base-url)
                    (cb/retry-after-ms config now-ms))))
