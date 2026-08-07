#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/check_deps.clj
;;
;; Dependency direction linting: verifies that runtime (:require ...) matches
;; declared deps.edn dependencies and that no circular dependency exists
;; between Wagoe libraries.

(ns wagoe.tools.check-deps
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [wagoe.tools.ansi :as ansi]
            [wagoe.tools.parsing :as parsing]))

;; ---------------------------------------------------------------------------
;; Parsing helpers
;; ---------------------------------------------------------------------------

(defn- lib-dirs
  "Return a seq of [lib-name, lib-dir] pairs for all libraries under libs/."
  []
  (let [root (io/file (System/getProperty "user.dir"))
        libs (io/file root "libs")]
    (when (.exists libs)
      (->> (.listFiles libs)
           (filter #(.isDirectory %))
           (map (fn [d] [(.getName d) d]))
           (sort-by first)))))

(defn- parse-deps-edn
  "Parse a deps.edn and extract declared Wagoe library dependencies.
   Returns a set of library name strings (empty set if file is missing)."
  [deps-file]
  (if (.exists deps-file)
    (let [deps (try (edn/read-string (slurp deps-file))
                    ;; Unreadable is reported by `unreadable-deps-files` as its
                    ;; own violation. Throwing here surfaced as a bare
                    ;; "EOF while reading" with no file named, because babashka
                    ;; prints the root cause.
                    (catch Exception _ nil))
          all-deps (merge (:deps deps) {})]
      (->> (vals all-deps)
           (filter map?)
           (keep :local/root)
           (map (fn [root-path]
                  ;; Extract lib name from paths like "../core" or "../../libs/core"
                  (last (str/split root-path #"/"))))
           (set)))
    #{}))

(defn- source-files
  "Find all .clj files under a library's src/ directory."
  [lib-dir]
  (let [src-dir (io/file lib-dir "src")]
    (when (.exists src-dir)
      (->> (file-seq src-dir)
           (filter #(and (.isFile %) (str/ends-with? (.getName %) ".clj")))))))

(defn- extract-required-ns
  "Extract required namespace symbols from a ns form."
  [ns-form]
  (when ns-form
    (let [require-clause (->> ns-form
                              (filter #(and (sequential? %) (= :require (first %))))
                              first)]
      (when require-clause
        (->> (rest require-clause)
             (map #(cond (symbol? %) (str %) (vector? %) (str (first %)) :else nil))
             (remove nil?))))))

(defn- ns->wagoe-lib
  "Given a namespace string like 'wagoe.user.core.foo', extract the library
   name 'user'. Returns nil for non-boundary namespaces.
   Maps wagoe.shared.* to 'shared-ui' (those namespaces live in libs/shared-ui/src/)."
  [ns-str]
  (let [parts (str/split ns-str #"\.")]
    (when (and (>= (count parts) 2) (= "wagoe" (first parts)))
      (case (second parts)
        ;; namespace segment differs from the lib dir name
        "shared" "shared-ui"      ; wagoe.shared.* lives in libs/shared-ui/
        "cli"    "wagoe-cli"      ; wagoe.cli.*  lives in libs/wagoe-cli/
        "mcp"    "wagoe-mcp"      ; wagoe.mcp.*  lives in libs/wagoe-mcp/
        (second parts)))))

;; ---------------------------------------------------------------------------
;; Graph building
;; ---------------------------------------------------------------------------

(defn- build-declared-graph
  "Build adjacency map from deps.edn: {lib-name -> #{dep-lib-names}}."
  [lib-entries]
  (into {}
        (map (fn [[lib-name lib-dir]]
               [lib-name (parse-deps-edn (io/file lib-dir "deps.edn"))])
             lib-entries)))

(defn- dir-name->ns-prefix
  "For hyphenated lib dirs like 'wagoe-cli', return the namespace segment
   'cli' that the library's own sources actually use under wagoe.<segment>.*.
   Returns nil for libs whose dir name equals their namespace segment."
  [lib-name]
  (when (str/starts-with? lib-name "wagoe-")
    (subs lib-name (count "wagoe-"))))

(defn- build-actual-graph
  "Build adjacency map from source :requires: {lib-name -> #{dep-lib-names}}."
  [lib-entries]
  (into {}
        (map (fn [[lib-name lib-dir]]
               (let [ns-prefix (dir-name->ns-prefix lib-name)
                     files (source-files lib-dir)
                     dep-libs (->> files
                                   (mapcat (fn [f]
                                             (let [ns-form (parsing/read-ns-form f)]
                                               (extract-required-ns ns-form))))
                                   (keep ns->wagoe-lib)
                                   (remove #(or (= % lib-name) (= % ns-prefix)))
                                   (set))]
                 [lib-name dep-libs]))
             lib-entries)))

;; ---------------------------------------------------------------------------
;; Cycle detection (DFS)
;; ---------------------------------------------------------------------------

(defn find-all-cycles
  "DFS cycle detection on a graph. Returns all distinct cycles found as a
   seq of vectors (each vector is a cycle path), or empty seq if acyclic.
   Cycles are deduplicated by their set of directed edges so that two cycles
   over the same nodes but with different edge patterns are both reported.

   Public so a test can feed it a graph that must trip it. `-main` exits the
   process, so it cannot be used to prove this gate still detects anything."
  [graph]
  (let [visited (atom #{})
        path    (atom [])
        on-path (atom #{})
        cycles  (atom [])
        seen-edge-sets (atom #{})]
    (letfn [(dfs [node]
              (when-not (@visited node)
                (swap! path conj node)
                (swap! on-path conj node)
                (doseq [neighbor (get graph node #{})]
                  (if (@on-path neighbor)
                    (let [cycle-path (conj (vec (drop-while #(not= % neighbor) @path)) neighbor)
                          edge-set (set (map vector (butlast cycle-path) (rest cycle-path)))]
                      (when-not (contains? @seen-edge-sets edge-set)
                        (swap! seen-edge-sets conj edge-set)
                        (swap! cycles conj cycle-path)))
                    (when-not (@visited neighbor)
                      (dfs neighbor))))
                (swap! path pop)
                (swap! on-path disj node)
                (swap! visited conj node)))]
      (doseq [node (keys graph)]
        (dfs node))
      @cycles)))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn check-core-independence
  "Verify that the 'core' library has zero wagoe library dependencies.

   Public for the same reason as `find-all-cycles`."
  [declared-graph actual-graph]
  (let [declared-deps (get declared-graph "core" #{})
        actual-deps   (get actual-graph "core" #{})]
    (concat
     (map (fn [d] {:type :core-declared-dep :dep d}) declared-deps)
     (map (fn [d] {:type :core-actual-dep :dep d}) actual-deps))))

(defn- check-undeclared-deps
  "Verify every actual source-level require is declared as a direct dependency
   in the library's own deps.edn. Transitive availability through another
   library is not sufficient — each library must explicitly declare what it uses."
  [declared-graph actual-graph]
  (->> actual-graph
       (mapcat (fn [[lib actual-deps]]
                 (let [direct-declared (get declared-graph lib #{})]
                   (->> actual-deps
                        (remove #(contains? direct-declared %))
                        (map (fn [dep]
                               {:type :undeclared-dep
                                :lib  lib
                                :dep  dep}))))))))

;; ---------------------------------------------------------------------------
;; Third-party dependency declaration
;; ---------------------------------------------------------------------------
;;
;; `check:poms` verifies that published POMs carry their inter-Wagoe deps
;; (BOU-202), and the graph above covers wagoe -> wagoe. Neither looks at
;; third-party requires, so a library could use tools.cli, cheshire or anything
;; else without declaring it and both gates stayed green. That is how
;; wagoe-ai shipped a POM without tools.cli and every `bb ai` subcommand died in
;; a generated project (BOU-272), and how platform and user came to resolve
;; tools.cli only through migratus, at 1.0.219 rather than the pinned 1.4.256
;; (BOU-273).

(def ^:private ns-prefix->artifact
  "Namespace prefix -> the artifact that provides it.

   Several of these families are split across artifacts, and one entry per
   family gets the gate wrong: `buddy.sign.jwt` comes from buddy/buddy-sign,
   not buddy/buddy-core, so mapping all of `buddy` to buddy-core meant removing
   buddy-sign from a deps.edn went unreported — the coarser gap was already
   allowlisted and absorbed it. Same for `integrant.repl` (integrant/repl, a
   separate artifact from integrant/integrant) and for reitit, which this
   repository consumes as four modules rather than the bundle.

   Longest prefix wins, so `buddy.core` is checked before `buddy`. Entries are
   added only for namespaces a Wagoe library actually requires: a prefix absent
   here is not checked, which makes this map the gate's coverage."
  {"aero"                   'aero/aero
   "buddy.core"             'buddy/buddy-core
   "buddy.hashers"          'buddy/buddy-hashers
   "buddy.sign"             'buddy/buddy-sign
   "cheshire"               'cheshire/cheshire
   "clj-http"               'clj-http/clj-http
   "clj-kondo"              'clj-kondo/clj-kondo
   "hiccup"                 'hiccup/hiccup
   "honey.sql"              'com.github.seancorfield/honeysql
   "integrant.core"         'integrant/integrant
   "integrant.repl"         'integrant/repl
   "malli"                  'metosin/malli
   "migratus"               'migratus/migratus
   "muuntaja"               'metosin/muuntaja
   "next.jdbc"              'com.github.seancorfield/next.jdbc
   "reitit.coercion.malli"  'metosin/reitit-malli
   "reitit.core"            'metosin/reitit-core
   "reitit.ring.middleware" 'metosin/reitit-middleware
   "reitit.ring"            'metosin/reitit-ring
   "reitit.swagger"         'metosin/reitit-swagger
   "rewrite-clj"            'rewrite-clj/rewrite-clj
   "clojure.tools.cli"      'org.clojure/tools.cli
   "clojure.tools.logging"  'org.clojure/tools.logging})

(defn provider-of
  "The artifact providing `ns-str`, or nil when no prefix matches.

   Longest match wins. `some` over the map picked an arbitrary one where
   prefixes overlap — hash-map order is not insertion order past eight entries
   — so `reitit.ring.middleware.muuntaja` could have resolved to reitit-ring or
   reitit-middleware depending on nothing the caller controls.

   Public so a test can drive the overlapping cases directly."
  [ns-str]
  (->> ns-prefix->artifact
       (filter (fn [[prefix _]]
                 (or (= ns-str prefix)
                     (str/starts-with? ns-str (str prefix ".")))))
       (sort-by (comp - count first))
       first
       second))

(defn declared-artifacts
  "Artifact coordinates declared in a library's deps.edn, as strings, or
   `:unreadable` when the file exists but does not parse.

   `:unreadable` rather than an empty set or a thrown exception. An empty set
   would make every namespace in that library look undeclared — a wall of
   violations pointing at the wrong thing. A throw was worse in practice:
   babashka prints the root cause, so `EOF while reading` reached the terminal
   with no indication of which file. It is reported as its own violation
   instead.

   Public so a test can drive it; `-main` exits the process."
  [lib-dir]
  (let [deps-file (io/file lib-dir "deps.edn")]
    (if (.exists deps-file)
      (try
        (set (map str (keys (:deps (edn/read-string (slurp deps-file))))))
        (catch Exception _ :unreadable))
      #{})))

(defn third-party-gaps
  "[{:lib :ns :artifact}] for third-party namespaces a library requires
   without declaring the artifact that provides them.

   Only *direct* declaration counts. Resolving through a transitive works until
   the dependency in between drops it, and it picks up whatever version that
   dependency pins — which is how platform got tools.cli 1.0.219 while the
   monorepo pinned 1.4.256.

   Public so a test can drive it; `-main` exits the process."
  [lib-entries]
  (for [[lib-name lib-dir] lib-entries
        ;; Read eagerly, and let a malformed deps.edn throw. This used to be
        ;; inside the lazy `for`, so an unreadable file surfaced nowhere: the
        ;; exception was never realised and the gate printed "0 violations" and
        ;; exited 0. A checker that passes when it cannot read its input is
        ;; worse than no checker.
        :let [declared (declared-artifacts lib-dir)]
        :when (set? declared)
        f    (source-files lib-dir)
        ns-str (extract-required-ns (parsing/read-ns-form f))
        :let [artifact (provider-of ns-str)]
        :when (and artifact (not (contains? declared (str artifact))))]
    {:lib lib-name :ns ns-str :artifact artifact}))

(defn unreadable-deps-files
  "Libraries whose deps.edn exists but does not parse.

   Checked separately because a checker that cannot read its input must say so
   rather than draw a conclusion from it — the first version of this gate
   swallowed the read error inside a lazy sequence and printed
   \"0 violations\"."
  [lib-entries]
  (for [[lib-name lib-dir] lib-entries
        :when (= :unreadable (declared-artifacts lib-dir))]
    {:lib lib-name :file (.getPath (io/file lib-dir "deps.edn"))}))

(def ^:private allowed-third-party-gaps
  "Pre-existing [lib artifact] pairs. Every one of these resolves today through
   a Wagoe sibling or another dependency, so nothing is broken — but each is a
   version the library does not control and a transitive it does not own.

   Remove entries as deps.edn files are updated. The point of the allowlist is
   that a *new* gap fails: the three fixed under BOU-273 are deliberately absent."
  #{["admin" "integrant/integrant"]
    ["admin" "metosin/malli"]
    ["admin" "org.clojure/tools.logging"]
    ["audience" "integrant/integrant"]
    ["cache" "integrant/integrant"]
    ["devtools" "cheshire/cheshire"]
    ["devtools" "integrant/integrant"]
    ["devtools" "integrant/repl"]
    ["devtools" "metosin/malli"]
    ["devtools" "metosin/muuntaja"]
    ["devtools" "metosin/reitit-core"]
    ["devtools" "metosin/reitit-ring"]
    ["devtools" "org.clojure/tools.logging"]
    ["external" "integrant/integrant"]
    ["geo" "integrant/integrant"]
    ["i18n" "integrant/integrant"]
    ["i18n" "org.clojure/tools.logging"]
    ["observability" "cheshire/cheshire"]
    ["observability" "metosin/malli"]
    ["platform" "metosin/malli"]
    ["platform" "org.clojure/tools.logging"]
    ["realtime" "integrant/integrant"]
    ["scaffolder" "cheshire/cheshire"]
    ["scaffolder" "metosin/malli"]
    ["scaffolder" "org.clojure/tools.logging"]
    ["search" "cheshire/cheshire"]
    ["search" "com.github.seancorfield/honeysql"]
    ["search" "com.github.seancorfield/next.jdbc"]
    ["search" "integrant/integrant"]
    ["search" "org.clojure/tools.logging"]
    ["shared-ui" "metosin/malli"]
    ["storage" "integrant/integrant"]
    ["storage" "metosin/malli"]
    ["storage" "org.clojure/tools.logging"]
    ["tenant" "integrant/integrant"]
    ["tenant" "metosin/malli"]
    ["tenant" "migratus/migratus"]
    ["tenant" "org.clojure/tools.logging"]
    ["tools" "cheshire/cheshire"]
    ["user" "buddy/buddy-core"]
    ["user" "cheshire/cheshire"]
    ["user" "com.github.seancorfield/next.jdbc"]
    ["user" "integrant/integrant"]
    ["user" "metosin/malli"]
    ["user" "org.clojure/tools.logging"]
    ["wagoe-mcp" "metosin/malli"]
    ["workflow" "com.github.seancorfield/honeysql"]
    ["workflow" "com.github.seancorfield/next.jdbc"]
    ["workflow" "integrant/integrant"]
    ["workflow" "metosin/malli"]
    ["workflow" "org.clojure/tools.logging"]})

(defn- allowed-third-party-gap?
  [{:keys [lib artifact]}]
  (contains? allowed-third-party-gaps [lib (str artifact)]))

;; ---------------------------------------------------------------------------
;; Known cycle allowlist
;; ---------------------------------------------------------------------------

(def ^:private allowed-undeclared-deps
  "Pre-existing [lib dep] pairs where a library :requires another Wagoe
   library without declaring it in deps.edn. These are acknowledged but not
   yet resolved. Remove entries as deps.edn files are updated; adding new
   entries requires an ADR."
  ;; Only platform->external remains undeclared: external declares platform, so
  ;; declaring platform->external would create a circular :local/root that
  ;; tools.deps rejects. Needs the external->platform coupling broken first.
  #{["platform" "external"]})

(defn- allowed-undeclared?
  "Returns true if this undeclared dep is in the known allowlist."
  [{:keys [lib dep]}]
  (contains? allowed-undeclared-deps [lib dep]))

(def ^:private allowed-cycle-edges
  "Pre-existing directed source-level require edges that are acknowledged
   but not yet resolved. A cycle is allowlisted when every directed edge
   in its path is covered by this set.
   Remove entries as cross-references are broken; adding new entries requires an ADR."
  ;; No source-level cycles remain (BOU-171/192/193/194/198 dissolved them all).
  #{})

(defn- allowed-cycle?
  "Returns true if every directed edge in the cycle path is covered by the
   allowlist. A cycle involving any new edge will fail."
  [cycle-path]
  (let [edges (map vector (butlast cycle-path) (rest cycle-path))]
    (every? #(contains? allowed-cycle-edges %) edges)))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn -main [& _args]
  (let [entries        (lib-dirs)
        declared-graph (build-declared-graph entries)
        actual-graph   (build-actual-graph entries)
        core-issues        (check-core-independence declared-graph actual-graph)
        undeclared         (check-undeclared-deps declared-graph actual-graph)
        declared-cycles    (find-all-cycles declared-graph)
        actual-cycles      (find-all-cycles actual-graph)
        allowed-actual     (filter allowed-cycle? actual-cycles)
        new-actual         (remove allowed-cycle? actual-cycles)
        allowed-undeclared  (filter allowed-undeclared? undeclared)
        new-undeclared      (remove allowed-undeclared? undeclared)
        tp-gaps             (distinct (map #(select-keys % [:lib :artifact])
                                           (third-party-gaps entries)))
        allowed-tp          (filter allowed-third-party-gap? tp-gaps)
        new-tp              (remove allowed-third-party-gap? tp-gaps)
        unreadable          (unreadable-deps-files entries)
        ;; Hard failures: core violations, any declared cycle, any non-allowlisted actual cycle,
        ;; any NEW undeclared direct dependency
        hard-failures  (concat core-issues
                               (map (fn [p] {:type :declared-cycle :path p}) declared-cycles)
                               (map (fn [p] {:type :actual-cycle :path p}) new-actual)
                               new-undeclared
                               (map #(assoc % :type :undeclared-third-party) new-tp)
                               (map #(assoc % :type :unreadable-deps) unreadable))
        ;; Soft warnings: allowlisted undeclared deps (pre-existing, acknowledged)
        warnings       allowed-undeclared]
    (when (seq allowed-tp)
      (println (ansi/yellow "Third-party dependencies used but not declared (allowlisted):"))
      (doseq [v (sort-by (juxt :lib (comp str :artifact)) allowed-tp)]
        (println (str "  " (:lib v) " uses " (:artifact v) " (resolved transitively)")))
      (println (str (count allowed-tp) " allowlisted. Each is a version the library does not control."))
      (println))
    ;; Print allowlisted actual cycles as informational
    (when (seq allowed-actual)
      (println (ansi/yellow "Known source-level cycle(s) (allowlisted):"))
      (doseq [c allowed-actual]
        (println (str "  " (str/join " -> " c))))
      (println))
    (when (seq warnings)
      (println (ansi/yellow "Known undeclared dependencies (allowlisted):"))
      (doseq [v warnings]
        (println (str "  " (:lib v) " requires " (:dep v) " (not in deps.edn)")))
      (println (str (count warnings) " allowlisted. Remove entries as deps.edn files are updated."))
      (println))
    ;; Hard failures block CI
    (if (seq hard-failures)
      (do
        (println (ansi/red "Dependency direction violations found:"))
        (println)
        (doseq [v hard-failures]
          (case (:type v)
            :core-declared-dep
            (println (str "  VIOLATION: core/deps.edn declares dependency on " (ansi/red (:dep v))))
            :core-actual-dep
            (println (str "  VIOLATION: core source requires " (ansi/red (:dep v)) " (core must be dependency-free)"))
            :declared-cycle
            (println (str "  VIOLATION: circular dependency in deps.edn: " (ansi/red (str/join " -> " (:path v)))))
            :actual-cycle
            (println (str "  VIOLATION: circular dependency in source requires: " (ansi/red (str/join " -> " (:path v)))))
            :undeclared-dep
            (println (str "  VIOLATION: " (:lib v) " requires " (ansi/red (:dep v)) " but it is not declared in deps.edn"))
            :undeclared-third-party
            (println (str "  VIOLATION: " (:lib v) " uses " (ansi/red (str (:artifact v)))
                          " but does not declare it — the published POM would omit it"))
            :unreadable-deps
            (println (str "  VIOLATION: cannot parse " (ansi/red (:file v))
                          " — dependency checks for " (:lib v) " cannot run"))))
        (println)
        (println (str (count hard-failures) " violation(s) found."))
        (System/exit 1))
      (do
        (println (ansi/green "Dependency check passed.")
                 (str (count entries) " libraries scanned, 0 hard violations."))
        (System/exit 0)))))
