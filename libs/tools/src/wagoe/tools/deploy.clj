#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/deploy.clj
;;
;; Deploy one or more Wagoe libraries to Clojars.
;;
;; Usage (via bb.edn task):
;;   bb deploy                         -- show help
;;   bb deploy --all                   -- deploy all 30 artifacts in dependency order
;;   bb deploy core platform user      -- deploy specific libs
;;   bb deploy --missing               -- deploy only libs not yet on Clojars
;;
;; Environment:
;;   CLOJARS_USERNAME  your Clojars username
;;   CLOJARS_PASSWORD  your Clojars deploy token

(ns wagoe.tools.deploy
  (:require [wagoe.tools.ansi :refer [bold green red yellow dim]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [wagoe.tools.check-poms :as check-poms]))

;; =============================================================================
;; ANSI helpers
;; =============================================================================


;; =============================================================================
;; Library registry (membership only — publish ORDER is derived, see below)
;; =============================================================================

;; This vector is the set of artifacts to publish. Its ORDER is not authoritative:
;; `publish-order` topologically sorts it from each lib's deps.edn so a lib is
;; always published after every wagoe lib it depends on (BOU-203). Add/remove
;; entries here; never hand-tune the order.
(def all-libs
  ["tools"
   "config"
   "core"
   "observability"
   "platform"
   "audience"
   "i18n"
   "user"
   "storage"
   "scaffolder"
   "cache"
   "events"
   "jobs"
   "push"
   "realtime"
   "email"
   "tenant"
   "workflow"
   "search"
   "external"
   "payments"
   "geo"
   "reports"
   "calendar"
   "ai"
   "ui-style"
   "shared-ui"
   "admin"
   "wagoe-cli"
   "devtools"
   "wagoe-mcp"])

(def valid-libs (set all-libs))
(def root-dir (System/getProperty "user.dir"))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn lib-dir [lib]
  (str (io/file root-dir "libs" lib)))

;; =============================================================================
;; Publish order — topological sort of all-libs by deps.edn wagoe deps
;; =============================================================================

(defn wagoe-dep-dirs
  "The directory names of the wagoe libs `lib` depends on, per its deps.edn
   (via check-poms — the same :local/root parsing the check:poms gate uses)."
  [lib]
  (map :dir (check-poms/wagoe-local-deps (io/file (lib-dir lib)))))

(defn topo-sort
  "Reorder `libs` so every lib follows all of its deps (per `dep-fn`). Stable:
   among libs whose deps are all satisfied, the earliest in the original order
   wins, so the output is deterministic and stays close to the input. Deps not
   in `libs` are ignored. Throws on a cycle (check:deps already forbids cycles)."
  [libs dep-fn]
  (let [libset (set libs)
        deps   (into {} (map (fn [l] [l (set (filter libset (dep-fn l)))])) libs)]
    (loop [result [] remaining (vec libs)]
      (if (empty? remaining)
        result
        (let [done (set result)
              nxt  (first (filter #(every? done (deps %)) remaining))]
          (if nxt
            (recur (conj result nxt) (vec (remove #{nxt} remaining)))
            (throw (ex-info "Cycle in deploy dependency graph"
                            {:remaining remaining}))))))))

(def publish-order
  "all-libs in a valid publish order: each lib after its wagoe deps."
  (topo-sort all-libs wagoe-dep-dirs))

(defn artifact-name
  "Clojars artifact id for a lib, read from its build.clj coordinate
   `(def lib 'com.wagoe/<artifact>)`. Reading the coordinate (rather than
   string-prefixing) avoids a double `wagoe-` for libs whose dir already starts
   with it (e.g. wagoe-cli → wagoe-cli, not wagoe-wagoe-cli). Falls
   back to `wagoe-<lib>` when build.clj is unreadable."
  [lib]
  (let [build-file (io/file (lib-dir lib) "build.clj")]
    (or (when (.exists build-file)
          (second (re-find #"\(def lib '[^/]+/([^\)\s]+)" (slurp build-file))))
        (str "wagoe-" lib))))

(defn read-version [lib]
  (let [build-file (io/file (lib-dir lib) "build.clj")]
    (when (.exists build-file)
      (second (re-find #"\(def version \"([^\"]+)\"" (slurp build-file))))))

(defn pom-url
  "Clojars repo URL of a lib's published pom. Note the group appears here in
   PATH form (com/wagoe), not coord form (com.wagoe) — a rename that only
   rewrites the coord form leaves this pointing at the old group, and every
   artifact then reports as unpublished (BOU-213). Extracted so the group is
   assertable without a network call."
  [lib]
  (let [version  (read-version lib)
        artifact (artifact-name lib)]
    (format "https://clojars.org/repo/com/wagoe/%s/%s/%s-%s.pom"
            artifact version artifact version)))

(defn published? [lib]
  (= 200 (:status (http/get (pom-url lib) {:throw false}))))

(defn version-mismatches
  "Seq of {:lib :actual :expected} for libraries whose build.clj version differs
   from `expected`. Empty when every lib is in lockstep. Used as a pre-deploy
   guard so a release tag can never be published from source carrying a different
   version (the failure mode that froze a stale jar under an immutable Clojars
   coordinate)."
  [expected]
  (keep (fn [lib]
          (let [actual (read-version lib)]
            (when (not= actual expected)
              {:lib lib :actual actual :expected expected})))
        all-libs))

(defn unpublished-libs
  "The libs for which `published-fn` is falsey, order preserved. `published-fn`
   is injected so the pure selection is testable without hitting the network;
   callers pass `published?`."
  [libs published-fn]
  (filterv (fn [lib] (not (published-fn lib))) libs))

(defn check-env! []
  (when (or (str/blank? (System/getenv "CLOJARS_USERNAME"))
            (str/blank? (System/getenv "CLOJARS_PASSWORD")))
    (println (red "Error: CLOJARS_USERNAME and CLOJARS_PASSWORD must be set."))
    (System/exit 1)))

(defn wait-for-indexing []
  (println (dim "  Waiting 30s for Clojars indexing..."))
  (Thread/sleep 30000))

;; =============================================================================
;; Deploy
;; =============================================================================

(def ^:private catalogue-path
  "libs/wagoe-cli/resources/wagoe/cli/modules-catalogue.edn")

(defn- patch-catalogue-version!
  "Update :version for lib-name in modules-catalogue.edn after a successful deploy."
  [lib-name new-version]
  (let [f       (io/file catalogue-path)
        content (slurp f)
        pattern (re-pattern (str "(?s)(\\{[^}]*:name\\s+\"" (java.util.regex.Pattern/quote lib-name) "\"[^}]*:version\\s+\")([^\"]+)(\")"))]
    (if (re-find pattern content)
      (do (spit f (str/replace content pattern (str "$1" new-version "$3")))
          (println (green (str "  Catalogue updated: " lib-name " → " new-version))))
      (println (dim (str "  Catalogue: no entry for " lib-name " (skipping)"))))))

(defn request-cljdoc-build!
  "Ask cljdoc to build docs for a freshly-published artifact. Fire-and-forget:
   cljdoc clones the repo at the pom's <scm><tag> (build.clj emits the bare
   version, which must match the pushed git tag) and renders API docs + source
   links. Non-fatal on failure — the release already succeeded.

   Lived only in scripts/deploy.clj until this was deduplicated, so the path CI
   actually runs never triggered a build. cljdoc polls Clojars and gets there
   on its own eventually, which is why nothing looked broken; asking directly
   just means the docs are current when the release is announced rather than
   whenever the poller comes round.

   `:throw false` alone does not make this non-fatal: it suppresses non-2xx
   RESPONSES, while a transport failure — DNS, connection refused, timeout —
   throws a ConnectException regardless, because there is no response to
   inspect. Unhandled, that would propagate out of `deploy-lib!` and abort the
   rest of a `--all` run, after the current artifact is already on Clojars. A
   cljdoc outage would halt a 29-artifact release halfway for the sake of a
   docs trigger. Recoverable via `--missing`, but not something a
   fire-and-forget helper should ever cause."
  [lib version]
  (let [artifact (str "com.wagoe/" (artifact-name lib))
        warn     (fn [reason]
                   (println (yellow (str "  cljdoc build request failed (" reason
                                         ") — trigger manually at https://cljdoc.org/d/"
                                         artifact "/" version))))]
    (try
      (let [resp (http/post "https://cljdoc.org/api/request-build2"
                            {:form-params {:project artifact :version version}
                             :throw       false})]
        (if (#{200 303} (:status resp))
          (println (green (str "  cljdoc build requested: " artifact " " version)))
          (warn (str "HTTP " (:status resp)))))
      (catch Exception e
        (warn (or (.getMessage e) (.getSimpleName (class e))))))))

(defn deploy-lib! [lib]
  (let [dir     (lib-dir lib)
        version (read-version lib)]
    (when-not version
      (println (red (str "Error: could not read version from " (lib-dir lib) "/build.clj")))
      (System/exit 1))
    (println (bold (str "\nDeploying " (artifact-name lib) " " version "...")))
    (p/shell {:dir dir} "clojure" "-T:build" "clean")
    (p/shell {:dir dir} "clojure" "-T:build" "deploy")
    (println (green (str "✓ " (artifact-name lib) " " version " deployed")))
    (patch-catalogue-version! lib version)
    (request-cljdoc-build! lib version)))

(defn deploy-sequence! [libs]
  (doseq [[i lib] (map-indexed vector libs)]
    (deploy-lib! lib)
    (when (< i (dec (count libs)))
      (wait-for-indexing))))

;; =============================================================================
;; Commands
;; =============================================================================

(defn cmd-all []
  (check-env!)
  (println (bold (str "Deploying all " (count publish-order) " artifacts...")))
  (deploy-sequence! publish-order)
  (println (green "\n✓ All artifacts deployed.")))

(defn cmd-missing []
  (check-env!)
  (println "Checking Clojars for already-published versions...")
  (let [missing (filterv (fn [lib]
                           (let [exists? (published? lib)]
                             (if exists?
                               (println (dim (str "  ⏭  " (artifact-name lib) " " (read-version lib) " already published")))
                               (println (yellow (str "  •  " (artifact-name lib) " " (read-version lib) " not yet published"))))
                             (not exists?)))
                         publish-order)]
    (if (empty? missing)
      (println (green "\nAll artifacts already published. Nothing to do."))
      (do
        (println (bold (str "\nDeploying " (count missing) " missing artifacts...")))
        (deploy-sequence! missing)
        (println (green "\n✓ Done."))))))

(defn cmd-specific [libs]
  (check-env!)
  (let [unknown (remove valid-libs libs)]
    (when (seq unknown)
      (println (red (str "Unknown libraries: " (str/join ", " unknown))))
      (println (str "Valid: " (str/join ", " all-libs)))
      (System/exit 1)))
  (deploy-sequence! libs))

(defn cmd-check-versions
  "Pre-deploy guard: assert every lib's build.clj version equals `expected` (the
   release tag). Exits 1 on any mismatch so the publish workflow aborts before
   shipping a version that disagrees with the tag/source."
  [expected]
  (when (str/blank? expected)
    (println (red "Error: --check-versions requires a version argument."))
    (System/exit 1))
  (let [mismatches (version-mismatches expected)]
    (if (empty? mismatches)
      (println (green (str "✓ All " (count all-libs) " libs at " expected)))
      (do
        (println (red (str "✗ build.clj version mismatch vs expected " expected ":")))
        (doseq [{:keys [lib actual]} mismatches]
          (println (red (str "  " (artifact-name lib) ": " (or actual "<none>")))))
        (System/exit 1)))))

(defn cmd-verify
  "Post-deploy check: assert every artifact's POM is live on Clojars at its
   build.clj version. Exits 1 if any are missing, so a partial/failed deploy
   fails the workflow instead of silently leaving a half-published release."
  []
  (println "Verifying all artifacts are published on Clojars...")
  (let [missing (unpublished-libs publish-order published?)]
    (if (empty? missing)
      (println (green (str "✓ All " (count all-libs) " artifacts published.")))
      (do
        (println (red "✗ Missing on Clojars:"))
        (doseq [lib missing]
          (println (red (str "  " (artifact-name lib) " " (read-version lib)))))
        (System/exit 1)))))

(defn print-help []
  (println (bold "bb deploy") "— Deploy Wagoe libraries to Clojars")
  (println)
  (println "Usage:")
  (println "  bb deploy --all                 Deploy all artifacts in dependency order")
  (println "  bb deploy --missing             Deploy only artifacts not yet on Clojars")
  (println "  bb deploy --check-versions VER  Guard: every build.clj == VER (no deploy)")
  (println "  bb deploy --verify              Check every artifact is live on Clojars")
  (println "  bb deploy <lib> [lib...]        Deploy specific libraries")
  (println)
  (println "Available artifacts (in publish order):")
  (doseq [lib publish-order]
    (println (str "  " lib)))
  (println)
  (println "Environment:")
  (println "  CLOJARS_USERNAME  your Clojars username")
  (println "  CLOJARS_PASSWORD  your Clojars deploy token"))

;; =============================================================================
;; Entry point
;; =============================================================================

(defn -main [& args]
  (cond
    (or (empty? args) (contains? (set args) "--help")) (print-help)
    (= args ["--all"])                                  (cmd-all)
    (= args ["--missing"])                              (cmd-missing)
    (= args ["--verify"])                               (cmd-verify)
    (= (first args) "--check-versions")                 (cmd-check-versions (second args))
    :else                                               (cmd-specific args)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
