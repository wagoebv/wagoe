(ns wagoe.events.core.outbox
  "Relay policy for the transactional outbox: when a failing row is
   dead-lettered, and how long the relay waits after a failed pass (BOU-679).

   FC/IS: pure.")

(defn opts-problem
  "Why outbox `opts` are unusable, or nil."
  [opts]
  (some (fn [k]
          (let [v (get opts k)]
            (when-not (pos-int? v)
              (str k " must be a positive integer, not " (pr-str v)))))
        [:max-attempts :dead-letter-after-ms]))

(defn failure-outcome
  "What a failed publish does to `row` at database time `now`: its new
   `:attempts`, `:first-failed-ms` and `:last-error`, and `:dead?`. A row is
   dead-lettered once it has failed `:max-attempts` times over at least
   `:dead-letter-after-ms`, so a bus outage shorter than that loses nothing;
   a `:fatal?` error dead-letters at once."
  [{:keys [attempts first-failed-ms]} error now {:keys [max-attempts dead-letter-after-ms]}]
  (let [attempts (inc attempts)
        first    (or first-failed-ms now)
        message  (str (:type error) (some->> (:message error) (str " ")))]
    {:attempts        attempts
     :first-failed-ms first
     :last-error      (subs message 0 (min 1000 (count message)))
     :dead?           (boolean (or (:fatal? error)
                                   (and (>= attempts max-attempts)
                                        (>= (- now first) dead-letter-after-ms))))}))

(defn next-delay
  "Milliseconds until the next relay pass: `poll-ms` after a good pass,
   doubling after each failed one up to `max-backoff-ms`."
  [{:keys [poll-ms max-backoff-ms]} delay failed?]
  (if failed?
    (max poll-ms (min max-backoff-ms (* 2 delay)))
    poll-ms))
