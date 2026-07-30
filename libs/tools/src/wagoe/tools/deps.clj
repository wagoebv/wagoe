#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/deps.clj
;;
;; Check and upgrade Maven dependencies across every deps.edn in the monorepo.
;;
;; Usage (via bb.edn task):
;;   bb upgrade-outdated              -- report all outdated deps
;;   bb upgrade-outdated --update     -- apply upgrades in-place
;;   bb upgrade-outdated --lib tenant -- only check a specific library
;;   bb upgrade-outdated --help       -- show this help

(ns wagoe.tools.deps
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; ANSI helpers
;; =============================================================================

(defn bold   [s] (str "\033[1m"  s "\033[0m"))
(defn green  [s] (str "\033[32m" s "\033[0m"))
(defn red    [s] (str "\033[31m" s "\033[0m"))
(defn yellow [s] (str "\033[33m" s "\033[0m"))
(defn dim    [s] (str "\033[2m"  s "\033[0m"))
(defn cyan   [s] (str "\033[36m" s "\033[0m"))

;; =============================================================================
;; File discovery
;; =============================================================================

(def ^:private root-dir (io/file (System/getProperty "user.dir")))

(defn- relative-label
  "Human-readable label for a deps.edn file, e.g. '(root)' or 'libs/tenant'."
  [^java.io.File deps-file]
  (let [parent (.getParentFile deps-file)
        rel    (str (.relativize (.toPath root-dir) (.toPath parent)))]
    (if (= rel "") "(root)" rel)))

(defn- find-deps-files
  "Returns all relevant deps.edn files: root + every lib (sorted by label)."
  ([] (find-deps-files nil))
  ([only-lib]
   (let [root-file (io/file root-dir "deps.edn")
         libs-dir  (io/file root-dir "libs")
         lib-files (when (.exists libs-dir)
                     (->> (.listFiles libs-dir)
                          (filter #(.isDirectory %))
                          (sort-by #(.getName %))
                          (map #(io/file % "deps.edn"))
                          (filter #(.exists %))
                          ;; skip scaffolder template dir
                          (remove #(str/includes? (.getPath %) "existing-dir"))))]
     (if only-lib
       (->> lib-files
            (filter #(= (.getName (.getParentFile %)) only-lib))
            (into [root-file]))
       (cons root-file lib-files)))))

;; =============================================================================
;; Coord extraction
;; =============================================================================

(defn- mvn-entries
  "Returns [[coord version] ...] for all :mvn/version entries in a deps map."
  [deps-map]
  (->> deps-map
       (filter (fn [[_ v]] (and (map? v) (string? (:mvn/version v)))))
       (map (fn [[coord v]] [coord (:mvn/version v)]))))

(defn- coords-from-file
  "Returns a map of {coord version} extracted from a deps.edn file.
   Covers :deps and all alias :extra-deps / :replace-deps."
  [^java.io.File f]
  (let [parsed (edn/read-string (slurp f))
        direct (mvn-entries (:deps parsed))
        aliased (for [[_ cfg] (:aliases parsed)
                      entry   (concat (mvn-entries (:extra-deps cfg))
                                      (mvn-entries (:replace-deps cfg)))]
                  entry)]
    (into {} (concat direct aliased))))

;; =============================================================================
;; Latest-version lookup  (Clojars first, Maven Central fallback)
;; =============================================================================

(defn- coord-parts
  "Splits a coord symbol into [group artifact], e.g.
   cheshire/cheshire  ->  [\"cheshire\"  \"cheshire\"]
   org.clojure/clojure -> [\"org.clojure\" \"clojure\"]"
  [coord]
  (let [s (str coord)
        [g a] (str/split s #"/")]
    [g (or a g)]))

(defn- clojars-latest [group artifact]
  (try
    (let [url  (str "https://clojars.org/api/artifacts/" group "/" artifact)
          resp (http/get url {:throw false})]
      (when (= 200 (:status resp))
        (-> resp :body (json/parse-string true) :latest_release)))
    (catch Exception _ nil)))

(defn- maven-central-latest [group artifact]
  (try
    (let [url  (str "https://search.maven.org/solrsearch/select"
                    "?q=g:" group "+AND+a:" artifact
                    "&rows=1&wt=json")
          resp (http/get url {:throw false})]
      (when (= 200 (:status resp))
        (some-> resp :body (json/parse-string true)
                :response :docs first :latestVersion)))
    (catch Exception _ nil)))

(defn- latest-version [coord]
  (let [[g a] (coord-parts coord)]
    (or (clojars-latest g a)
        (maven-central-latest g a))))

(defn- fetch-all-latest
  "Looks up latest versions for all unique coords in parallel.
   Returns {coord -> latest-version-string-or-nil}."
  [all-coords]
  (let [unique (vec (distinct all-coords))]
    (println (dim (str "  Querying " (count unique) " unique dependencies in parallel...")))
    (into {}
          (pmap (fn [coord] [coord (latest-version coord)]) unique))))

;; =============================================================================
;; Version comparison  (semantic, handles alpha/beta suffixes gracefully)
;; =============================================================================

(defn- numeric-parts [v]
  (when v
    (mapv #(try (Long/parseLong %) (catch Exception _ 0))
          (take 3 (concat (-> v
                              (str/split #"-")
                              first
                              (str/split #"\."))
                          (repeat "0"))))))

(defn- newer?
  "True when latest is strictly newer than current (numeric comparison)."
  [latest current]
  (when (and latest current)
    (pos? (compare (numeric-parts latest) (numeric-parts current)))))

;; =============================================================================
;; Report
;; =============================================================================

(defn- outdated-for
  "Returns sorted seq of [coord current latest] triples that are outdated."
  [coords latest-map]
  (->> coords
       (keep (fn [[coord current]]
               (let [latest (get latest-map coord)]
                 (when (newer? latest current)
                   [coord current latest]))))
       (sort-by (comp str first))))

(defn- print-location
  "Prints the result for one location. Returns count of outdated deps."
  [label coords latest-map]
  (let [outdated (outdated-for coords latest-map)
        up-to-date (- (count coords) (count outdated))]
    (println)
    (print (bold (str "  " label)))
    (if (empty? outdated)
      (println (dim (str "  (" (count coords) " up to date)")))
      (do
        (println)
        (doseq [[coord current latest] outdated]
          (println (str "    " (yellow "↑") " "
                        (cyan (str coord)) "  "
                        current "  →  " (bold latest))))
        (when (pos? up-to-date)
          (println (dim (str "    ✓ " up-to-date " up to date"))))))
    (count outdated)))

;; =============================================================================
;; In-place upgrade
;; =============================================================================

(defn- apply-update!
  "Replaces one coord's version in the file content. Returns updated content."
  [content coord current latest]
  (let [coord-s  (str coord)
        ;; Match: <coord> <optional-whitespace> {:mvn/version "<current>"}
        ;; Using Pattern/quote so dots and slashes in coord names are literal.
        pattern  (re-pattern
                  (str (java.util.regex.Pattern/quote coord-s)
                       "\\s+\\{\\s*:mvn/version\\s+\""
                       (java.util.regex.Pattern/quote current)
                       "\"\\s*\\}"))
        replacement (str coord-s " {:mvn/version \"" latest "\"}")
        updated  (str/replace content pattern (constantly replacement))]
    (if (= updated content)
      (do (println (str "    " (yellow "!") " Could not auto-update " (cyan coord-s) " — update manually"))
          content)
      (do (println (str "    " (green "✓") " " (cyan coord-s) "  " current "  →  " (bold latest)))
          updated))))

(defn- upgrade-file! [^java.io.File f coords latest-map]
  (let [outdated (outdated-for coords latest-map)]
    (when (seq outdated)
      (let [original (slurp f)
            updated  (reduce (fn [content [coord current latest]]
                               (apply-update! content coord current latest))
                             original
                             outdated)]
        (when (not= original updated)
          (spit f updated))))))

;; =============================================================================
;; Commands
;; =============================================================================

(defn cmd-check
  "Check all (or one) locations and report outdated deps."
  [only-lib]
  (println (bold "\nChecking dependencies across the monorepo..."))
  (let [files       (vec (find-deps-files only-lib))
        by-file     (map (fn [f] [f (coords-from-file f)]) files)
        all-coords  (mapcat (comp keys second) by-file)
        latest-map  (fetch-all-latest all-coords)
        total-outdated
        (reduce (fn [acc [f coords]]
                  (+ acc (print-location (relative-label f) coords latest-map)))
                0
                by-file)]
    (println)
    (if (zero? total-outdated)
      (println (green (bold "✓ All dependencies are up to date.")))
      (do
        (println (yellow (bold (str "↑ " total-outdated " outdated "
                                    (if (= 1 total-outdated) "dependency" "dependencies")
                                    " found."))))
        (println (dim "  Run 'bb upgrade-outdated --update' to apply."))))
    (println)))

(defn cmd-update
  "Check all (or one) locations, apply version upgrades in-place."
  [only-lib]
  (println (bold "\nChecking and upgrading dependencies across the monorepo..."))
  (let [files       (vec (find-deps-files only-lib))
        by-file     (map (fn [f] [f (coords-from-file f)]) files)
        all-coords  (mapcat (comp keys second) by-file)
        latest-map  (fetch-all-latest all-coords)
        total-outdated
        (reduce (fn [acc [f coords]]
                  (let [n (count (outdated-for coords latest-map))]
                    (when (pos? n)
                      (println)
                      (println (bold (str "  " (relative-label f))))
                      (upgrade-file! f coords latest-map))
                    (+ acc n)))
                0
                by-file)]
    (println)
    (if (zero? total-outdated)
      (println (green (bold "✓ All dependencies are already up to date.")))
      (println (green (bold (str "✓ " total-outdated " "
                                 (if (= 1 total-outdated) "dependency" "dependencies")
                                 " upgraded.")))))
    (println)))

(defn print-help []
  (println (bold "bb upgrade-outdated") "— Check and upgrade Maven dependencies across the monorepo")
  (println)
  (println "Usage:")
  (println "  bb upgrade-outdated              Check all deps.edn files, report outdated")
  (println "  bb upgrade-outdated --update     Apply version upgrades in-place")
  (println "  bb upgrade-outdated --lib <name> Only check a specific library (e.g. tenant)")
  (println)
  (println "Notes:")
  (println "  • Checks :deps and all alias :extra-deps / :replace-deps entries")
  (println "  • Git deps (:git/url, :local/root) are skipped")
  (println "  • Queries Clojars first, Maven Central as fallback")
  (println "  • All network calls run in parallel")
  (println))

;; =============================================================================
;; Entry point
;; =============================================================================

(defn -main [& args]
  (let [arg-set  (set args)
        update?  (contains? arg-set "--update")
        help?    (contains? arg-set "--help")
        lib-idx  (.indexOf (vec args) "--lib")
        only-lib (when (and (>= lib-idx 0) (< (inc lib-idx) (count args)))
                   (nth args (inc lib-idx)))]
    (cond
      help?   (print-help)
      update? (cmd-update only-lib)
      :else   (cmd-check only-lib))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
