(ns wagoe.external.shell.resilience
  "Retry and circuit breaker for the SMTP, IMAP and Twilio adapters, on the
   same layer as the RPC client (`wagoe.platform.resilience`).

   Same defaults as the RPC client too: two retries, and only of a call that
   never connected, since one that connected may have sent the message. The
   breaker is opt-in — pass a `:cache` — and counts calls that got no answer:
   never connected, or timed out."
  (:require [clojure.tools.logging :as log]
            [wagoe.platform.core.circuit-breaker :as cb]
            [wagoe.platform.core.retry :as retry]
            [wagoe.platform.core.rpc :as rpc]
            [wagoe.platform.resilience :as resilience]))

(defn settings
  "Resilience settings for an adapter, from its config: `:retries`,
   `:retry-delay-ms`, `:cache` and `:circuit-breaker` (`:failure-threshold`,
   `:open-ms`). `circuit` names the breaker, `timeout-ms` is one attempt's
   longest wait, if the adapter sets one."
  [config circuit timeout-ms]
  (let [policy {:max-attempts (inc (:retries config 2))
                :delay-ms     (:retry-delay-ms config 100)}
        cb-cfg (merge cb/default-config
                      {:probe-lease-ms (retry/budget-ms policy timeout-ms)}
                      (:circuit-breaker config))]
    (when-let [problem (and (:circuit-breaker config) (cb/config-problem cb-cfg))]
      (throw (ex-info problem {:type            :validation-error
                               :circuit-breaker (:circuit-breaker config)})))
    {:policy policy :cache (:cache config) :breaker cb-cfg :circuit circuit}))

(defn- no-answer
  "`:rpc/unavailable` if `e` or a cause says the call never connected,
   `:rpc/timeout` if it connected and got no answer, else nil. javax.mail
   wraps the socket exception, so the causes are searched."
  [^Throwable e]
  (some #(#{:rpc/unavailable :rpc/timeout} (rpc/classify-exception %))
        (take-while some? (iterate ex-cause e))))

(defn call!
  "Run `(f)` under `settings`. Returns its value or rethrows its last
   exception, so an adapter's own error mapping still applies. When the breaker
   is open, throws ex-info with `:type :circuit-open` instead of calling `f`."
  [{:keys [policy cache breaker circuit]} f]
  (let [outcome
        (resilience/with-breaker
          cache breaker circuit
          #(some? (:no-answer %))
          (fn [retry-after-ms] {:open retry-after-ms})
          (fn []
            (resilience/with-retry
              policy
              #(= :rpc/unavailable (:no-answer %))
              (fn [_attempt]
                (try {:value (f)}
                     (catch Exception e
                       {:exception e :no-answer (no-answer e)})))
              {:on-retry (fn [{:keys [exception]} attempt delay-ms]
                           (log/warn "external call did not connect, retrying"
                                     {:circuit  circuit
                                      :attempt  attempt
                                      :delay-ms delay-ms
                                      :cause    (ex-message exception)}))})))]
    (cond
      (contains? outcome :value) (:value outcome)
      (:exception outcome)       (throw (:exception outcome))
      :else (throw (ex-info (str "Circuit open for " circuit "; not attempting the call")
                            {:type           :circuit-open
                             :circuit        circuit
                             :retry-after-ms (:open outcome)})))))

(defn error-type
  "`:circuit-open` for the breaker's refusal, else `default`: the type an
   adapter's catch-all reports for `e`."
  [e default]
  (if (= :circuit-open (:type (ex-data e))) :circuit-open default))
