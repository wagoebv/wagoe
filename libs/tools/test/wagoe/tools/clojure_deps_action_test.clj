(ns wagoe.tools.clojure-deps-action-test
  "The clojure-deps action's dependency prefetch: its retry, its cache key, and
   what it warms.

   The retry is asserted by running scripts/ci-resolve-deps.sh with a fake
   `clojure` and `sleep` on the PATH, not by grepping the YAML for a word."
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

;; The fake `clojure` fails its first FAKE_FAILS calls with the resolver's own
;; message and FAKE_EXIT, then succeeds. The fake `sleep` records the wait
;; instead of taking it.
(def ^:private fake-clojure
  "#!/usr/bin/env bash
n=$(( $(cat \"$HOME/.calls\" 2>/dev/null || echo 0) + 1 )); echo $n > \"$HOME/.calls\"
echo \"$*\" >> \"$HOME/.args\"
if [ \"$n\" -le \"${FAKE_FAILS:-0}\" ]; then
  echo 'Error building classpath. The following artifacts could not be resolved: io.opentelemetry:opentelemetry-sdk-testing:jar:1.66.0 (absent): Could not find artifact io.opentelemetry:opentelemetry-sdk-testing:jar:1.66.0 in central (https://repo1.maven.org/maven2/)' >&2
  exit \"${FAKE_EXIT:-1}\"
fi
")

(def ^:private fake-sleep
  "#!/usr/bin/env bash
echo \"$1\" >> \"$HOME/.slept\"
")

(defn- with-fakes [f]
  (let [home (fs/create-temp-dir)
        bin (fs/path home "bin")]
    (try
      (fs/create-dirs bin)
      (doseq [[cmd src] {"clojure" fake-clojure "sleep" fake-sleep}]
        (spit (str (fs/path bin cmd)) src)
        (fs/set-posix-file-permissions (fs/path bin cmd) "rwxr-xr-x"))
      (f home)
      (finally (fs/delete-tree home)))))

(defn- resolve! [home env]
  (process/shell {:out :string :err :string :continue true :dir (str home)
                  :extra-env (merge {"HOME" (str home)
                                     "PATH" (str (fs/path home "bin") ":" (System/getenv "PATH"))}
                                    env)}
                 "bash" (str (io/file (repo-root) "scripts" "ci-resolve-deps.sh")) "." "-M:test"))

(defn- lines-of [home file]
  (let [f (fs/path home file)]
    (if (fs/exists? f) (str/split-lines (slurp (str f))) [])))

(deftest ^:unit a-transient-failure-is-retried-until-it-resolves
  (with-fakes
    (fn [home]
      (let [r (resolve! home {"FAKE_FAILS" "3"})]
        (is (zero? (:exit r)) (str (:out r) (:err r)))
        (is (= 4 (count (lines-of home ".args"))) "succeeds on the fourth attempt")
        (is (every? #{"-Sthreads 1 -P -M:test"} (lines-of home ".args")))))))

(deftest ^:unit the-retries-outlast-a-few-minutes-of-central-failing
  (testing "BOU-548's outage failed four attempts over two and a half minutes"
    (with-fakes
      (fn [home]
        (resolve! home {"FAKE_FAILS" "99"})
        (let [waited (reduce + (map parse-long (lines-of home ".slept")))]
          (is (>= waited 300) (str "waits total " waited "s")))))))

(deftest ^:unit a-persistent-failure-gives-up-with-the-resolvers-status
  (with-fakes
    (fn [home]
      (let [r (resolve! home {"FAKE_FAILS" "99" "FAKE_EXIT" "3"})]
        (is (= 3 (:exit r)))
        (is (= 5 (count (lines-of home ".args"))))
        (is (str/includes? (:out r) "Could not find artifact io.opentelemetry"))
        (is (str/includes? (:out r) "after 5 attempts"))))))
