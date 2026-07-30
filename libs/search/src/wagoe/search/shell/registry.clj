(ns wagoe.search.shell.registry
  "Load-time registry of search index definitions.

   The registry is mutable process state, so it lives in the shell — the core
   (wagoe.search.core.index) stays pure (document construction and filter-key
   conversion only). Definitions are registered at namespace load via the
   `defsearch` macro and read at runtime by the search service.

   Key functions:
   - defsearch macro       — define and register an index
   - register-search!      — low-level registration
   - get-search            — look up by index-id
   - list-searches         — list registered index ids
   - clear-registry!       — reset registry (test fixtures)")

;; =============================================================================
;; In-process Registry
;; =============================================================================

(defonce ^:private registry (atom {}))

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

;; =============================================================================
;; defsearch Macro
;; =============================================================================

(defmacro defsearch
  "Define a search index configuration and register it globally.

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
