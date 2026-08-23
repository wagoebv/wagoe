(ns wagoe.platform.shell.feature-flags
  "Reading feature flags from the process environment.

   `wagoe.core.config.feature-flags` decides what a flag means, given an
   environment map. This namespace is the one line that fetches the map, and it
   lives in a shell because reading `System/getenv` is I/O — ambient input that
   changes the answer without changing the arguments.

   Both halves used to be in the core namespace, whose docstring promised no
   side effects while five of its functions called `(System/getenv)` in a
   convenience arity (BOU-302)."
  (:require [wagoe.core.config.feature-flags :as flags]))

(defn env
  "The process environment as a Clojure map."
  []
  (into {} (System/getenv)))

(defn enabled?
  "Whether `flag-key` is on, according to the process environment."
  [flag-key]
  (flags/enabled? flag-key (env)))

(defn all-flags
  "Every known flag's status, according to the process environment."
  []
  (flags/all-flags (env)))

(defn flag-info
  "What `flag-key` is and whether it is on, according to the process environment."
  [flag-key]
  (flags/flag-info flag-key (env)))

(defn add-flags-to-config
  "`config` with `:feature-flags` added from the process environment."
  [config]
  (flags/add-flags-to-config config (env)))
