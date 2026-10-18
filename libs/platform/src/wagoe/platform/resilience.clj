(ns wagoe.platform.resilience
  "Retry and circuit breaker for outbound calls — the RPC client and the
   libs/external adapters both go through here.

   Outside `shell/` for the reason `wagoe.platform.database` is: other modules
   call it, and reaching into platform's shell is what `bb check:ports` refuses.

   Both take predicates over a result rather than deciding what counts as a
   failure. Only the caller knows whether a second attempt could run something
   twice."
  (:require [wagoe.platform.core.circuit-breaker :as cb]
            [wagoe.platform.core.retry :as retry]
            [wagoe.platform.shell.breaker :as breaker]))

(defn settings
  "Retry and breaker settings for calls to `circuit`.

   `timeout-ms` is one attempt's longest wait: the probe lease must outlive a
   whole run of attempts, or it expires mid-probe and a second caller sends a
   second probe. Throws on an invalid `:circuit-breaker` — an inert breaker
   looks exactly like a working one."
  [{:keys [cache circuit-breaker]} policy circuit timeout-ms]
  (let [cb-cfg (merge cb/default-config
                      {:probe-lease-ms (retry/budget-ms policy timeout-ms)}
                      circuit-breaker)]
    (when-let [problem (and circuit-breaker (cb/config-problem cb-cfg))]
      (throw (ex-info problem {:type            :configuration-error
                               :circuit-breaker circuit-breaker})))
    {:policy policy :cache cache :breaker cb-cfg :circuit circuit}))

(defn with-retry
  "Call `(f attempt)` (1-based) until `retryable?` rejects its result or
   `policy` runs out of attempts, sleeping `retry/delay-ms` in between.
   Returns the last result.

   `:on-retry` gets `[result attempt delay-ms]` before each sleep, and
   `:on-give-up` `[result attempts]` when a retryable result is the last."
  [policy retryable? f & [{:keys [on-retry on-give-up]}]]
  (loop [attempt 1]
    (let [result (f attempt)
          again? (retryable? result)]
      (cond
        (and again? (retry/attempts-left? policy attempt))
        (let [delay (retry/delay-ms policy attempt)]
          (when on-retry (on-retry result attempt delay))
          (Thread/sleep (long delay))
          (recur (inc attempt)))

        again?
        (do (when on-give-up (on-give-up result attempt))
            result)

        :else result))))

(defn with-breaker
  "Call `(f)` unless the breaker for `circuit` is open; record the outcome.

   With no `cache` there is no breaker and `f` always runs. When open, returns
   `(on-open retry-after-ms)` without calling `f`. `failure?` decides whether a
   result counts against the circuit — count each call once, after its retries,
   or a threshold of 5 trips on the second call.

   `config` is the `:breaker` of `settings`."
  [cache config circuit failure? on-open f]
  (let [now #(System/currentTimeMillis)]
    (if-not (breaker/allow? cache config circuit (now))
      (on-open (breaker/retry-after-ms cache config circuit (now)))
      (let [result (f)]
        (if (failure? result)
          (breaker/record-failure! cache config circuit (now))
          (breaker/record-success! cache circuit))
        result))))

(defn call
  "`with-retry` inside `with-breaker`, under `settings`: `(f attempt)` is
   retried while `retryable?`, and the last result counts against the circuit
   when `failure?`. Returns that result, or `(on-open retry-after-ms)`."
  [{:keys [policy cache breaker circuit]} {:keys [retryable? failure? on-open] :as opts} f]
  (with-breaker cache breaker circuit failure? on-open
    #(with-retry policy retryable? f opts)))
