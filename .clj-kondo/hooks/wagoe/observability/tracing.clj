(ns hooks.wagoe.observability.tracing
  "clj-kondo hook so `with-span`'s binding vector is understood as introducing
   the span symbol (like a `let` binding)."
  (:require [clj-kondo.hooks-api :as api]))

(defn with-span
  "(with-span tracer [sym name attrs?] & body)
   -> (let [sym nil] tracer name attrs? body...)"
  [{:keys [node]}]
  (let [[_ tracer binding-vec & body] (:children node)
        [sym & rest-binding] (:children binding-vec)
        new-node (api/list-node
                  (list*
                   (api/token-node 'let)
                   (api/vector-node [sym (api/token-node nil)])
                   tracer
                   (concat rest-binding body)))]
    {:node new-node}))
