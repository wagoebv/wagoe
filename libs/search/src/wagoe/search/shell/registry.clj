(ns wagoe.search.shell.registry
  "Deprecated: a process-global registry of search index definitions.

   Hand definitions to `:wagoe/search` instead — under `:indexes` in config or
   a module's `:search` part — and bind them with
   `wagoe.search.core.index/defsearch`. The search service still reads this
   registry for an id its component does not hold, and logs once when it does.

   Key functions:
   - defsearch macro       — define and register an index
   - register-search!      — low-level registration
   - get-search            — look up by index-id
   - list-searches         — list registered index ids
   - clear-registry!       — reset registry (test fixtures)"
  (:require [clojure.tools.logging :as log]))

;; =============================================================================
;; In-process Registry
;; =============================================================================

(defonce ^:private registry (atom {}))

(defonce ^:private warned? (atom false))

(defn register-search!
  "Register a SearchDefinition in the global registry.

   Args:
     definition - SearchDefinition map with :id keyword

   Returns:
     :id keyword"
  [definition]
  (let [id (:id definition)]
    (swap! registry assoc id definition)
    id))

(defn get-search
  "Retrieve a registered SearchDefinition by id.

   Args:
     index-id - keyword

   Returns:
     SearchDefinition map or nil"
  [index-id]
  (get @registry index-id))

(defn list-searches
  "List all registered search index ids.

   Returns:
     Vector of keyword ids"
  []
  (vec (keys @registry)))

(defn clear-registry!
  "Remove all registered definitions. Use in test fixtures.

   Returns:
     nil"
  []
  (reset! registry {})
  nil)

(defn fallback
  "The definition registered for `index-id`, or nil. Logs a deprecation
   warning the first time it finds one."
  [index-id]
  (when-let [definition (get-search index-id)]
    (when (compare-and-set! warned? false true)
      (log/warn (str "Search index " index-id " comes from wagoe.search.shell.registry,"
                     " which is deprecated. Hand it to :wagoe/search under :indexes,"
                     " or as a module's :search part.")))
    definition))

;; =============================================================================
;; defsearch Macro
;; =============================================================================

(defmacro defsearch
  "Deprecated: use wagoe.search.core.index/defsearch and hand the var to
   :wagoe/search.

   Binds a var and registers in the in-process search registry.

   Example:
     (defsearch product-search
       {:id          :product-search
        :entity-type :product
        :language    :english
        :fields      [{:name :title       :weight :a}
                      {:name :description :weight :b}
                      {:name :tags        :weight :c}]
        :options     {:highlight? true
                      :trigrams?  true}})

   This is equivalent to:
     (def product-search {...})
     (register-search! product-search)"
  [var-name definition]
  `(do
     (def ~var-name ~definition)
     (register-search! ~definition)
     ~var-name))
