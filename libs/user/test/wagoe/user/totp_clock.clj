(ns wagoe.user.totp-clock
  "Generating a TOTP code and verifying it read the clock twice, so a test that
   straddled a 30 s window boundary failed (BOU-722). These freeze the clock
   that verification reads and generate codes against it."
  (:require [one-time.core :as otp]
            [wagoe.user.shell.mfa :as mfa])
  (:import (java.time Instant)
           (java.util Date)))

(def window-edge
  "The last millisecond of a 30 s TOTP window."
  (Instant/ofEpochMilli 1700000009999))

(defn frozen
  "Fixture: verification runs at `window-edge`."
  [t]
  (binding [mfa/*now* window-edge]
    (t)))

(defn code
  "The six-digit code for `secret` at `at`, default the clock verification reads."
  ([secret] (code secret (or mfa/*now* (Instant/now))))
  ([secret ^Instant at]
   (format "%06d" (otp/get-totp-token secret {:date (Date/from at)}))))
