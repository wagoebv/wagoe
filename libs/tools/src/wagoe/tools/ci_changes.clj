(ns wagoe.tools.ci-changes
  "Which CI jobs a pull request needs. `bb ci:changes <base-ref>` writes to
   $GITHUB_OUTPUT: `code`, false when only docs no code test reads changed
   (BOU-615); `libs`, the libraries whose tests run; `skip-jobs`, the jobs
   gated on `libs` that therefore do not."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clj-yaml.core :as yaml]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [wagoe.tools.check-deps :as check-deps]
            [wagoe.tools.parsing :as parsing]))

(def docs-only-paths
  "Files no test outside libs/tools reads; tools tests always run. Not
   docs/modules/** (the app suite lists its pages), AGENTS.md or a library's
   README (workflow pins its own), which code tests do read."
  [#"^dev-docs/.*\.(md|adoc)$"
   #"^(CHANGELOG|README|UPGRADING|CONTRIBUTING|BUILD|CLAUDE)\.md$"])

(defn- docs-only-path? [p]
  (some #(re-find % p) docs-only-paths))

(defn docs-only?
  "Whether every path in `paths` is one of `docs-only-paths`. An empty diff is
   not docs-only: run everything rather than guess."
  [paths]
  (boolean (and (seq paths) (every? docs-only-path? paths))))

(def false-edges
  "Uses that do not load the other library in a test run. Each one cut here
   keeps most libraries from depending on all of them."
  {["core" "user"]          "snapshot.clj's docstring uses a user test namespace as its example"
   ["ai" "user"]            "docstrings use a user source file as the example path"
   ["platform" "devtools"]  "request capture: dev profile only, and skipped when devtools fails to load"})

(defn- lib-names [root]
  (->> (fs/list-dir (fs/path root "libs")) (filter fs/directory?) (map (comp str fs/file-name)) sort))

(defn- files-under [root lib dirs]
  (for [d dirs
        :let [p (fs/path root "libs" lib d)] :when (fs/exists? p)
        f (fs/glob p "**.{clj,cljc,bb,edn}")]
    (fs/file f)))

(defn- local-roots [x]
  (->> (tree-seq coll? seq x) (filter map?) (keep :local/root) (map #(last (str/split % #"/")))))

(defn- ns-libs [names]
  (keep #(check-deps/ns->wagoe-lib (str "wagoe." %)) names))

(defn- names-path [text lib]
  (or (str/includes? text (str "libs/" lib "/"))
      (str/includes? text (str "\"wagoe/" (str/replace lib "-" "_") "/"))))

(defn- code-uses
  "Libraries source file `f` loads: its requires, a literal `requiring-resolve`,
   and files it reads by path. Not every mention: a docstring naming another
   library would join most of the graph into one."
  [libs f]
  (let [text (slurp f)]
    (-> (->> (tree-seq coll? seq (try (parsing/read-ns-form f) (catch Exception _ nil)))
             (filter symbol?) (keep (comp check-deps/ns->wagoe-lib str)) set)
        (into (ns-libs (map second (re-seq #"requiring-resolve\s+'wagoe\.([a-z0-9-]+)[./]" text))))
        (into (filter #(names-path text %)) libs))))

(defn- test-uses
  "Libraries test file `f` names at all: namespace, keyword or path. Test uses
   are not followed further, so over-reading only runs a few more jobs."
  [libs f]
  (let [text (slurp f)]
    (-> (set (ns-libs (map second (re-seq #"wagoe\.([a-z0-9-]+)[./]" text))))
        (into (filter #(or (names-path text %) (str/includes? text (str ":wagoe/" %)))) libs))))

(defn dependency-graph
  "{lib {:src #{libs its code uses} :test #{libs only its tests use}}}."
  [root]
  (let [libs  (lib-names root)
        known (set libs)
        edges (fn [lib from-deps uses dirs]
                (->> (concat from-deps (mapcat #(uses libs %) (files-under root lib dirs)))
                     (filter known)
                     (remove #(or (= lib %) (false-edges [lib %])))
                     set))]
    (into {}
          (for [lib libs
                :let [f    (fs/file root "libs" lib "deps.edn")
                      deps (when (fs/exists? f) (edn/read-string (slurp f)))]]
            [lib {:src  (edges lib (local-roots (:deps deps)) code-uses ["src" "resources"])
                  :test (edges lib (local-roots (:aliases deps)) test-uses ["test" "test-pg" "dev"])}]))))

(defn loaded-by
  "Libraries a library's test run loads: itself, what its tests use, and the
   :src closure of both. A test-only use is not followed further."
  [graph lib]
  (loop [seen #{} todo (into [lib] (get-in graph [lib :test]))]
    (if-let [[x & more] (seq todo)]
      (if (seen x)
        (recur seen more)
        (recur (conj seen x) (into (vec more) (get-in graph [x :src]))))
      seen)))

(defn libs-to-test
  "Libraries whose tests must run for `paths`. Anything changed outside libs/,
   other than a docs-only file, runs them all, and so does an empty diff."
  [graph paths]
  (let [libs    (set (keys graph))
        changed (->> paths (remove docs-only-path?) (map #(second (re-find #"^libs/([^/]+)/" %))))]
    (if (or (empty? changed) (some #(not (libs %)) changed))
      libs
      (set (filter #(some (loaded-by graph %) changed) libs)))))

(defn gated-jobs
  "{job lib} for each job in `workflow` (ci.yml text) whose `if:` gates it on
   `libs`. A gate naming no library throws, rather than skip the job forever."
  [workflow libs]
  (into {}
        (for [[job {gate :if}] (:jobs (yaml/parse-string workflow))
              :when (and (string? gate) (str/includes? gate "needs.changes.outputs.libs"))
              :let [lib (second (re-find #"' ([a-z0-9-]+) '" gate))]]
          (if (contains? libs lib)
            [(name job) lib]
            (throw (ex-info (str "ci.yml: " (name job) " is gated on no library: " gate)
                            {:type :validation-error}))))))

(defn- changed-files [base-ref]
  (let [{:keys [exit out err]} (process/shell {:out :string :err :string :continue true}
                                              "git" "diff" "--name-only" (str "origin/" base-ref "...HEAD"))]
    (when-not (zero? exit)
      (throw (ex-info (str "git diff against " base-ref " failed: " err) {:type :internal-error})))
    (remove str/blank? (str/split-lines out))))

(defn -main
  "No base ref (a push to main) always means everything."
  [& [base-ref]]
  (let [graph (dependency-graph ".")
        libs  (set (keys graph))
        paths (when-not (str/blank? base-ref) (changed-files base-ref))
        code? (or (nil? paths) (not (docs-only? paths)))
        run   (cond (nil? paths) libs
                    code?        (libs-to-test graph paths)
                    :else        #{})
        gated (gated-jobs (slurp ".github/workflows/ci.yml") libs)
        lines [(str "code=" code?)
               (str "libs=" (str/join " " (sort run)))
               (str "skip-jobs=" (str/join " " (sort (keep (fn [[job lib]] (when-not (run lib) job)) gated))))]]
    (run! println lines)
    (when-let [out (System/getenv "GITHUB_OUTPUT")]
      (spit out (str (str/join "\n" lines) "\n") :append true))))
