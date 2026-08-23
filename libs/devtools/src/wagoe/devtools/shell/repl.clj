(ns wagoe.devtools.shell.repl
  "Shell REPL helpers that access the running Integrant system.
   These functions take system components as arguments — they do NOT directly
   access the running system. Wiring happens in dev/repl/user.clj."
  (:require [wagoe.devtools.core.documentation :as docs]
            [wagoe.platform.database :as db]
            [cheshire.core :as json]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.walk]
            [integrant.core :as ig]
            [reitit.core]
            [reitit.ring])
  (:import (java.io ByteArrayInputStream)))

;; =============================================================================
;; Route extraction
;; =============================================================================

(def ^:private boundary-module-pattern
  "Regex to extract module name from a handler string or compiled fn.
   Matches both 'wagoe.user.shell.http/list-users' (symbol form)
   and 'wagoe.user.shell.http$list_users@...' (compiled fn form)."
  #"wagoe\.([^.$]+)\.")

(defn extract-module
  "Extract module name from a handler string.
   Works with both qualified symbol strings and compiled fn str representations.
   Returns the module name string (e.g. 'user', 'admin', 'platform') or nil
   for non-wagoe handlers."
  [handler-str]
  (when (string? handler-str)
    (when-let [m (re-find boundary-module-pattern handler-str)]
      (second m))))

(defn- routes-from-router
  "Extract route maps from a Reitit router instance."
  [router]
  (let [http-methods [:get :post :put :patch :delete :head :options]]
    (for [[path data] (reitit.core/routes router)
          method http-methods
          :when (contains? data method)
          :let [handler-fn (get-in data [method :handler])
                handler-str (str handler-fn)]]
      {:method  method
       :path    path
       :handler handler-str
       :module  (extract-module handler-str)})))

(defn extract-routes-from-handler
  "Extract route info from a compiled Ring handler backed by Reitit.
   Checks handler metadata for :reitit/router first (set by compile-routes),
   then falls back to reitit.ring/get-router for unwrapped handlers.
   Returns a seq of {:method :path :handler :module} maps, or nil on error."
  [http-handler]
  (try
    (if-let [router (:reitit/router (meta http-handler))]
      (routes-from-router router)
      (when-let [router (reitit.ring/get-router http-handler)]
        (routes-from-router router)))
    (catch Exception _
      nil)))

;; =============================================================================
;; Request simulation
;; =============================================================================

(defn- encode-query-string
  "Encode a map of query params into a URL query string.
   Values are URL-encoded. Keys are converted to strings via `name` if keywords."
  [params]
  (str/join "&"
            (map (fn [[k v]]
                   (str (java.net.URLEncoder/encode (name k) "UTF-8")
                        "="
                        (java.net.URLEncoder/encode (str v) "UTF-8")))
                 params)))

(defn build-simulate-request
  "Build a Ring request map for simulating an HTTP call.

   opts keys:
     :body         — EDN/map body, will be JSON-encoded
     :raw-body     — raw string body, passed as-is (for non-JSON text replay)
     :raw-bytes    — raw byte array, passed directly (for binary replay)
     :headers      — extra headers map
     :query-params — map of query params (encoded into :query-string for wrap-params)
     :query-string — raw query string (used as-is, takes precedence over :query-params)"
  [method path opts]
  (let [{:keys [body raw-body raw-bytes headers query-params query-string]} opts
        base-headers (if (or raw-body raw-bytes)
                       {"accept" "application/json"}
                       {"content-type" "application/json"
                        "accept"       "application/json"})
        all-headers (merge base-headers headers)
        request (cond-> {:request-method (keyword (str/lower-case (name method)))
                         :uri            path
                         :headers        all-headers
                         :server-name    "localhost"
                         :server-port    3000
                         :scheme         :http}
                  query-string (assoc :query-string query-string)
                  (and query-params (not query-string)) (assoc :query-string (encode-query-string query-params))
                  body
                  (assoc :body
                         (ByteArrayInputStream.
                          (.getBytes (json/generate-string body) "UTF-8")))
                  raw-body
                  (assoc :body
                         (ByteArrayInputStream.
                          (.getBytes (str raw-body) "UTF-8")))
                  raw-bytes
                  (assoc :body
                         (ByteArrayInputStream. ^bytes raw-bytes)))]
    request))

(defn simulate-request
  "Send a simulated HTTP request to the given Ring handler.
   Returns {:status :headers :body} with body JSON-decoded when possible."
  [http-handler method path opts]
  (let [request (build-simulate-request method path opts)
        response (http-handler request)
        raw-body (:body response)
        body-str (cond
                   (string? raw-body)
                   raw-body
                   (instance? java.io.InputStream raw-body)
                   (slurp raw-body)
                   :else
                   (str raw-body))
        parsed-body (try
                      (json/parse-string body-str true)
                      (catch Exception _
                        body-str))]
    {:status  (:status response)
     :headers (:headers response)
     :body    parsed-body}))

;; =============================================================================
;; Data exploration
;; =============================================================================

(defn build-query-map
  "Build a HoneySQL query map for the given table.

   opts keys:
     :where    — HoneySQL where clause
     :order-by — HoneySQL order-by clause
     :limit    — row limit (default 20)"
  [table opts]
  (let [{:keys [where order-by limit]} opts
        base {:select [:*]
              :from   [table]
              :limit  (or limit 20)}]
    (cond-> base
      where    (assoc :where where)
      order-by (assoc :order-by order-by))))

(defn run-query
  "Execute a SELECT query against the given table via db/execute-query!.
   Returns a seq of row maps."
  [db-context table opts]
  (let [query (build-query-map table opts)]
    (db/execute-query! db-context query)))

(defn count-rows
  "Count total rows in the given table. Returns a single integer."
  [db-context table]
  (let [query {:select [[[:count :*] :count]]
               :from   [table]}
        result (db/execute-one! db-context query)]
    (:count result)))

;; =============================================================================
;; Handler tracing
;; =============================================================================

(defonce ^:private active-traces
  (atom {}))

(defn set-trace!
  "Register a trace for the given handler name.
   Returns a confirmation message string."
  [handler-name]
  (swap! active-traces assoc handler-name true)
  (str "Tracing enabled for: " handler-name))

(defn remove-trace!
  "Remove a trace for the given handler name.
   Returns a confirmation message string."
  [handler-name]
  (swap! active-traces dissoc handler-name)
  (str "Tracing disabled for: " handler-name))

(defn active-traces-list
  "Return the set of handler names currently being traced."
  []
  (keys @active-traces))

;; =============================================================================
;; Quality tools
;; =============================================================================

(defn run-tests
  "Run Kaocha tests for the given module keyword and optional meta filter keyword.
   Shells out to clojure -M:test so it works from any REPL session.
   Returns {:exit <int> :output <string>}."
  [module meta-filter]
  (let [cmd (str "clojure -M:test :" (name module)
                 (when meta-filter
                   (str " --focus-meta :" (name meta-filter))))
        result (shell/sh "bash" "-c" cmd)]
    {:exit   (:exit result)
     :output (str (:out result) (:err result))}))

(defn run-lint
  "Run clj-kondo linting across src, test, and all library sources.
   Uses a shell wrapper so glob patterns are expanded correctly.
   Returns {:exit <int> :output <string>}."
  []
  (let [result (shell/sh "bash" "-c"
                         "clojure -M:clj-kondo --lint src test libs/*/src libs/*/test")]
    {:exit   (:exit result)
     :output (str (:out result) (:err result))}))

(defn run-checks
  "Run all quality checks via 'bb check'.
   Returns {:exit <int> :output <string>}."
  []
  (let [result (shell/sh "bb" "check" "--ci")]
    {:exit   (:exit result)
     :output (str (:out result) (:err result))}))

;; =============================================================================
;; Documentation
;; =============================================================================

(defn show-doc
  "Look up and print documentation for the given topic keyword.
   Shows available topics when the topic is unknown."
  [topic]
  (if-let [entry (docs/lookup topic)]
    (docs/format-doc entry)
    (str "Unknown topic: " topic
         "\n\nAvailable topics: "
         (str/join ", " (map str (docs/list-topics))))))

;; =============================================================================
;; Component restart
;; =============================================================================

(defn find-dependents
  "Find Integrant keys that transitively depend on component-key.
   Returns the full transitive closure in dependency-safe restart order:
   if dependent A is also referenced by dependent B, A appears before B
   so that restarting in sequence leaves every component wired to fresh
   instances rather than stale refs.

   Algorithm: BFS to collect the dependent set, then topological sort
   within that set using Kahn's algorithm."
  [config component-key]
  (let [;; For a key k, find all config keys whose values reference k
        direct-dependents
        (fn [k]
          (let [ref? (fn check [v]
                       (cond
                         (= v (ig/ref k)) true
                         (map? v) (some check (vals v))
                         (sequential? v) (some check v)
                         :else false))]
            (sort (for [[ck cv] config
                        :when (and (not= ck k) (ref? cv))]
                    ck))))

        ;; BFS to collect full transitive dependent set
        all-dependents
        (loop [queue   (into clojure.lang.PersistentQueue/EMPTY [component-key])
               visited #{component-key}
               result  #{}]
          (if-let [current (peek queue)]
            (let [deps     (direct-dependents current)
                  new-deps (remove visited deps)]
              (recur (into (pop queue) new-deps)
                     (into visited new-deps)
                     (into result new-deps)))
            result))]

    (if (< (count all-dependents) 2)
      (vec all-dependents)
      ;; Topological sort (Kahn's) within the dependent subgraph.
      ;; Edge: A -> B means "A is depended upon by B" (B refs A),
      ;; so A must restart before B.
      (let [;; For each dependent, find which OTHER dependents it references
            ;; (i.e. its dependencies within the subgraph)
            deps-within
            (reduce (fn [m k]
                      (let [refs-in-val
                            (fn collect [v]
                              (cond
                                (ig/ref? v) (let [rk (ig/ref-key v)]
                                              (when (and (contains? all-dependents rk)
                                                         (not= rk k))
                                                [rk]))
                                (map? v) (mapcat collect (vals v))
                                (sequential? v) (mapcat collect v)
                                :else nil))
                            ;; Also count the changed component-key as a dependency
                            ;; (it is restarted first, before any dependent)
                            internal-deps (set (concat
                                                (refs-in-val (get config k))
                                                (when (some #(= % (ig/ref component-key))
                                                            (tree-seq coll? seq (get config k)))
                                                  [])))]
                        (assoc m k (disj internal-deps k))))
                    {}
                    all-dependents)

            ;; In-degree: how many other dependents does each key depend on?
            in-degree (reduce-kv (fn [m k deps] (assoc m k (count deps)))
                                 {} deps-within)]

        (loop [queue  (into clojure.lang.PersistentQueue/EMPTY
                            (sort (filter #(zero? (get in-degree %)) (keys in-degree))))
               degree in-degree
               result []]
          (if-let [current (peek queue)]
            (let [;; Find dependents of current within the subgraph
                  consumers (sort (for [k all-dependents
                                        :when (contains? (get deps-within k) current)]
                                    k))
                  updated   (reduce (fn [d c] (update d c dec)) degree consumers)
                  new-ready (filter #(zero? (get updated %)) consumers)]
              (recur (into (pop queue) (sort new-ready))
                     updated
                     (conj result current)))
            (do (when (not= (count result) (count all-dependents))
                  (println (str "Warning: find-dependents could not sort all dependents — "
                                "possible cycle among: "
                                (pr-str (remove (set result) all-dependents)))))
                result)))))))

(defn restart-component
  "Halt and reinitialize a single Integrant component.

   system-var:    the var holding the running system (a plain def, not an atom)
   config:        the Integrant config map
   component-key: the key to restart

   Note: integrant.repl.state/system is a plain def, not an atom.
   We use alter-var-root to update it atomically.

   Warning: dependents that captured the old instance are NOT updated.
   Use (reset) for cascading restarts."
  [system-var config component-key]
  (let [system (var-get system-var)]
    (if-not (contains? system component-key)
      (do
        (println (format "Component %s not found in system." component-key))
        (println "Available components:")
        (doseq [k (sort (keys system))]
          (println (str "  " k)))
        nil)
      (let [dependents (find-dependents config component-key)]
        (println (format "Restarting %s..." component-key))
        (alter-var-root system-var
                        (fn [sys]
                          (ig/halt-key! component-key (get sys component-key))
                          (let [expanded-config (get (ig/expand config) component-key)
                                ;; Resolve ig/ref values to live instances from the
                                ;; running system so init-key receives real objects,
                                ;; not raw Ref markers.
                                resolved-config (clojure.walk/postwalk
                                                 (fn [v]
                                                   (if (ig/ref? v)
                                                     (get sys (ig/ref-key v) v)
                                                     v))
                                                 expanded-config)
                                new-val (ig/init-key component-key resolved-config)]
                            ;; If we're restarting the HTTP handler, sync the live
                            ;; handler-atom so Jetty serves the new handler immediately.
                            (when (= component-key :wagoe/http-handler)
                              (try
                                (require 'wagoe.platform.shell.system.wiring)
                                (let [swap-fn (resolve 'wagoe.platform.shell.system.wiring/swap-handler!)]
                                  (swap-fn new-val))
                                (catch Exception e
                                  (println (str "  Warning: failed to sync live handler: "
                                                (.getMessage e))))))
                            (assoc sys component-key new-val))))
        (println (format "=> %s restarted." component-key))
        (when (seq dependents)
          (println (format "  Warning: %d component(s) hold references to the old instance and were NOT restarted:"
                           (count dependents)))
          (doseq [d dependents]
            (println (str "    " d)))
          (println "  Use (reset) for a full cascading restart."))
        (get (var-get system-var) component-key)))))
