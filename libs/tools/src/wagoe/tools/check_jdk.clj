#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/check_jdk.clj
;;
;; One JDK baseline. Before this gate there were four answers to "which Java
;; does Wagoe need": the installer required 21, the docs said 21, the root and
;; dev Dockerfiles built and ran 17, `wagoe doctor` accepted 17, and CI pinned
;; nothing at all outside the e2e job — every other job ran whatever the runner
;; image defaults to (BOU-446).
;;
;; Truth is `wagoe.tools.doctor-env/java-min`, because that is the number a user
;; is actually held to.
;;
;; The files are DISCOVERED, not listed. The first version of this gate carried
;; a list, and review immediately found two files that were not on it —
;; publish.yml, which compiles every released artifact, and BUILD.md, which
;; tells users what to install. A third turned up on re-read: docker-compose.yml
;; ran its dev-tools container on 17. A hand-kept list of where a fact is
;; written is the drift this gate exists to catch, so it should not need one.

(ns wagoe.tools.check-jdk
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [wagoe.tools.ansi :as ansi]
            [wagoe.tools.doctor-env :as doctor-env]))

(def jdk-re
  "Every way this repository names a JDK version, digit last.

   One pattern, because a gate with a narrow pattern per file stops looking the
   moment a file says it a new way — BUILD.md said 17 in three spellings.

   The quote around a YAML scalar is optional: `java-version: 17` is valid and
   `actions/setup-java` accepts it, and requiring the quote made that spelling
   invisible. The package forms are here because installation.adoc tells four
   package managers which JDK to install, and each spells it differently —
   `openjdk@21`, `openjdk-21-jdk`, `openjdk21` — so all four could have drifted
   under prose that still said 21."
  #"(?:clojure:temurin-|clojure:openjdk-|eclipse-temurin:|java-version:\s*[\"']?|JAVA_MIN=|Java |JDK |openjdk@|openjdk-|openjdk|temurin@)(\d+)")

(def jdk-infix-re
  "The one package spelling that puts the version in the middle:
   `java-21-openjdk` (dnf/yum)."
  #"java-(\d+)-openjdk")

(def patterns
  "Read with all of these, because no single regex covers both digit positions."
  [jdk-re jdk-infix-re])

(def must-name-a-jdk
  "Files that have to pin the baseline, not merely agree with it.

   Discovery cannot see a missing pin: a workflow that sets up no JDK matches
   nothing and reads as clean, which is exactly how publish.yml built every
   released artifact on the runner default."
  {"Dockerfile"                          "the production image"
   "resources/conf/dev/Dockerfile"       "the dev image"
   "examples/shop/Dockerfile"            "the generated-project example"
   "libs/wagoe-cli/resources/wagoe/cli/templates/Dockerfile.tmpl"
   "what `wagoe new` writes"
   "scripts/install.sh"                  "what the installer enforces"
   ;; Its dev-tools image is a JVM someone develops against. Retagged to
   ;; `clojure:latest` it would name no version, discovery would find nothing,
   ;; and this gate would pass while that environment drifted.
   "docker-compose.yml"                  "the dev-tools container"
   ".github/actions/clojure-deps/action.yml"
   "every CI job that resolves deps"
   ;; Not covered by the action above: example-smoke boots the generated app
   ;; and sets up its own JDK. Without this entry that pin could be deleted and
   ;; the job would silently return to the runner default.
   ".github/workflows/ci.yml"            "the CI jobs with their own setup-java"
   ".github/workflows/publish.yml"       "the release build"
   "BUILD.md"                            "what a user is told to install"
   ;; The pages a newcomer actually reads. BUILD.md was in this set from the
   ;; start and these were not, which is the same omission one file along.
   "docs/modules/getting-started/pages/index.adoc"
   "the quickstart's prerequisites"
   "docs/modules/getting-started/pages/installation.adoc"
   "the manual install instructions"
   ;; The asdf pin. It said corretto-25 for months while this gate passed:
   ;; the spelling matched nothing, so it was neither read nor missed (BOU-488).
   ".tool-versions"                      "what asdf puts on a developer's PATH"})

(def exempt
  "Files that name a JDK for some reason other than the baseline, and why.

   Each entry is a claim that the number there is not an instruction. A stale
   entry is reported, so this stays a list of real exceptions."
  {"CHANGELOG.md"
   "records which JDK past releases ran on"

   "dev-docs/scaling-guide.adoc"
   "says which JDK made a JVM flag the default — a fact about the JVM, not an instruction"

   "scripts/first-run-preconditions.sh"
   "asserts the installer REFUSES an older JDK — the old number is the fixture"

   ".github/workflows/first-run-matrix.yml"
   "names the images that can supply an old JDK, for the same refusal test"

   "libs/tools/src/wagoe/tools/check_jdk.clj"
   "this gate's own pattern and prose"

   "libs/tools/test/wagoe/tools/check_jdk_test.clj"
   "fixtures for this gate"

   "libs/tools/test/wagoe/tools/gate_firing_test.clj"
   "fixtures for this gate"

   "libs/tools/test/wagoe/tools/check_doc_counts_test.clj"
   "fixture prose that happens to contain a version-shaped phrase"})

(def exempt-prefixes
  "Whole trees that describe what was true when they were written.

   Deliberately narrower than `dev-docs/`: that tree also holds operator
   instructions — a deployment snippet added to `dev-docs/reference/` is a real
   instruction, and a blanket exemption would hide it."
  {"dev-docs/adr/"              "decision records describe the state at the time"
   "dev-docs/presentations/"    "delivered talks"
   "dev-docs/reference/launch/" "launch material, written for one moment"})

(def overrides
  "Per-file patterns, where the shared ones read something that is not a pin."
  {"scripts/install.sh" [#"JAVA_MIN=(\d+)"]
   ;; `java <distribution>-<major>...` — temurin-21.0.12+101.0.LTS, corretto-25.0.3.9.1.
   ;; One pattern for every distribution asdf-java offers; the major is the
   ;; first digit run after the name.
   ".tool-versions"     [#"(?m)^java\s+\S*?(\d+)"]})

(defn- exempt? [path]
  (or (contains? exempt path)
      (some (fn [[prefix _]] (str/starts-with? path prefix)) exempt-prefixes)))

(defn versions-in
  "Every JDK major named in `content`, using `path`'s patterns."
  [path content]
  (for [re (get overrides path patterns)
        m  (re-seq re content)]
    (parse-long (second m))))

(defn tracked-files
  "Every file git tracks.

   Throws when git fails: a gate that reports clean because it could not look is
   the failure mode this whole family of checks exists to prevent."
  []
  (let [{:keys [exit out err]} (process/shell {:out :string :err :string :continue true}
                                              "git" "ls-files")]
    (when-not (zero? exit)
      (throw (ex-info (str "git ls-files failed (exit " exit ") — cannot determine "
                           "which files to scan")
                      {:exit exit :err (str/trim (or err ""))})))
    (->> (str/split-lines out)
         (remove str/blank?))))

(defn- readable [path]
  (when (fs/exists? path)
    (try (slurp path) (catch Exception _ nil))))

(defn findings
  "Everything that disagrees with `expected`, given a file list and a reader.

   Four kinds: a scanned file naming another JDK, a file that must pin one and
   does not, and an exemption — file or prefix — that no longer matches
   anything."
  [expected files read-file]
  (let [scanned  (for [path  files
                       :when (not (exempt? path))
                       :let  [content (read-file path)]
                       :when content
                       :let  [versions (versions-in path content)]
                       :when (some #(not= expected %) versions)]
                   {:path path
                    :what (get must-name-a-jdk path "names a JDK")
                    :problem (str "declares JDK " (str/join ", " (sort (distinct versions)))
                                  " — expected " expected)})
        unpinned (for [[path why] must-name-a-jdk
                       :let [content (read-file path)]
                       :when (or (nil? content)
                                 (empty? (versions-in path content)))]
                   {:path path :what why
                    :problem (if content
                               "names no JDK at all — the pin is gone"
                               "not found")})
        stale    (for [[path why] exempt
                       :let [content (read-file path)]
                       :when (or (nil? content)
                                 (empty? (versions-in path content)))]
                   {:path path :what why
                    :problem "exempt, but no longer names a JDK — drop the exemption"})
        ;; The same burn-down for whole trees. Without it a prefix could go on
        ;; exempting nothing, which is a claim about files that have moved.
        stale-prefixes (for [[prefix why] exempt-prefixes
                             :when (not-any? (fn [path]
                                               (and (str/starts-with? path prefix)
                                                    (some-> (read-file path)
                                                            (->> (versions-in path))
                                                            seq)))
                                             files)]
                         {:path prefix :what why
                          :problem "exempt prefix, but nothing under it names a JDK — drop it"})]
    (vec (concat scanned unpinned stale stale-prefixes))))

(defn -main [& _args]
  (let [expected doctor-env/java-min
        files    (tracked-files)
        problems (findings expected files readable)]
    (if (seq problems)
      (do
        (println (ansi/red (str "JDK baseline is " expected ", and these disagree:")))
        (println)
        (doseq [{:keys [path what problem]} (sort-by :path problems)]
          (println (format "  %-58s %s" (str path " (" what ")") problem)))
        (println)
        (println (str "The baseline lives in wagoe.tools.doctor-env/java-min. "
                      "A file that names a JDK for another reason belongs in "
                      "`exempt`, with a reason."))
        (System/exit 1))
      (do
        (println (ansi/green (str "JDK baseline " expected " agreed across "
                                  (count files) " tracked files.")))
        (System/exit 0)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
