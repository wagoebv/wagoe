(ns wagoe.platform.throttle
  "Logging a repeating event once per interval, by key. Outside `shell/` so
   other modules can call it, as `wagoe.platform.database`.")

(def interval-ms
  "How long one key stays quiet after its thunk ran."
  60000)

(defn once-per-interval
  "A fn of a key and a thunk that calls the thunk at most once per
   `interval-ms` for the same key."
  []
  (let [seen (atom {})]
    (fn [k f]
      (let [now       (System/currentTimeMillis)
            fresh     (fn [m] (let [m (if (> (count m) 10000) {} m)]
                                (if (< (- now (get m k 0)) interval-ms) m (assoc m k now))))
            [old new] (swap-vals! seen fresh)]
        (when (not= (get old k) (get new k))
          (f))))))
