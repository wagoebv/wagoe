(ns wagoe.mcp.core.resources
  "Pure producers for the reflective MCP resources (BOU-99).

   A resource's content is derived from a *snapshot* of the running project —
   never hardcoded, so it cannot drift from reality (the answer to version
   skew). Producers are pure functions of the snapshot; the shell
   (wagoe.mcp.shell.system-source) builds the snapshot by reflecting the
   live system / project (in-process now; nREPL bridge later, ADR-033).

   Resources that reflect live runtime state (schema-registry, routes,
   workflows, lib/{name}) return an :unavailable placeholder until the snapshot
   carries that data, so the wiring is complete and adding reflection later is
   purely a shell concern."
  (:require [clojure.string :as str]))

(def lib-uri-prefix "wagoe://lib/")

;; Catalog — the resource set advertised over resources/list. Every resource is
;; capability :read (zero mutation); the gate is applied in the shell dispatch.
(def catalog
  [{:uri         "wagoe://conventions"
    :name        "Conventions"
    :description "FC/IS boundary rules and naming conventions for this project."
    :mimeType    "application/json"
    :capability  :read}
   {:uri         "wagoe://module-graph"
    :name        "Module graph"
    :description "Modules, their ports, dependency edges, and libraries in use."
    :mimeType    "application/json"
    :capability  :read}
   {:uri         "wagoe://kondo-rules"
    :name        "clj-kondo rules"
    :description "Active clj-kondo configuration and hooks for this project."
    :mimeType    "application/json"
    :capability  :read}
   {:uri         "wagoe://schema-registry"
    :name        "Schema registry"
    :description "Live Malli schemas, per module (reflects the running system)."
    :mimeType    "application/json"
    :capability  :read}
   {:uri         "wagoe://routes"
    :name        "Routes"
    :description "HTTP routes with their interceptor chains (reflects the running system)."
    :mimeType    "application/json"
    :capability  :read}
   {:uri         "wagoe://workflows"
    :name        "Workflows"
    :description "Workflow states, transitions, guards, and permissions."
    :mimeType    "application/json"
    :capability  :read}
   {:uri         (str lib-uri-prefix "{name}")
    :name        "Library API surface"
    :description "Public API surface of a wagoe library at its installed version. Read wagoe://lib/<name> (e.g. wagoe://lib/user)."
    :mimeType    "application/json"
    :capability  :read}])

(def ^:private unavailable
  {:status :unavailable
   :note   (str "This resource reflects live project state and requires a running "
                "system (in-process) or an nREPL bridge; not available in the "
                "current context.")})

(defn mime-type
  "MIME type for a resource uri. All reflective resources are JSON."
  [_uri]
  "application/json")

(def resource-capability
  "Reading any reflective resource is a Tier 0 (:read) operation."
  :read)

(defn force-val
  "A snapshot view may be a `delay` (the in-process adapter builds views lazily
   so reading one resource never pays to build the others). Force it; plain
   values pass through unchanged."
  [v]
  (if (delay? v) (force v) v))

(defn- fetch
  "Resolve snapshot view `k`, forcing a delay if present. A missing or nil view
   surfaces as the :unavailable placeholder."
  [snapshot k]
  (let [v (force-val (get snapshot k))]
    (if (nil? v) unavailable v)))

(defn read-resource
  "Produce the data for `uri` from a project `snapshot`. Returns the resource
   data (a Clojure value to be serialized by the shell), or nil if `uri` is not
   a known resource. Live-state resources yield an :unavailable placeholder
   until the snapshot carries their data. Only the requested view is forced."
  [snapshot uri]
  (cond
    (= uri "wagoe://conventions")     (fetch snapshot :conventions)
    (= uri "wagoe://module-graph")    (fetch snapshot :module-graph)
    (= uri "wagoe://kondo-rules")     (fetch snapshot :kondo-rules)
    (= uri "wagoe://schema-registry") (fetch snapshot :schema-registry)
    (= uri "wagoe://routes")          (fetch snapshot :routes)
    (= uri "wagoe://workflows")       (fetch snapshot :workflows)
    (str/starts-with? uri lib-uri-prefix)
    (let [lib-name (subs uri (count lib-uri-prefix))]
      (when-not (str/blank? lib-name)              ;; "wagoe://lib/" -> unknown
        (let [v (force-val (get (force-val (get snapshot :libs)) lib-name))]
          (if (nil? v) unavailable v))))
    :else nil))

(defn known-resource?
  [snapshot uri]
  (some? (read-resource snapshot uri)))

(defn available?
  "True when `uri` resolves to real content for `snapshot`.

   The `wagoe://lib/{name}` entry is a template rather than a readable uri, so
   it is judged by whether the snapshot carries a non-empty `:libs` view.
   A view that throws counts as unavailable. Forcing every view is what this
   does, and one unreadable file — a config the process cannot open — used to
   take down `resources/list`, and with it the server's whole startup."
  [snapshot {:keys [uri]}]
  (try
    (if (str/ends-with? uri "{name}")
      (boolean (seq (force-val (get snapshot :libs))))
      (let [v (read-resource snapshot uri)]
        (and (some? v) (not= :unavailable (:status v)))))
    (catch Throwable _ false)))

(defn available-catalog
  "The resources this project can actually serve.

   `resources/list` advertised all seven everywhere, and four of them answered
   with an :unavailable placeholder in any project — the agent asked for a
   schema registry, got a note explaining that it does not exist here, and had
   spent a round trip to learn it. An advertisement that never delivers is
   worse than an absence (BOU-320)."
  [snapshot]
  (vec (filter #(available? snapshot %) catalog)))
