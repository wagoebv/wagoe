#!/usr/bin/env bb
;; scripts/deploy.clj
;;
;; Deploy one or more Wagoe libraries to Clojars.
;;
;; Usage (via bb.edn task):
;;   bb deploy                         -- show help
;;   bb deploy --all                   -- deploy all 23 libs in dependency order
;;   bb deploy core platform user      -- deploy specific libs
;;   bb deploy --missing               -- deploy only libs not yet on Clojars
;;
;; Usage (direct):
;;   bb scripts/deploy.clj --all
;;
;; Environment:
;;   CLOJARS_USERNAME  your Clojars username
;;   CLOJARS_PASSWORD  your Clojars deploy token

(ns deploy
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [wagoe.tools.check-poms :as check-poms]))

;; =============================================================================
;; ANSI helpers
;; =============================================================================

(defn bold   [s] (str "\033[1m"  s "\033[0m"))
(defn green  [s] (str "\033[32m" s "\033[0m"))
(defn red    [s] (str "\033[31m" s "\033[0m"))
(defn yellow [s] (str "\033[33m" s "\033[0m"))
(defn dim    [s] (str "\033[2m"  s "\033[0m"))

;; =============================================================================
;; Library registry (membership only — publish ORDER is derived, see below)
;; =============================================================================

;; Keep the SET in sync with libs/tools/src/wagoe/tools/deploy.clj all-libs
;; (the canonical registry `bb deploy` publishes from). The ORDER here is not
;; authoritative: `publish-order` topologically sorts it from each lib's deps.edn
;; (BOU-203), so both registries derive the same order and can't drift on it.
(def all-libs
  ["tools"
   "core"
   "observability"
   "platform"
   "audience"
   "i18n"
   "user"
   "storage"
   "scaffolder"
   "cache"
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

;; Publish order — topological sort of all-libs by deps.edn wagoe deps.
;; Mirrors wagoe.tools.deploy (canonical); both derive the same order.
(defn wagoe-dep-dirs [lib]
  (map :dir (check-poms/wagoe-local-deps (io/file (lib-dir lib)))))

(defn topo-sort
  "Reorder `libs` so every lib follows all of its deps (per `dep-fn`). Stable:
   earliest-in-original-order among ready libs wins. Throws on a cycle."
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

(def publish-order (topo-sort all-libs wagoe-dep-dirs))

(defn read-version [lib]
  (let [build-file (io/file (lib-dir lib) "build.clj")]
    (when (.exists build-file)
      (second (re-find #"\(def version \"([^\"]+)\"" (slurp build-file))))))

(defn artifact-name
  "Clojars artifact id for a lib, read from its build.clj coordinate
   `(def lib 'org.wagoe/<artifact>)`. Reading the coordinate (rather than
   string-prefixing) avoids a double `wagoe-` for libs whose dir already starts
   with it (e.g. wagoe-cli → wagoe-cli, not wagoe-wagoe-cli). Falls
   back to `wagoe-<lib>` when build.clj is unreadable."
  [lib]
  (let [build-file (io/file (lib-dir lib) "build.clj")]
    (or (when (.exists build-file)
          (second (re-find #"\(def lib '[^/]+/([^\)\s]+)" (slurp build-file))))
        (str "wagoe-" lib))))

(defn published? [lib]
  (let [version  (read-version lib)
        artifact (artifact-name lib)
        url      (format "https://clojars.org/repo/org/wagoe/%s/%s/%s-%s.pom"
                         artifact version artifact version)
        response (http/get url {:throw false})]
    (= 200 (:status response))))

(defn check-env! []
  (when (or (str/blank? (System/getenv "CLOJARS_USERNAME"))
            (str/blank? (System/getenv "CLOJARS_PASSWORD")))
    (println (red "Error: CLOJARS_USERNAME and CLOJARS_PASSWORD must be set."))
    (System/exit 1)))

(defn wait-for-indexing []
  (println (dim "  Waiting 30s for Clojars indexing..."))
  (Thread/sleep 30000))

;; =============================================================================
;; Catalogue patch
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

;; =============================================================================
;; cljdoc
;; =============================================================================

(defn request-cljdoc-build!
  "Ask cljdoc to build docs for a freshly-published artifact. Fire-and-forget:
   cljdoc clones the repo at the pom's <scm><tag> (build.clj emits the bare
   version, which must match the pushed git tag) and renders API docs + source
   links. Non-fatal on failure — the release already succeeded."
  [lib version]
  (let [artifact (str "org.wagoe/" (artifact-name lib))
        resp     (http/post "https://cljdoc.org/api/request-build2"
                            {:form-params {:project artifact :version version}
                             :throw       false})]
    (if (#{200 303} (:status resp))
      (println (green (str "  cljdoc build requested: " artifact " " version)))
      (println (yellow (str "  cljdoc build request failed (HTTP " (:status resp)
                            ") — trigger manually at https://cljdoc.org/d/" artifact "/" version))))))

;; =============================================================================
;; Deploy
;; =============================================================================

(defn deploy-lib! [lib]
  (let [dir     (lib-dir lib)
        version (read-version lib)]
    (when-not version
      (println (red (str "Error: could not read version from libs/" lib "/build.clj")))
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
  (println (bold (str "Deploying all " (count publish-order) " libraries...")))
  (deploy-sequence! publish-order)
  (println (green "\n✓ All libraries deployed.")))

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
      (println (green "\nAll libraries already published. Nothing to do."))
      (do
        (println (bold (str "\nDeploying " (count missing) " missing libraries...")))
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

(defn print-help []
  (println (bold "bb deploy") "— Deploy Wagoe libraries to Clojars")
  (println)
  (println "Usage:")
  (println "  bb deploy --all              Deploy all 23 libraries in dependency order")
  (println "  bb deploy --missing          Deploy only libraries not yet on Clojars")
  (println "  bb deploy <lib> [lib...]     Deploy specific libraries")
  (println)
  (println "Available libraries (in publish order):")
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
    :else                                               (cmd-specific args)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
