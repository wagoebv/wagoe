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
    (let [deps (edn/read-string (slurp deps-file))
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

(defn- ns->boundary-lib
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
  (when (str/starts-with? lib-name "boundary-")
    (subs lib-name (count "boundary-"))))

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
                                   (keep ns->boundary-lib)
                                   (remove #(or (= % lib-name) (= % ns-prefix)))
                                   (set))]
                 [lib-name dep-libs]))
             lib-entries)))

;; ---------------------------------------------------------------------------
;; Cycle detection (DFS)
;; ---------------------------------------------------------------------------

(defn- find-all-cycles
  "DFS cycle detection on a graph. Returns all distinct cycles found as a
   seq of vectors (each vector is a cycle path), or empty seq if acyclic.
   Cycles are deduplicated by their set of directed edges so that two cycles
   over the same nodes but with different edge patterns are both reported."
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

(defn- check-core-independence
  "Verify that the 'core' library has zero wagoe library dependencies."
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
        ;; Hard failures: core violations, any declared cycle, any non-allowlisted actual cycle,
        ;; any NEW undeclared direct dependency
        hard-failures  (concat core-issues
                               (map (fn [p] {:type :declared-cycle :path p}) declared-cycles)
                               (map (fn [p] {:type :actual-cycle :path p}) new-actual)
                               new-undeclared)
        ;; Soft warnings: allowlisted undeclared deps (pre-existing, acknowledged)
        warnings       allowed-undeclared]
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
            (println (str "  VIOLATION: " (:lib v) " requires " (ansi/red (:dep v)) " but it is not declared in deps.edn"))))
        (println)
        (println (str (count hard-failures) " violation(s) found."))
        (System/exit 1))
      (do
        (println (ansi/green "Dependency check passed.")
                 (str (count entries) " libraries scanned, 0 hard violations."))
        (System/exit 0)))))
