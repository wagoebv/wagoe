(ns wagoe.mcp.shell.system-source
  "SystemSource adapters (BOU-99 / ADR-033). The in-process adapter reflects the
   project by reading its files and (later) the live Integrant system; the
   static adapter serves a fixed snapshot for tests. All I/O lives here so the
   core resource producers stay pure."
  (:require [wagoe.mcp.ports :as ports]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

;; --- file/resource helpers --------------------------------------------------

(defn- read-edn-resource [path]
  (when-let [r (io/resource path)]
    (try (edn/read-string (slurp r)) (catch Exception _ nil))))

(defn- read-edn-file [path]
  (let [f (io/file path)]
    (when (.exists f)
      (try (edn/read-string (slurp f)) (catch Exception _ nil)))))

;; --- reflected views (best-effort; nil when not determinable) ---------------

(defn- conventions []
  (when-let [k (or (read-edn-resource "agents/knowledge.edn")
                   (read-edn-file "resources/agents/knowledge.edn"))]
    {:fc-is  (:fc-is k)
     :naming (:naming k)}))

(defn- lib-deps [deps-file]
  (let [deps (:deps (read-edn-file deps-file))
        names (keys deps)]
    {:boundary (->> names (filter #(= "wagoe" (namespace %))) (map name) sort vec)
     :external (->> names (remove #(= "wagoe" (namespace %))) (map str) sort vec)}))

(defn- has-ports? [lib-dir]
  (let [src (io/file lib-dir "src")]
    (boolean (and (.isDirectory src)
                  (some #(= "ports.clj" (.getName ^java.io.File %)) (file-seq src))))))

(defn- module-graph []
  (let [libs (io/file "libs")]
    (when (.isDirectory libs)
      (let [mods (for [d     (.listFiles libs)
                       :when (.isDirectory ^java.io.File d)
                       :let  [deps-file (io/file d "deps.edn")]
                       :when (.exists deps-file)
                       :let  [{:keys [boundary external]} (lib-deps (str deps-file))]]
                   {:name         (.getName ^java.io.File d)
                    :deps         boundary
                    :external-libs external
                    :has-ports?   (has-ports? d)})]
        {:modules (vec (sort-by :name mods))
         :edges   (vec (for [m mods, dep (:deps m)] [(:name m) dep]))}))))

(defn- kondo-rules []
  (when-let [config (read-edn-file ".clj-kondo/config.edn")]
    {:config config}))

(defn build-snapshot
  "Reflect the project rooted at the current working directory. Each view is a
   `delay`, so reading one resource never pays to build the others (e.g.
   reading conventions does not walk the libs/ tree for the module graph). A
   view that resolves to nil surfaces as :unavailable to clients. Live-system
   views (schema-registry, routes, workflows, libs) are not yet reflected —
   see ADR-033."
  []
  {:conventions  (delay (conventions))
   :module-graph (delay (module-graph))
   :kondo-rules  (delay (kondo-rules))})

;; --- adapters ---------------------------------------------------------------

(defrecord InProcessSystemSource []
  ports/SystemSource
  (snapshot [_] (build-snapshot)))

(defn in-process-system-source
  "Reflects the project at the current working directory. Re-reads on each call
   so resources always reflect current state."
  []
  (->InProcessSystemSource))

(defrecord StaticSystemSource [snap]
  ports/SystemSource
  (snapshot [_] snap))

(defn static-system-source
  "A fixed snapshot, for tests and for serving a precomputed view."
  [snap]
  (->StaticSystemSource snap))
