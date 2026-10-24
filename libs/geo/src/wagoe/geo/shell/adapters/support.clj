(ns wagoe.geo.shell.adapters.support
  "Rate limiting and response parsing shared by the provider adapters."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]))

(defn enforce-rate-limit!
  "Block until `min-interval-ms` have passed since the previous request, and
   return the slot this caller got. Each caller reserves its slot atomically, so
   concurrent callers are spaced too, and none sleeps holding a monitor, which
   would pin a virtual thread's carrier on JDK 21."
  [last-request-ms min-interval-ms]
  (let [now  (System/currentTimeMillis)
        slot (swap! last-request-ms #(max now (+ % min-interval-ms)))]
    (when (> slot now)
      (Thread/sleep (- slot now)))
    slot))

(defn parse-response
  "Apply `parse-fn` to a provider's result item. nil item means no match.
   A result without numeric :lat/:lng is a malformed response: WARN and nil."
  [provider parse-fn item]
  (when item
    (let [result (try (parse-fn item) (catch Exception e e))
          data   {:provider provider
                  :keys     (if (map? item) (keys item) (type item))}]
      (cond
        (instance? Throwable result)
        (log/warn result "Geo provider returned a malformed response" data)

        (and (number? (:lat result)) (number? (:lng result)))
        result

        :else
        (log/warn "Geo provider returned a malformed response" data)))))

(defn street-fields
  "The GeoResult's :street, :house-number and :house-number-addition, leaving
   out what the provider did not give."
  [street house-number addition]
  (into {}
        (remove (comp str/blank? val))
        {:street                street
         :house-number          (some-> house-number str)
         :house-number-addition addition}))

(defn query-text
  "An AddressQuery as one free-text line: \"Damrak 1 A, 1012 LG, Amsterdam, Netherlands\".
   The house number follows the address."
  [{:keys [address postcode house-number house-number-addition city country]}]
  (let [street (not-empty (str/join " " (remove str/blank? [address (some-> house-number str)
                                                             house-number-addition])))]
    (str/join ", " (remove nil? [street postcode city (or country "Netherlands")]))))
