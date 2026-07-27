(ns wagoe.push.core.analytics)

(defn calculate-rates
  "Pure: compute delivery-rate and open-rate from raw counts."
  [{:keys [sent delivered opened] :as stats}]
  (cond-> stats
    (pos? sent) (assoc :delivery-rate (double (/ delivered sent)))
    (pos? delivered) (assoc :open-rate (double (/ opened delivered)))))
