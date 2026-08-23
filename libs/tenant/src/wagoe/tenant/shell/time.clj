(ns wagoe.tenant.shell.time
  "The clock, read once per library rather than once per namespace.

   `current-timestamp` was defined three times in this library — in
   `service`, `membership-service` and `invite-service` — with the same body
   each time, and one of them public, so a one-line helper was part of the
   library's API (BOU-302).

   It lives in a shell namespace because that is the layer allowed to read a
   clock: core functions take the instant as an argument."
  (:import [java.time Instant]))

(defn now
  "The current instant."
  []
  (Instant/now))
