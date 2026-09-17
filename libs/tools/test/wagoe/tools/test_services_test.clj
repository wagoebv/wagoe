(ns wagoe.tools.test-services-test
  "Guards the service registry against the two ways it can go quiet.

   The registry exists so a missing container is named in one second instead of
   four minutes. It stops being worth anything the moment it disagrees with the
   compose file that starts the services, or with the suites that need them —
   and both drift silently, because nothing fails when a registry is merely
   incomplete (BOU-419)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [wagoe.tools.doctor-env :as doctor-env]
            [wagoe.tools.test-services :as ts]))

(defn- repo-root
  "bb test:tools runs from the repo root; a standalone run inside libs/tools does
   not — try both, then fail rather than silently skipping."
  []
  (let [cwd (System/getProperty "user.dir")]
    (or (first (filter #(.isDirectory (io/file % "libs"))
                       [cwd (str cwd "/../..")]))
        (throw (ex-info "cannot locate repo root (no libs/ dir)" {:cwd cwd})))))

(defn- compose-text []
  (let [f (io/file (repo-root) ts/compose-file)]
    (is (.exists f) (str ts/compose-file " is missing — `bb test:services up` has nothing to start"))
    (slurp f)))

(defn- compose-service-names
  "The top-level service keys of the compose file, in declaration order."
  [text]
  (->> (str/split-lines text)
       (keep #(second (re-matches #"^  ([a-z0-9][a-z0-9_-]*):\s*$" %)))
       vec))

(deftest ^:unit services-are-well-formed
  (testing "every service says where it is and what needs it"
    (is (seq ts/services))
    (doseq [{:keys [id host port needed-by]} ts/services]
      (is (keyword? id))
      (is (string? host))
      (is (int? port) (str id " needs a numeric port"))
      (is (seq needed-by)
          (str id " must name the test namespaces that fail without it — that is"
               " what the preflight prints")))))

(deftest ^:unit every-service-is-in-the-compose-file
  (testing "the registry and docker-compose.test.yml name the same services"
    ;; Either direction is a real gap: a registry entry with no compose service
    ;; makes `bb test:services up` a lie, and a compose service with no registry
    ;; entry is a container nobody is told to start.
    (let [declared (set (compose-service-names (compose-text)))
          known    (set (map (comp name :id) ts/services))]
      (is (= known declared)
          (str "registry " (pr-str (sort known))
               " vs " ts/compose-file " " (pr-str (sort declared))
               " — add the missing side")))))

(deftest ^:unit local-images-match-the-ones-ci-runs
  (testing "every image in the compose file is a service image in ci.yml"
    ;; A sweep is only worth running locally if it compares what CI compares.
    ;; Bumping one side alone is how a suite comes to pass here and fail there,
    ;; or the reverse — the more expensive direction.
    (let [ci (slurp (io/file (repo-root) ".github/workflows/ci.yml"))]
      (doseq [image (->> (compose-text)
                         (re-seq #"(?m)^\s+image:\s*(\S+)\s*$")
                         (map second))]
        (is (true? (str/includes? ci (str "image: " image)))
            (str ts/compose-file " runs " image
                 ", which no CI service container uses — the local sweep and the"
                 " CI sweep are comparing different backends"))))))

(deftest ^:unit needed-by-names-real-test-namespaces
  (testing "every :needed-by points at a test file that exists"
    ;; A renamed suite would otherwise leave the preflight advertising a sweep
    ;; that no longer exists, which is how advice becomes noise.
    (doseq [{:keys [id needed-by]} ts/services
            ns-name needed-by]
      (let [path (-> ns-name
                     (str/replace "-" "_")
                     (str/replace "." "/")
                     (str ".clj"))
            hits (->> (file-seq (io/file (repo-root)))
                      (filter #(str/ends-with? (str %) path))
                      (remove #(str/includes? (str %) "/target/")))]
        (is (seq hits)
            (str id " claims " ns-name " needs it, but no such test file exists"))))))

(deftest ^:unit the-sweeps-that-need-a-service-say-how-to-start-it
  (testing "a sweep failure names the command that fixes it"
    ;; Reading `expected 3, actual 2` at the end of a four-minute run and having
    ;; to work out which container was absent is the whole problem this closes.
    (doseq [{:keys [id needed-by]} ts/services
            ns-name needed-by]
      (let [path (-> ns-name (str/replace "-" "_") (str/replace "." "/") (str ".clj"))
            file (->> (file-seq (io/file (repo-root)))
                      (filter #(str/ends-with? (str %) path))
                      (remove #(str/includes? (str %) "/target/"))
                      first)]
        (when file
          ;; `true?` rather than the `str/includes?` form itself: a failing
          ;; `is` prints its actual value, and that would be the whole file.
          (is (true? (str/includes? (slurp file) "bb test:services up"))
              (str file " guards on " id " but never names `bb test:services up`")))))))

(deftest ^:unit doctor-env-reports-the-test-services
  (testing "`bb doctor:env` covers them too, not just `bb test:all`"
    (let [ids (set (map :id (doctor-env/run-checks)))]
      (is (contains? ids :test-services)
          "doctor:env must include the test-services check"))))
