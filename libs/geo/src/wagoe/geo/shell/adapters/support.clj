(ns wagoe.geo.shell.adapters.support
  "Rate limiting and response parsing shared by the provider adapters."
  (:require [clojure.tools.logging :as log]))

(defn enforce-rate-limit!
  "Block until `min-interval-ms` have passed since the previous request.
   Locks so concurrent callers are spaced too, not just sequential ones."
  [last-request-ms min-interval-ms]
  (locking last-request-ms
    (let [wait (- (+ @last-request-ms min-interval-ms) (System/currentTimeMillis))]
      (when (pos? wait)
        (Thread/sleep wait))
      (reset! last-request-ms (System/currentTimeMillis)))))

(defn parse-response
  "Apply `parse-fn` to a provider's result item. nil item means no match.
   A result without numeric :lat/:lng is a malformed response: WARN and nil."
  [provider parse-fn item]
  (when item
    (let [result (try (parse-fn item) (catch Exception _ nil))]
      (if (and (number? (:lat result)) (number? (:lng result)))
        result
        (do (log/warn "Geo provider returned a malformed response"
                      {:provider provider
                       :keys     (if (map? item) (keys item) (type item))})
            nil)))))
