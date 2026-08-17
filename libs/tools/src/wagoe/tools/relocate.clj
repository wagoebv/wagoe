#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/relocate.clj
;;
;; Publish Maven relocation stubs under the retired org.boundary-app group so
;; the old coordinates point at their com.wagoe successors (BOU-218).
;;
;; Usage (via bb.edn task):
;;   bb relocate                       -- show help
;;   bb relocate --generate            -- write the stub poms to target/relocation/
;;   bb relocate --deploy              -- generate, then push them to Clojars
;;   bb relocate --verify              -- check every stub is live on Clojars
;;
;; Environment (--deploy only):
;;   CLOJARS_USERNAME  your Clojars username
;;   CLOJARS_PASSWORD  your Clojars deploy token
;;
;; What this does and does not achieve
;; -----------------------------------
;; Two limits, both measured rather than assumed. Read them before expecting
;; this to redirect anybody.
;;
;; 1. Clojars refuses to redeploy a released version, so the already-published
;;    org.boundary-app poms can never gain a <relocation>. Anyone pinned to
;;    `1.0.0-beta-1` or `1.0.1-alpha-42` keeps resolving the old jars — not
;;    fixable, only outlived.
;;
;; 2. tools.deps ignores <relocation> outright. Verified against MySQL's
;;    official relocation-only pom (mysql/mysql-connector-java 8.0.33, no jar
;;    published, relocation to com.mysql/mysql-connector-j):
;;
;;      Error building classpath. Could not find artifact
;;      mysql:mysql-connector-java:jar:8.0.33 in central
;;
;;    The Clojure CLI demands a jar at the original coordinate instead of
;;    following the redirect. Our stubs produce the identical error, so for
;;    deps.edn users a relocation buys nothing functional.
;;
;; What it does buy: the stub becomes the newest version in the old group, so
;; the Clojars page for every old artifact shows the deprecation notice and the
;; new coordinate — the channel that actually reaches people. Maven consumers
;; get the redirect for real. The group stays in place, dormant: deleting it
;; would remove this signpost along with the artifacts it points from.

(ns wagoe.tools.relocate
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [wagoe.tools.deploy :as deploy]))

;; =============================================================================
;; ANSI helpers
;; =============================================================================

(defn bold   [s] (str "\033[1m"  s "\033[0m"))
(defn green  [s] (str "\033[32m" s "\033[0m"))
(defn red    [s] (str "\033[31m" s "\033[0m"))
(defn yellow [s] (str "\033[33m" s "\033[0m"))
(defn dim    [s] (str "\033[2m"  s "\033[0m"))

;; =============================================================================
;; Coordinates
;; =============================================================================

(def old-group "org.boundary-app")
(def new-group "com.wagoe")

(def stub-version
  "Version of the relocation stubs published under the old group.

   Must sort above every version already in org.boundary-app. The highest is
   `1.0.1-alpha-42`, NOT the `1.0.0-beta-1` that Clojars reports as
   `latest_release` — that field tracks push order, while Maven orders 1.0.1
   above 1.0.0. A qualifier-less `1.0.1` sorts above all of its own alphas, so
   it is the lowest version that still wins."
  "1.0.1")

(def out-dir "target/relocation")

(def deps-deploy-version "0.2.3")

(defn old-artifact-name
  "The retired artifact id for a lib, derived from its current one:
   `wagoe-core` -> `boundary-core`. Derived rather than listed so a lib added to
   the deploy registry cannot be silently missed here — the two groups hold the
   same 30 artifacts under a 1:1 name mapping."
  [lib]
  (str/replace-first (deploy/artifact-name lib) #"^wagoe-" "boundary-"))

(defn target-version
  "The com.wagoe version a stub redirects to — read from the lib's build.clj so
   stubs cannot drift from what is actually published."
  [lib]
  (deploy/read-version lib))

;; =============================================================================
;; Pom generation
;; =============================================================================

(def ^:private relocation-message
  "Boundary has been renamed to Wagoe. This artifact now lives at the coordinate below; the org.boundary-app group is no longer maintained. See https://wagoe.org")

(defn stub-pom
  "The relocation pom for `lib` as an XML string.

   Packaging is `pom` and no jar is attached: a relocation carries no code, it
   only tells the resolver where the artifact went."
  [lib]
  (let [old-artifact (old-artifact-name lib)
        new-artifact (deploy/artifact-name lib)
        to-version   (target-version lib)]
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
         "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n"
         "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
         "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
         "  <modelVersion>4.0.0</modelVersion>\n"
         "  <groupId>" old-group "</groupId>\n"
         "  <artifactId>" old-artifact "</artifactId>\n"
         "  <version>" stub-version "</version>\n"
         "  <packaging>pom</packaging>\n"
         "  <name>" old-artifact "</name>\n"
         "  <description>DEPRECATED — moved to " new-group "/" new-artifact ". "
         relocation-message "</description>\n"
         "  <url>https://wagoe.org</url>\n"
         "  <licenses>\n"
         "    <license>\n"
         "      <name>Eclipse Public License 2.0</name>\n"
         "      <url>https://www.eclipse.org/legal/epl-2.0/</url>\n"
         "    </license>\n"
         "  </licenses>\n"
         "  <scm>\n"
         "    <url>https://github.com/wagoebv/wagoe</url>\n"
         "    <connection>scm:git:git://github.com/wagoebv/wagoe.git</connection>\n"
         "    <developerConnection>scm:git:ssh://git@github.com/wagoebv/wagoe.git</developerConnection>\n"
         "  </scm>\n"
         "  <distributionManagement>\n"
         "    <relocation>\n"
         "      <groupId>" new-group "</groupId>\n"
         "      <artifactId>" new-artifact "</artifactId>\n"
         "      <version>" to-version "</version>\n"
         "      <message>" relocation-message "</message>\n"
         "    </relocation>\n"
         "  </distributionManagement>\n"
         "</project>\n")))

(defn pom-path [lib]
  (str out-dir "/" (old-artifact-name lib) "-" stub-version ".pom"))

;; =============================================================================
;; Guards
;; =============================================================================

(defn version-mismatches
  "Seq of {:lib :version} where build.clj versions disagree, i.e. the suite is
   mid-bump. Relocating to a version that is not uniform across the suite would
   point some stubs at a coordinate that was never published together."
  []
  (let [by-version (group-by target-version deploy/all-libs)]
    (when (> (count by-version) 1)
      (map (fn [[v libs]] {:version v :libs libs}) by-version))))

(defn already-published?
  "True when the stub coordinate is already on Clojars. Clojars refuses to
   redeploy a release, so this turns a confusing server-side rejection into a
   clear skip."
  [lib]
  (let [artifact (old-artifact-name lib)
        url      (format "https://clojars.org/repo/%s/%s/%s/%s-%s.pom"
                         (str/replace old-group "." "/")
                         artifact stub-version artifact stub-version)]
    (= 200 (:status (http/get url {:throw false})))))

(defn check-env! []
  (when (or (str/blank? (System/getenv "CLOJARS_USERNAME"))
            (str/blank? (System/getenv "CLOJARS_PASSWORD")))
    (println (red "Error: CLOJARS_USERNAME and CLOJARS_PASSWORD must be set."))
    (System/exit 1)))

;; =============================================================================
;; Commands
;; =============================================================================

(defn generate!
  "Write every stub pom to out-dir. Returns the libs written."
  []
  (when-let [mismatches (version-mismatches)]
    (println (red "Error: build.clj versions are not uniform across the suite:"))
    (doseq [{:keys [version libs]} mismatches]
      (println (red (str "  " version " — " (str/join ", " libs)))))
    (System/exit 1))
  (io/make-parents (str out-dir "/x"))
  (doseq [lib deploy/all-libs]
    (spit (pom-path lib) (stub-pom lib)))
  (println (green (str "✓ Wrote " (count deploy/all-libs) " stub poms to " out-dir "/")))
  (println (dim (str "  " old-group "/<artifact> " stub-version
                     "  →  " new-group "/<artifact> " (target-version (first deploy/all-libs)))))
  deploy/all-libs)

(defn deploy-stub!
  "Push one stub pom to Clojars.

   deps-deploy wants both an :artifact and a :pom-file. Passing the same pom for
   each is deliberate: it derives the extension from the filename, so both
   entries collapse onto a single `pom` artifact and nothing but the pom is
   uploaded — which is what a relocation is."
  [lib]
  (let [pom (pom-path lib)]
    (println (bold (str "\nDeploying " (old-artifact-name lib) " " stub-version " (relocation stub)...")))
    (p/shell "clojure"
             "-Sdeps" (format "{:deps {slipset/deps-deploy {:mvn/version \"%s\"}}}" deps-deploy-version)
             "-X" "deps-deploy.deps-deploy/deploy"
             ":installer" ":remote"
             ":artifact" (pr-str pom)
             ":pom-file" (pr-str pom))
    (println (green (str "✓ " (old-artifact-name lib) " " stub-version " deployed")))))

(defn cmd-generate []
  (generate!)
  (println (dim "\nReview a pom, then: bb relocate --deploy")))

(defn cmd-deploy []
  (check-env!)
  (generate!)
  (println "\nChecking which stubs are already on Clojars...")
  (let [todo (filterv (fn [lib]
                        (let [done? (already-published? lib)]
                          (when done?
                            (println (dim (str "  ⏭  " (old-artifact-name lib) " " stub-version " already published"))))
                          (not done?)))
                      deploy/all-libs)]
    (if (empty? todo)
      (println (green "\nAll stubs already published. Nothing to do."))
      (do
        (println (bold (str "\nDeploying " (count todo) " relocation stubs...")))
        (run! deploy-stub! todo)
        (println (green (str "\n✓ " (count todo) " stubs deployed.")))))))

(defn cmd-verify []
  (println "Verifying relocation stubs on Clojars...")
  (let [missing (remove already-published? deploy/all-libs)]
    (if (empty? missing)
      (println (green (str "✓ All " (count deploy/all-libs) " stubs live at "
                           old-group " " stub-version)))
      (do
        (println (red "✗ Missing on Clojars:"))
        (doseq [lib missing]
          (println (red (str "  " (old-artifact-name lib) " " stub-version))))
        (System/exit 1)))))

(defn print-help []
  (println (bold "bb relocate") "— Publish org.boundary-app relocation stubs pointing at com.wagoe")
  (println)
  (println "Usage:")
  (println "  bb relocate --generate   Write stub poms to" (str out-dir "/"))
  (println "  bb relocate --deploy     Generate, then push them to Clojars")
  (println "  bb relocate --verify     Check every stub is live on Clojars")
  (println)
  (println (str "Mapping (" (count deploy/all-libs) " artifacts):"))
  (println (str "  " old-group "/<artifact> " stub-version
                "  →  " new-group "/<artifact> " (or (target-version (first deploy/all-libs)) "?")))
  (println)
  (println (yellow "Note:") "already-released old coordinates cannot be given a relocation —")
  (println "  Clojars forbids redeploying a release. Consumers pinned to 1.0.0-beta-1 or")
  (println "  1.0.1-alpha-42 keep resolving the old jars; only the new stub redirects.")
  (println)
  (println "Environment (--deploy only):")
  (println "  CLOJARS_USERNAME  your Clojars username")
  (println "  CLOJARS_PASSWORD  your Clojars deploy token"))

;; =============================================================================
;; Entry point
;; =============================================================================

(defn -main [& args]
  (case (first args)
    "--generate" (cmd-generate)
    "--deploy"   (cmd-deploy)
    "--verify"   (cmd-verify)
    (print-help)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
