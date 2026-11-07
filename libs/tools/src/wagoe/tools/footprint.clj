(ns wagoe.tools.footprint
  "What each published library adds to an application's classpath, at a release
   tag, as EDN for the library pages on wagoe.org (BOU-878).

   Usage: bb deps:footprint [--ref <tag>] [--out <file>]

   The tree is `clojure -Stree` per library, run on `git archive <ref>`, so the
   numbers describe that release and not the working copy."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [edamame.core :as e]
            [wagoe.tools.check-ports-diff :as ports-diff]
            [wagoe.tools.deploy :as deploy]
            [wagoe.tools.text :as text]))

(defn- jetty? [dep] (str/starts-with? (namespace dep) "org.eclipse.jetty"))

(def http-stack
  "Display name -> predicate on a dependency symbol."
  [["Jetty"    jetty?]
   ["Reitit"   #(str/starts-with? (name %) "reitit")]
   ["Ring"     #(contains? #{"ring" "org.ring-clojure"} (namespace %))]
   ["Muuntaja" #(= "muuntaja" (name %))]])

(defn parse-tree
  "`clojure -Stree` output -> the included entries, as {:lib sym :coord str}.
   Lines marked `X` are omitted from the classpath, so they are dropped."
  [text]
  (for [line  (str/split-lines text)
        :let  [[_ mark lib coord] (re-matches #"\s*(?:([.X]) )?(\S+/\S+)\s+(\S+).*" line)]
        :when (and lib (not= "X" mark))]
    {:lib (symbol lib) :coord coord}))

(defn- wagoe-dir
  "The libs/ directory of a wagoe :local/root entry, else nil."
  [{:keys [lib coord]}]
  (when (and (= "wagoe" (namespace lib)) (fs/absolute? coord))
    (fs/file-name coord)))

(defn collapse-stems
  "Deps sharing a name stem (before the first `-`) and a group prefix or last
   group segment shown once, as `stem-*`, so nineteen Jetty artifacts read as
   `jetty-*`, `fi.metosin/reitit-openapi` folds in with `metosin/reitit-core`,
   and `commons-net` and `commons-lang3` stay apart."
  [deps]
  (let [stem     #(first (str/split (name %) #"-"))
        segments #(str/split (namespace %) #"\.")
        families (fn [dep] [[(stem dep) (take 2 (segments dep))]
                            [(stem dep) (last (segments dep))]])
        shared   (frequencies (mapcat families deps))]
    (distinct (for [dep deps]
                (if (some #(< 1 (shared %)) (families dep))
                  (str (stem dep) "-*")
                  (name dep))))))

(defn footprint
  "One library's entry, from its tree and fns lib -> artifact id, lib -> declared deps."
  [lib entries artifact-fn declared-fn]
  (let [{wagoe-entries true ext-entries false} (group-by (comp some? wagoe-dir) entries)
        wagoe    (->> wagoe-entries (map wagoe-dir) distinct sort)
        external (set (map :lib ext-entries))
        declared (->> (cons lib wagoe) (mapcat declared-fn) (filter external) distinct
                      (sort-by (comp text/lower-case name)) collapse-stems)]
    {:artifact       (artifact-fn lib)
     :wagoe          (mapv artifact-fn wagoe)
     :declared       (vec declared)
     :external-count (count external)
     :not-included   (vec (concat (when-not (some #{"platform"} (cons lib wagoe))
                                    [(artifact-fn "platform")])
                                  ;; The rest of the HTTP stack is only news
                                  ;; when the server itself is missing.
                                  (when (not-any? jetty? external)
                                    (for [[label pred] http-stack
                                          :when (not-any? pred external)]
                                      label))))}))

(defn all-libs
  "The `all-libs` vector of wagoe.tools.deploy, read from its source text, so a
   tag's own published set is used rather than this checkout's."
  [deploy-src]
  (some (fn [form]
          (when (and (seq? form) (= '[def all-libs] (take 2 form)))
            (last form)))
        (e/parse-string-all deploy-src {:all true
                                        :auto-resolve (constantly 'x)
                                        :readers (constantly identity)})))

(defn- sh! [opts & args]
  (let [{:keys [exit out err]} (apply p/sh opts args)]
    (when-not (zero? exit)
      (throw (ex-info (str (str/join " " args) " failed: " err)
                      {:type :internal-error :exit exit})))
    out))

(defn- declared-deps
  "The non-local deps a lib's deps.edn names in :deps."
  [root lib]
  (let [f (io/file root "libs" lib "deps.edn")]
    (->> (:deps (try (edn/read-string (slurp f))
                     (catch Exception ex
                       (throw (ex-info (str "cannot read " f ": " (ex-message ex))
                                       {:type :internal-error} ex)))))
         (remove (fn [[_ coord]] (contains? coord :local/root)))
         (map key))))

(defn- lib-entry [root lib artifact-fn declared-fn]
  (binding [*out* *err*] (println "  tree:" lib))
  (footprint lib
             (parse-tree (sh! {:dir (str (io/file root "libs" lib))} "clojure" "-Stree"))
             artifact-fn
             declared-fn))

(defn generate
  "The footprint of every published library at `ref`."
  [ref]
  (let [commit (str/trim (sh! {} "git" "rev-parse" "--verify" (str ref "^{commit}")))
        root   (str (fs/create-temp-dir {:prefix "wagoe-footprint"}))
        tar    (str (io/file root "libs.tar"))]
    (try
      ;; Two steps, not a pipe: a pipe reports tar's exit, so a git failure
      ;; would surface later as a missing file.
      (sh! {} "git" "archive" "-o" tar commit "libs")
      (sh! {} "tar" "-xf" tar "-C" root)
      (let [deploy-src  (io/file root "libs/tools/src/wagoe/tools/deploy.clj")
            libs        (when (.exists deploy-src) (all-libs (slurp deploy-src)))
            artifact-fn (memoize #(deploy/artifact-name root %))
            declared-fn (memoize #(declared-deps root %))]
        (when (empty? libs)
          (throw (ex-info "could not read all-libs at ref" {:type :internal-error :ref ref})))
        {:ref    ref
         :commit commit
         :libs   (into (sorted-map)
                       (for [lib libs]
                         [lib (lib-entry root lib artifact-fn declared-fn)]))})
      (finally (fs/delete-tree root)))))

(def ^:private usage "Usage: bb deps:footprint [--ref <tag>] [--out <file>]")

(defn -main [& args]
  (let [opts (when (even? (count args)) (apply hash-map args))
        _    (when-not (and opts (every? #{"--ref" "--out"} (keys opts)))
               (binding [*out* *err*] (println usage))
               (System/exit 1))
        ref  (or (get opts "--ref") (ports-diff/latest-final-tag))
        _    (when-not ref
               (throw (ex-info "no final release tag reachable from HEAD; pass --ref"
                               {:type :validation-error})))
        out  (or (get opts "--out") "footprint.edn")
        edn  (generate ref)]
    (spit out (str ";; Generated by `bb deps:footprint` in the wagoe repo — do not edit.\n"
                   (with-out-str (pprint/pprint edn))))
    (println (str "Wrote " out " (" (count (:libs edn)) " libraries at " ref ")"))))
