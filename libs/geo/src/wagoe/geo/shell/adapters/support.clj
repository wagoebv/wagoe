(ns wagoe.geo.shell.adapters.support
  "Rate limiting and response parsing shared by the provider adapters."
  (:require [clojure.tools.logging :as log]))

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
