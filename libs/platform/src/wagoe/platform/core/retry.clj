(ns wagoe.platform.core.retry
  "How long to wait between attempts, and how long a whole run of them can take.

   Shared by the RPC client and the libs/external adapters, so a flaky
   third-party API and a flaky internal service get one policy. Which failures
   are worth retrying is the caller's call; it knows what a second attempt
   could repeat.

   FC/IS: pure.")

(def default-policy
  {:max-attempts 1
   :delay-ms     0
   ;; :linear waits delay×1, delay×2, …; :exponential delay×1, delay×2, delay×4, …
   :backoff      :linear
   :max-delay-ms nil})

(defn delay-ms
  "Delay after the `attempt`-th failed attempt (1-based), capped at
   `:max-delay-ms` when set."
  [policy attempt]
  (let [{:keys [delay-ms backoff max-delay-ms]} (merge default-policy policy)
        n     (max 1 attempt)
        delay (case backoff
                :exponential (* delay-ms (bit-shift-left 1 (min 30 (dec n))))
                (* delay-ms n))]
    (if max-delay-ms (min max-delay-ms delay) delay)))

(defn retry?
  "Whether to make another attempt after the `attempt`-th one failed with a
   failure the caller judged `retryable?`."
  [policy attempt retryable?]
  (boolean (and retryable? (< attempt (:max-attempts (merge default-policy policy))))))

(defn budget-ms
  "Longest a run of attempts can take: every attempt's timeout plus the delays
   slept between them. A breaker's probe lease must outlive this, or it expires
   mid-probe and a second caller sends a second probe."
  [policy timeout-ms]
  (let [n (:max-attempts (merge default-policy policy))]
    (+ (* n (or timeout-ms 0))
       (reduce + 0 (map #(delay-ms policy %) (range 1 n))))))
