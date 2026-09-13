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
  (:require [wagoe.tools.ansi :refer [bold green yellow dim cyan]]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; ANSI helpers
;; =============================================================================

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

(def ^:private generated-deps-files
  "Manifests a generator writes. Reported, never rewritten.

   `examples/shop` comes from the wagoe-cli template, so a version written here
   is undone by the next `bb example:regen` and fails `bb example:regen --check`
   until then. Bump the template instead."
  #{"examples/shop"})

(defn- manifests-under
  "Every <dir>/*/deps.edn, sorted by directory name."
  [dir-name]
  (let [dir (io/file root-dir dir-name)]
    (when (.exists dir)
      (->> (.listFiles dir)
           (filter #(.isDirectory %))
           (sort-by #(.getName %))
           (map #(io/file % "deps.edn"))
           (filter #(.exists %))
           ;; skip scaffolder template dir
           (remove #(str/includes? (.getPath %) "existing-dir"))))))

(defn- find-deps-files
  "Returns all relevant deps.edn files: root + every lib + every example."
  ([] (find-deps-files nil))
  ([only-lib]
   (let [root-file (io/file root-dir "deps.edn")
         lib-files (manifests-under "libs")
         ;; Examples are real manifests someone copies from, and nothing else
         ;; reports them: examples/todo sat two Clojure releases behind and was
         ;; found by hand (BOU-443).
         example-files (manifests-under "examples")]
     (if only-lib
       (->> lib-files
            (filter #(= (.getName (.getParentFile %)) only-lib))
            (into [root-file]))
       (concat [root-file] lib-files example-files)))))

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

(defn- ask
  "[:ok version], [:absent], or [:unavailable].

   The three used to be one nil, so a registry nobody could reach read as
   \"nothing newer\" and the whole repo reported current (BOU-443 review)."
  [url extract]
  (try
    (let [resp (http/get url {:throw false})]
      (cond
        (= 200 (:status resp)) (if-let [v (extract (:body resp))] [:ok v] [:absent])
        (= 404 (:status resp)) [:absent]
        :else [:unavailable]))
    (catch Exception _ [:unavailable])))

(def ^:dynamic *prereleases?*
  "Offer alphas, betas, release candidates and milestones as upgrade targets."
  false)

(defn- prerelease? [v]
  (boolean (re-find #"(?i)alpha|beta|-rc|\.rc|-M\d|snapshot|preview|-ea\b" (str v))))

(defn- newest
  "The first acceptable version in `versions`, which must be newest-first.

   Falls back to the newest of all when every one is a prerelease, so a library
   that has only ever shipped alphas still reports something."
  [versions]
  (let [vs (remove str/blank? versions)]
    (or (when-not *prereleases?* (first (remove prerelease? vs)))
        (first vs))))

(defn- clojars-latest
  "Clojars' own `latest_release` is not release-only: it is 0.11.0-rc1 for
   reitit and 0.5.243-ALPHA for criterium, which is why both showed up as
   upgrade targets. `recent_versions` is newest-first, so scan that (BOU-474)."
  [group artifact]
  (ask (str "https://clojars.org/api/artifacts/" group "/" artifact)
       #(let [parsed (json/parse-string % true)]
          (or (newest (map :version (:recent_versions parsed)))
              (:latest_release parsed)))))

(defn- maven-central-latest
  "maven-metadata.xml, not search.maven.org/solrsearch.

   solrsearch's `latestVersion` was stale for 27 of the 29 coordinates this
   repo resolves through Central — awssdk 2.46.7 against a real 2.54.17,
   HikariCP 6.3.0 against 7.1.0 — so the sweep reported current what was not
   (BOU-474). The metadata lists every version in release order; its own
   `<release>` field is no help, being 1.13.0-alpha6 for Clojure."
  [group artifact]
  (ask (str "https://repo1.maven.org/maven2/"
            (str/replace group "." "/") "/" artifact "/maven-metadata.xml")
       #(newest (reverse (map second (re-seq #"<version>([^<]+)</version>" %))))))

(defn- latest-version
  "A version string, `::absent` when no registry carries the coordinate, or
   `::unavailable` when one could not be reached."
  [coord]
  (let [[g a]  (coord-parts coord)
        [cs cv] (clojars-latest g a)]
    (if (= :ok cs)
      cv
      (let [[ms mv] (maven-central-latest g a)]
        (cond
          (= :ok ms)                             mv
          (or (= :unavailable cs)
              (= :unavailable ms))               ::unavailable
          :else                                  ::absent)))))

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
  "True when latest is strictly newer than current (numeric comparison).

   `string?`, not `some?`: ::unavailable and ::absent are answers, not versions."
  [latest current]
  (when (and (string? latest) current)
    (pos? (compare (numeric-parts latest) (numeric-parts current)))))

(defn- unresolved
  "Coordinates whose newest version could not be looked up at all."
  [latest-map]
  (sort-by str (keys (filter #(= ::unavailable (val %)) latest-map))))

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
  (let [outdated   (outdated-for coords latest-map)
        ;; A coordinate nobody could look up is not up to date; it is unknown.
        ;; Counting it as current is how an outage read as a clean repo.
        unknown    (count (filter #(= ::unavailable (get latest-map (key %))) coords))
        up-to-date (- (count coords) (count outdated) unknown)
        tally      (str up-to-date " up to date"
                        (when (pos? unknown) (str ", " unknown " not checked")))]
    (println)
    (print (bold (str "  " label)))
    (if (empty? outdated)
      (println (dim (str "  (" tally ")")))
      (do
        (println)
        (doseq [[coord current latest] outdated]
          (println (str "    " (yellow "↑") " "
                        (cyan (str coord)) "  "
                        current "  →  " (bold latest))))
        (when (pos? (+ up-to-date unknown))
          (println (dim (str "    ✓ " tally))))))
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

(defn- report-unresolved!
  "Print the lookups that did not happen. Returns how many there were."
  [latest-map]
  (let [failed (unresolved latest-map)]
    (when (seq failed)
      (println)
      (println (yellow (bold (str "! " (count failed) " lookup"
                                  (when (not= 1 (count failed)) "s")
                                  " failed — this run cannot say those are current."))))
      (doseq [c failed] (println (dim (str "    " c))))
      (println (dim "  A registry was unreachable. Re-run before trusting a clean report.")))
    (count failed)))

(defn cmd-check
  "Check all (or one) locations and report outdated deps.

   Returns the number of lookups that failed, so a caller can tell an
   unfinished sweep from a clean one."
  [only-lib]
  (println (bold "\nChecking dependencies..."))
  (let [files       (vec (find-deps-files only-lib))
        by-file     (map (fn [f] [f (coords-from-file f)]) files)
        all-coords  (mapcat (comp keys second) by-file)
        latest-map  (fetch-all-latest all-coords)
        total-outdated
        (reduce (fn [acc [f coords]]
                  (+ acc (print-location (relative-label f) coords latest-map)))
                0
                by-file)
        failed (count (unresolved latest-map))]
    (println)
    (cond
      ;; Order matters: "up to date" is a claim about every coordinate, and
      ;; during an outage most of them were never looked at.
      (pos? failed)
      (println (yellow (bold (str "? Cannot say whether dependencies are current — "
                                  failed " of " (count latest-map)
                                  " lookups did not complete."))))

      (zero? total-outdated)
      (println (green (bold "✓ All dependencies are up to date.")))

      :else
      (do
        (println (yellow (bold (str "↑ " total-outdated " outdated "
                                    (if (= 1 total-outdated) "dependency" "dependencies")
                                    " found."))))
        (println (dim "  Run 'bb upgrade-outdated --update' to apply."))))
    (report-unresolved! latest-map)
    (println)
    failed))

(defn cmd-update
  "Check all (or one) locations, apply version upgrades in-place.

   Returns the number of lookups that failed, like `cmd-check`: a sweep that
   could not reach a registry upgraded only part of what it should have."
  [only-lib]
  (println (bold "\nChecking and upgrading dependencies across the monorepo..."))
  (let [files       (vec (find-deps-files only-lib))
        by-file     (map (fn [f] [f (coords-from-file f)]) files)
        all-coords  (mapcat (comp keys second) by-file)
        latest-map  (fetch-all-latest all-coords)
        {:keys [upgraded skipped]}
        (reduce (fn [acc [f coords]]
                  (let [label (relative-label f)
                        n     (count (outdated-for coords latest-map))]
                    (if (zero? n)
                      acc
                      (do
                        (println)
                        (println (bold (str "  " label)))
                        (if (generated-deps-files label)
                          (do (println (dim (str "    generated — not rewritten; bump the "
                                                 "template and run bb example:regen")))
                              (update acc :skipped + n))
                          (do (upgrade-file! f coords latest-map)
                              (update acc :upgraded + n)))))))
                {:upgraded 0 :skipped 0}
                by-file)
        failed (count (unresolved latest-map))]
    (println)
    (cond
      ;; Same order as cmd-check, and for the same reason: "already up to date"
      ;; is a claim about every coordinate, and during an outage most were
      ;; never looked at.
      (pos? failed)
      (println (yellow (bold (str "? Upgraded " upgraded " of what could be checked — "
                                  failed " of " (count latest-map)
                                  " lookups did not complete."))))

      (zero? (+ upgraded skipped))
      (println (green (bold "✓ All dependencies are already up to date.")))

      :else
      (println (green (bold (str "✓ " upgraded " "
                                 (if (= 1 upgraded) "dependency" "dependencies")
                                 " upgraded.")))))
    (when (pos? skipped)
      (println (yellow (str "  " skipped " left in generated files — change the template."))))
    (report-unresolved! latest-map)
    (println)
    failed))

(defn print-help []
  (println (bold "bb upgrade-outdated") "— Check and upgrade Maven dependencies across the monorepo")
  (println)
  (println "Usage:")
  (println "  bb upgrade-outdated              Check all deps.edn files, report outdated")
  (println "  bb upgrade-outdated --update     Apply version upgrades in-place")
  (println "  bb upgrade-outdated --lib <name> Only check a specific library (e.g. tenant)")
  (println "  bb upgrade-outdated --prereleases Offer alphas, betas and RCs too")
  (println)
  (println "Notes:")
  (println "  • Covers root, libs/*/deps.edn and examples/*/deps.edn")
  (println "  • Checks :deps and all alias :extra-deps / :replace-deps entries")
  (println "  • Git deps (:git/url, :local/root) are skipped")
  (println "  • examples/shop is generated — reported, never rewritten")
  (println "  • Queries Clojars first, then Maven Central's maven-metadata.xml")
  (println "  • Prereleases are not upgrade targets unless --prereleases is given")
  (println "  • Exits non-zero when a registry could not be reached, so an")
  (println "    unfinished sweep is not mistaken for a clean one")
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
    (if help?
      (print-help)
      ;; One exit path for both commands. Non-zero when a registry could not be
      ;; reached: a caller that sees 0 is entitled to conclude the sweep
      ;; finished. Giving each mode its own branch is exactly how --update went
      ;; on reporting success after looking at nothing (BOU-443 review).
      (let [failed (binding [*prereleases?* (contains? arg-set "--prereleases")]
                     ((if update? cmd-update cmd-check) only-lib))]
        (when (pos? failed) (System/exit 1))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
