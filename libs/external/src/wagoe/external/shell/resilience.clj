(ns wagoe.external.shell.resilience
  "Retry and circuit breaker for the SMTP, IMAP and Twilio adapters, on the
   same layer as the RPC client (`wagoe.platform.resilience`).

   Same defaults as the RPC client too: two retries, and only of a call that
   never connected, since one that connected may have sent the message. The
   breaker is opt-in — pass a `:cache` — and counts calls that got no answer:
   never connected, or timed out."
  (:require [clojure.tools.logging :as log]
            [wagoe.platform.core.rpc :as rpc]
            [wagoe.platform.resilience :as resilience]))

(defn settings
  "Resilience settings for an adapter, from its config: `:retries`,
   `:retry-delay-ms`, `:cache` and `:circuit-breaker` (`:failure-threshold`,
   `:open-ms`). `circuit` names the breaker, `timeout-ms` is one attempt's
   longest wait, if the adapter sets one."
  [config circuit timeout-ms]
  (resilience/settings config
                       {:max-attempts (inc (:retries config 2))
                        :delay-ms     (:retry-delay-ms config 100)}
                       circuit timeout-ms))

(defn open-error
  "The `:error` of a call the breaker declined to make."
  [circuit retry-after-ms]
  (cond-> {:type    :circuit-open
           :message (str "Circuit open for " circuit "; not attempting the call")
           :circuit circuit}
    retry-after-ms (assoc :retry-after-ms retry-after-ms)))

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
  [{:keys [circuit] :as settings} f]
  (let [outcome
        (resilience/call
          settings
          {:retryable? #(= :rpc/unavailable (:no-answer %))
           :failure?   #(some? (:no-answer %))
           :on-open    (fn [retry-after-ms]
                         (let [error (open-error circuit retry-after-ms)]
                           (throw (ex-info (:message error) error))))
           :on-retry   (fn [{:keys [exception]} attempt delay-ms]
                         (log/warn "external call did not connect, retrying"
                                   {:circuit  circuit
                                    :attempt  attempt
                                    :delay-ms delay-ms
                                    :cause    (ex-message exception)}))}
          (fn [_attempt]
            (try {:value (f)}
                 (catch Exception e
                   {:exception e :no-answer (no-answer e)}))))]
    (if-let [e (:exception outcome)]
      (throw e)
      (:value outcome))))

(defn error-type
  "`:circuit-open` for the breaker's refusal, else `default`: the type an
   adapter's catch-all reports for `e`."
  [e default]
  (if (= :circuit-open (:type (ex-data e))) :circuit-open default))
