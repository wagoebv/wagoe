(ns wagoe.tools.clojure-deps-action-test
  "The dependency retry has to clear Maven's record of the failure first.

   Maven writes a `*.lastUpdated` marker beside an artifact it could not
   download, and its update policy for releases is `never` — so a later
   resolution reads the marker instead of the network. The four retries in
   `.github/actions/clojure-deps` waited 140 seconds between them and achieved
   nothing, and `~/.m2/repository` is what actions/cache saves, so one 403
   during warm-deps was restored into all 30 isolation cells (BOU-440).

   A transient 404 records an empty `.error=`, like a genuine absence, so the
   retry also clears the markers of artifacts the error names (BOU-548).

   Asserted by running scripts/ci-resolve-deps.sh against a fake ~/.m2, not by
   grepping the YAML for a word: what matters is which markers it removes."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- action-file []
  (let [cwd (System/getProperty "user.dir")]
    (or (first (filter #(.exists ^java.io.File %)
                       [(io/file cwd ".github" "actions" "clojure-deps" "action.yml")
                        (io/file cwd ".." ".." ".github" "actions" "clojure-deps" "action.yml")]))
        (throw (ex-info "clojure-deps action not found" {:cwd cwd})))))

(defn- prefetch-script []
  (->> (get-in (yaml/parse-string (slurp (action-file))) [:runs :steps])
       (filter #(str/includes? (or (:name %) "") "Prefetch"))
       first
       :run))

(deftest ^:unit the-prefetch-step-retries-through-the-script
  (testing "the retry is the tested script, not an untested copy in the YAML"
    (is (str/includes? (str (prefetch-script)) "scripts/ci-resolve-deps.sh"))))

(defn- ci-file []
  (let [cwd (System/getProperty "user.dir")]
    (or (first (filter #(.exists ^java.io.File %)
                       [(io/file cwd ".github" "workflows" "ci.yml")
                        (io/file cwd ".." ".." ".github" "workflows" "ci.yml")]))
        (throw (ex-info "ci.yml not found" {:cwd cwd})))))

(defn- repo-root []
  ;; .github/workflows/ci.yml -> workflows -> .github -> the repository.
  (-> (ci-file) .getParentFile .getParentFile .getParentFile))

(deftest ^:unit the-cache-key-and-its-restore-prefix-carry-the-same-version
  (testing "a mismatch silently restores the previous cache under the new key,
            which is the poisoning the version segment exists to escape"
    (let [cache (->> (get-in (yaml/parse-string (slurp (action-file))) [:runs :steps])
                     (filter #(str/includes? (or (:name %) "") "Cache"))
                     first
                     :with)
          version-of #(second (re-find #"clojure-(v\d+)-" (str %)))]
      (is (some? (:key cache)) "the cache step has no key")
      (is (= (version-of (:key cache)) (version-of (:restore-keys cache)))
          (str "key is " (version-of (:key cache))
               ", restore-keys is " (version-of (:restore-keys cache)))))))

(deftest ^:unit every-library-the-matrix-builds-is-warmed-first
  (testing "a library in the isolation matrix but not in warm-deps' also-warm
            resolves its own deps from Central, in 30 cells at once, with
            nothing cached behind it (BOU-441)"
    (let [ci (yaml/parse-string (slurp (ci-file)))
          jobs (:jobs ci)
          matrix (->> (get-in jobs [:check-isolation-matrix :strategy :matrix :include])
                      (mapcat :libs))
          warm (->> (get-in jobs [:warm-deps :steps])
                    (keep #(get-in % [:with :also-warm]))
                    first)]

      (testing "both halves were found"
        (is (seq matrix) "no isolation matrix — this test would pass vacuously")
        (is (some? warm) "warm-deps names no directories to warm"))

      (testing "and the glob resolves to a directory for every library in it"
        ;; Expanded against the tree rather than string-matched: "libs/*" is
        ;; what covers a library added tomorrow, and a hand-kept list is what
        ;; would not.
        (let [expand (fn [entry]
                       (if (str/ends-with? entry "/*")
                         (->> (.listFiles (io/file (repo-root)
                                                   (str/replace entry #"/\*$" "")))
                              (filter #(.isDirectory ^java.io.File %))
                              (map #(.getName ^java.io.File %)))
                         ;; A literal directory covers exactly itself.
                         [(.getName (io/file entry))]))
              covered (->> (str/split (str warm) #"\s+")
                           (remove str/blank?)
                           (mapcat expand)
                           set)
              missing (remove covered (map name matrix))]
          (is (empty? missing)
              (str "the matrix builds these, and nothing warms their deps: "
                   (pr-str (sort missing)))))))))

;; The fake `clojure` fails for as long as FAKE_BLOCKER exists, which is what
;; Maven does while a marker is in place.
(def ^:private fake-clojure
  "#!/usr/bin/env bash
n=$(( $(cat \"$HOME/.calls\" 2>/dev/null || echo 0) + 1 )); echo $n > \"$HOME/.calls\"
if { [ -n \"${FAKE_BLOCKER:-}\" ] && [ -e \"$FAKE_BLOCKER\" ]; } || [ \"${FAKE_ALWAYS:-}\" = fail ]; then
  echo 'Error building classpath. Could not find artifact io.opentelemetry:opentelemetry-sdk-testing:jar:1.66.0 (absent)' >&2
  exit 1
fi
")

(def ^:private absent-marker
  ".m2/repository/io/opentelemetry/opentelemetry-sdk-testing/1.66.0/opentelemetry-sdk-testing-1.66.0.jar.lastUpdated")

(def ^:private sources-marker
  ".m2/repository/foo/bar/1.0/bar-1.0-sources.jar.lastUpdated")

(def ^:private transfer-marker
  ".m2/repository/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar.lastUpdated")

(def ^:private empty-error "#NOTE\nhttps\\://repo.maven.apache.org/maven2/.error=\n")

(defn- with-m2 [f]
  (let [home (fs/create-temp-dir)
        mk (fn [rel content]
             (let [p (fs/path home rel)]
               (fs/create-dirs (fs/parent p))
               (spit (str p) content)
               p))]
    (try
      (fs/create-dirs (fs/path home "bin"))
      (spit (str (fs/path home "bin" "clojure")) fake-clojure)
      (fs/set-posix-file-permissions (fs/path home "bin" "clojure") "rwxr-xr-x")
      (f home mk)
      (finally (fs/delete-tree home)))))

(defn- resolve! [home env]
  (process/shell {:out :string :err :string :continue true :dir (str home)
                  :extra-env (merge {"HOME" (str home)
                                     "PATH" (str (fs/path home "bin") ":" (System/getenv "PATH"))
                                     "RESOLVE_RETRY_DELAY" "0"}
                                    env)}
                 "bash" (str (io/file (repo-root) "scripts" "ci-resolve-deps.sh")) "." "-M:test"))

(defn- calls [home]
  (parse-long (str/trim (slurp (str (fs/path home ".calls"))))))

(deftest ^:unit it-clears-a-transfer-failure-and-keeps-a-genuine-absence
  (with-m2
    (fn [home mk]
      ;; What Maven writes when the download failed…
      (mk transfer-marker (str "#NOTE\nhttps\\://repo.maven.apache.org/maven2/.error="
                               "Could not transfer artifact: status code: 403\n"))
      ;; …and when an artifact was never published. Every -sources.jar that does
      ;; not exist has one, and re-checking each on every attempt buys nothing.
      (mk sources-marker empty-error)
      (let [r (resolve! home {})]
        (is (zero? (:exit r)) (str (:out r) (:err r)))
        (is (not (fs/exists? (fs/path home transfer-marker))))
        (is (fs/exists? (fs/path home sources-marker)))
        (is (str/includes? (:out r) "cleared 1"))))))

(deftest ^:unit a-cached-transient-404-is-cleared-for-the-retry
  (testing "a transient miss records an empty .error=, like a genuine one, so
            the retry clears the marker of the artifact the error names"
    (with-m2
      (fn [home mk]
        (let [marker (mk absent-marker empty-error)]
          (mk sources-marker empty-error)
          (let [r (resolve! home {"FAKE_BLOCKER" (str marker)})]
            (is (zero? (:exit r)) (str (:out r) (:err r)))
            (is (= 2 (calls home)) "succeeds on the second attempt")
            (is (not (fs/exists? marker)))
            (is (fs/exists? (fs/path home sources-marker))
                "a marker the error does not name is kept")))))))

(deftest ^:unit a-genuine-404-still-fails-after-the-retries
  (with-m2
    (fn [home _mk]
      (let [r (resolve! home {"FAKE_ALWAYS" "fail"})]
        (is (= 1 (:exit r)))
        (is (= 4 (calls home)))
        (is (str/includes? (:out r) "after 4 attempts"))))))

