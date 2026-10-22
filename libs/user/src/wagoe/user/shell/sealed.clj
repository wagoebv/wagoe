(ns wagoe.user.shell.sealed
  "Encrypted, short-lived cookie values that carry one sign-in step to the
   next without server state."
  (:require [buddy.sign.jwt :as jwt]
            [clojure.string :as str])
  (:import (java.time Instant)))

(defn- now-epoch [] (.getEpochSecond (Instant/now)))

(defn seal
  "Encrypt `claims` under the 32-byte `key` for a cookie of kind `typ` that
   expires in `ttl` seconds."
  [key typ claims ttl]
  (jwt/encrypt (assoc claims :typ (name typ) :exp (+ (now-epoch) ttl))
               key {:alg :dir :enc :a256gcm}))

(defn unseal
  "The claims of a sealed value of kind `typ`, or nil when it is absent,
   forged, expired, or sealed for another step."
  [key typ value]
  (when-not (str/blank? value)
    (let [claims (try (jwt/decrypt value key {:alg :dir :enc :a256gcm})
                      (catch Exception _ nil))]
      (when (= (name typ) (:typ claims)) claims))))
