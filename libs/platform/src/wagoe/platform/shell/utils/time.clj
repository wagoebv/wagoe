(ns wagoe.platform.shell.utils.time
  "The clock, read once per library rather than once per namespace.

   `current-timestamp` was defined identically in `utils.error-handling` and
   `interfaces.http.middleware` (BOU-302).

   A shell namespace, because reading a clock is the shell's job: core
   functions take the instant as an argument."
  (:import [java.time Instant]))

(defn now
  "The current instant."
  []
  (Instant/now))
