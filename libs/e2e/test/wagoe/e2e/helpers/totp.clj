(ns wagoe.e2e.helpers.totp
  "Generate TOTP codes for e2e MFA tests. Uses one-time, which is already
   used by the wagoe.user MFA implementation."
  (:require [one-time.core :as ot]))

(defn current-code
  "Returns the current 6-digit TOTP code for a base32 secret."
  [secret]
  (format "%06d" (ot/get-totp-token secret)))

(defn fresh-code
  "Waits until we're at least `safety-ms` away from a TOTP window rollover,
   then returns a fresh code. Reduces flakiness near window boundaries."
  ([secret] (fresh-code secret 2000))
  ([secret safety-ms]
   (let [ms-into-window (mod (System/currentTimeMillis) 30000)
         ms-left        (- 30000 ms-into-window)]
     (when (< ms-left safety-ms)
       (Thread/sleep (long (+ ms-left 100))))
     (current-code secret))))
