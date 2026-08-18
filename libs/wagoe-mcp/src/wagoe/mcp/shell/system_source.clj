(ns wagoe.mcp.shell.system-source
  "SystemSource adapters (BOU-99 / ADR-033). The in-process adapter reflects the
   project by reading its files and (later) the live Integrant system; the
   static adapter serves a fixed snapshot for tests. All I/O lives here so the
   core resource producers stay pure."
  (:require [wagoe.mcp.ports :as ports]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; --- file/resource helpers --------------------------------------------------

(defn- read-edn-resource [path]
  (when-let [r (io/resource path)]
    (try (edn/read-string (slurp r)) (catch Exception _ nil))))

(defn- read-edn-file
  "EDN at `path`, resolved against `root` when `path` is relative.

   `(io/file nil \"/abs/path\")` throws \"not a relative path\", so the root is
   only applied when there is one."
  [root path]
  (let [f (if root (io/file root path) (io/file path))]
    (when (.exists f)
      (try (edn/read-string (slurp f)) (catch Exception _ nil)))))

;; --- reflected views (best-effort; nil when not determinable) ---------------

(defn- conventions
  "FC/IS and naming rules, from the knowledge base in the wagoe-tools jar.

   It used to be read from `resources/agents/knowledge.edn`, a path that exists
   in the framework repository and in no project — so every editor agent that
   installed this server got `:unavailable` for the one resource that describes
   how to write Wagoe code (BOU-320). The file override stays for a project
   that wants to say something different."
  [root]
  (when-let [k (or (read-edn-file root "resources/agents/knowledge.edn")
                   (read-edn-resource "wagoe/agents/knowledge.edn"))]
    {:fc-is    (:fc-is k)
     :naming   (:naming k)
     :pitfalls (:pitfalls k)}))

(defn- lib-deps [deps-file]
  (let [deps (:deps (read-edn-file nil deps-file))
        names (keys deps)]
    {:wagoe (->> names (filter #(= "wagoe" (namespace %))) (map name) sort vec)
     :external (->> names (remove #(= "wagoe" (namespace %))) (map str) sort vec)}))

(defn- has-ports? [lib-dir]
  (let [src (io/file lib-dir "src")]
    (boolean (and (.isDirectory src)
                  (some #(= "ports.clj" (.getName ^java.io.File %)) (file-seq src))))))

(defn- monorepo-module-graph
  "The graph of this repository: one node per directory under libs/."
  [root]
  (let [libs (io/file root "libs")]
    (when (.isDirectory libs)
      (let [mods (for [d     (.listFiles libs)
                       :when (.isDirectory ^java.io.File d)
                       :let  [deps-file (io/file d "deps.edn")]
                       :when (.exists deps-file)
                       :let  [{:keys [wagoe external]} (lib-deps (str deps-file))]]
                   {:name         (.getName ^java.io.File d)
                    :deps         wagoe
                    :external-libs external
                    :has-ports?   (has-ports? d)})]
        {:source  :monorepo
         :modules (vec (sort-by :name mods))
         :edges   (vec (for [m mods, dep (:deps m)] [(:name m) dep]))}))))

(defn- active-config-keys
  "`:wagoe/*` keys in the :active section of the dev config.

   Read as text, not EDN: config.edn carries Aero reader tags (#env, #profile,
   #or) that no plain reader knows, so reading it would throw on the first one.
   A key name is all this needs."
  [root]
  (let [f (io/file root "resources/conf/dev/config.edn")]
    (when (.exists f)
      (let [;; Comments stripped first. The shipped dev config lists the
            ;; alternatives it did not choose — `:wagoe/postgresql` sits in a
            ;; comment above the sqlite block — and reporting those as active
            ;; modules is worse than reporting none.
            code (->> (str/split-lines (slurp f))
                      (map #(str/replace % #";.*$" ""))
                      (str/join "\n"))]
        (->> (re-seq #":wagoe[./a-z-]*/[a-z][a-z0-9-]*" code)
             distinct
             sort
             vec)))))

(defn- project-module-graph
  "What a project created by `wagoe new` has: the wagoe libraries it depends on
   and the modules its config switches on.

   The monorepo answer — walk `libs/` — is a directory no project has, so this
   resource answered :unavailable everywhere the server was actually installed
   (BOU-320)."
  [root]
  (let [deps    (:deps (read-edn-file root "deps.edn"))
        aliases (:aliases (read-edn-file root "deps.edn"))
        wagoe-of (fn [m] (->> (keys m)
                              (filter #(= "com.wagoe" (namespace %)))
                              (map name)
                              sort
                              vec))
        libs     (wagoe-of deps)
        dev-libs (->> (vals aliases)
                      (mapcat #(wagoe-of (:extra-deps %)))
                      distinct
                      sort
                      vec)]
    (when (seq (concat libs dev-libs))
      {:source        :project
       :libraries     libs
       :dev-libraries (vec (remove (set libs) dev-libs))
       :config-keys   (active-config-keys root)})))

(defn- module-graph [root]
  (or (monorepo-module-graph root) (project-module-graph root)))

(defn- kondo-rules [root]
  (when-let [config (read-edn-file root ".clj-kondo/config.edn")]
    {:config config}))

(defn build-snapshot
  "Reflect the project rooted at `root` (the working directory by default).

   Each view is a `delay`, so reading one resource never pays to build the
   others. A view that resolves to nil surfaces as :unavailable — and is not
   advertised at all, see wagoe.mcp.core.resources/available-catalog.

   The root is a parameter because relative paths in the JVM resolve against
   the process working directory, which a test cannot change: reading the
   project from an explicit root is what makes this reflection testable at all.
   Live-system views (schema-registry, routes, workflows, libs) are not
   reflected — see ADR-033."
  ([] (build-snapshot (System/getProperty "user.dir")))
  ([root]
   {:conventions  (delay (conventions root))
    :module-graph (delay (module-graph root))
    :kondo-rules  (delay (kondo-rules root))}))

;; --- adapters ---------------------------------------------------------------

(defrecord InProcessSystemSource [root]
  ports/SystemSource
  (snapshot [_] (build-snapshot root)))

(defn in-process-system-source
  "Reflects the project at `root`, the working directory by default. Re-reads on
   each call so resources always reflect current state."
  ([] (in-process-system-source (System/getProperty "user.dir")))
  ([root] (->InProcessSystemSource root)))

(defrecord StaticSystemSource [snap]
  ports/SystemSource
  (snapshot [_] snap))

(defn static-system-source
  "A fixed snapshot, for tests and for serving a precomputed view."
  [snap]
  (->StaticSystemSource snap))
