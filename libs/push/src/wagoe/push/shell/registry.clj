(ns wagoe.push.shell.registry
  "Load-time registry of push notification definitions.

   The registry is mutable process state, so it lives in the shell — the core
   (wagoe.push.core.notification) stays pure (rendering only). Definitions are
   registered at namespace load via the `defpush` macro and read at runtime by
   the push service/jobs.")

(defonce ^:private registry-atom (atom {}))

(defn register-push!
  "Register (or replace) a push definition by its :id. Returns the definition."
  [definition]
  (swap! registry-atom assoc (:id definition) definition)
  definition)

(defn get-push
  "Look up a registered push definition by id, or nil."
  [id]
  (get @registry-atom id))

(defn list-pushes
  "Ids of all registered push definitions."
  []
  (vec (keys @registry-atom)))

(defn clear-registry!
  "Remove all registered definitions (test/dev helper)."
  []
  (reset! registry-atom {}))

(defmacro defpush
  "Define and register a push notification type."
  [sym definition-map]
  `(do
     (def ~sym ~definition-map)
     (register-push! ~sym)
     ~sym))
