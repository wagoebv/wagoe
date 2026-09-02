(ns wagoe.platform.system
  "Reading the running system — platform's introspection API.

   The sanctioned surface for a tool that needs to know what is running.
   Development tooling used to answer that from `integrant.repl.state/system`,
   which only a REPL `(go)` fills, so a server started by `wagoe.main` was
   invisible to it: the dev dashboard reported three components for a system of
   forty-three (BOU-400). The system has always been recorded — in
   `wagoe.platform.shell.system.wiring`, which is platform's shell, and naming
   it from another module is the violation `bb check:ports` exists to catch.

   Same shape as `wagoe.platform.database`, and for the same reason: a narrow
   non-shell namespace over machinery that stays internal (BOU-303).

   Read-only on purpose. Starting and stopping a system is a composition root's
   job — `wagoe.main` and the REPL both call `wiring/start!` directly, and they
   are the two places allowed to."
  (:require [wagoe.platform.shell.system.wiring :as wiring]))

(defn running
  "The running system map, or nil when nothing has been started.

   nil is the honest answer for a tool asked before boot or after shutdown, and
   is what callers already handle — the dashboard falls back to what it can
   reach through its own Integrant refs."
  []
  (wiring/system))
