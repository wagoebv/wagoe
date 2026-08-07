(ns wagoe.tools.check-deps-test
  "BOU-273: a library could require a third-party namespace without declaring
   the artifact that provides it, and both dependency gates stayed green.

   `check:poms` verifies inter-Wagoe deps only (BOU-202), and the dependency
   graph covers wagoe -> wagoe. Third-party requires were checked by nothing.
   That is how wagoe-ai shipped a POM without tools.cli and every `bb ai`
   subcommand died in a generated project (BOU-272), and how platform and user
   came to resolve tools.cli only through migratus — at 1.0.219, while the
   monorepo pinned 1.4.256."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [wagoe.tools.check-deps :as sut]))

(defn- temp-lib
  "Write a throwaway library dir with the given deps.edn text and one source
   file requiring `required-ns`."
  [deps-text required-ns]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "wagoe-check-deps-test"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        src (io/file dir "src" "wagoe" "probe")]
    (.mkdirs src)
    (spit (io/file dir "deps.edn") deps-text)
    (spit (io/file src "core.clj")
          (str "(ns wagoe.probe.core\n  (:require [" required-ns " :as x]))\n"))
    dir))

(defn- delete-tree! [dir]
  (doseq [f (reverse (file-seq dir))] (.delete f)))

(deftest ^:unit third-party-gaps-finds-undeclared-artifacts
  (testing "a required namespace with no declaring artifact is a gap"
    (let [dir (temp-lib "{:deps {org.clojure/clojure {:mvn/version \"1.12.5\"}}}"
                        "clojure.tools.cli")]
      (try
        (let [gaps (sut/third-party-gaps [["probe" dir]])]
          (is (= [["probe" "org.clojure/tools.cli"]]
                 (map (juxt :lib (comp str :artifact)) gaps))
              "this is the shape that shipped a broken wagoe-ai POM"))
        (finally (delete-tree! dir)))))

  (testing "declaring it closes the gap"
    (let [dir (temp-lib (str "{:deps {org.clojure/clojure {:mvn/version \"1.12.5\"}\n"
                             "        org.clojure/tools.cli {:mvn/version \"1.4.256\"}}}")
                        "clojure.tools.cli")]
      (try
        (is (empty? (sut/third-party-gaps [["probe" dir]])))
        (finally (delete-tree! dir)))))

  (testing "a transitive does not count as declared"
    ;; platform resolved tools.cli through migratus and worked — until migratus
    ;; drops it, and meanwhile at migratus's version rather than the pin.
    (let [dir (temp-lib (str "{:deps {org.clojure/clojure {:mvn/version \"1.12.5\"}\n"
                             "        migratus/migratus {:mvn/version \"1.6.6\"}}}")
                        "clojure.tools.cli")]
      (try
        (is (seq (sut/third-party-gaps [["probe" dir]]))
            "resolving by luck is not the same as declaring")
        (finally (delete-tree! dir)))))

  (testing "unmapped namespaces are not guessed at"
    ;; The prefix map is the gate's coverage. Something outside it is ignored
    ;; rather than reported against an artifact name invented here.
    (let [dir (temp-lib "{:deps {org.clojure/clojure {:mvn/version \"1.12.5\"}}}"
                        "some.unknown.library")]
      (try
        (is (empty? (sut/third-party-gaps [["probe" dir]])))
        (finally (delete-tree! dir))))))

(deftest ^:unit an-unreadable-deps-edn-is-reported-not-swallowed
  ;; The first version of this gate read deps.edn inside a lazy `for`, so a
  ;; malformed file threw where nothing realised it and the checker printed
  ;; "0 violations" and exited 0. Then it threw for real, and babashka printed
  ;; the root cause — "EOF while reading" — naming neither the file nor the
  ;; library.
  (testing "a malformed deps.edn is its own violation"
    (let [dir (temp-lib "{:deps {broken" "clojure.tools.cli")]
      (try
        (is (= [:unreadable] [(sut/declared-artifacts dir)]))
        (is (= [["probe" (.getPath (io/file dir "deps.edn"))]]
               (map (juxt :lib :file) (sut/unreadable-deps-files [["probe" dir]])))
            "the message has to name the file, which the exception did not")
        (finally (delete-tree! dir)))))

  (testing "and it does not masquerade as a wall of missing declarations"
    (let [dir (temp-lib "{:deps {broken" "clojure.tools.cli")]
      (try
        (is (empty? (sut/third-party-gaps [["probe" dir]]))
            "treating unreadable as no-deps would blame every require in the lib")
        (finally (delete-tree! dir)))))

  (testing "a readable file is unaffected"
    (let [dir (temp-lib "{:deps {org.clojure/clojure {:mvn/version \"1.12.5\"}}}"
                        "clojure.tools.cli")]
      (try
        (is (set? (sut/declared-artifacts dir)))
        (is (empty? (sut/unreadable-deps-files [["probe" dir]])))
        (finally (delete-tree! dir))))))

(deftest ^:unit the-repository-declares-what-it-requires
  ;; The regression guard for BOU-273 itself: platform and user require
  ;; clojure.tools.cli for `bb migrate` and `bb create-admin`.
  (testing "platform and user declare tools.cli"
    (doseq [lib ["platform" "user"]]
      (let [deps (slurp (str "libs/" lib "/deps.edn"))]
        (is (str/includes? deps "org.clojure/tools.cli")
            (str lib " requires clojure.tools.cli, so its POM must carry it"))))))

(deftest ^:unit split-artifact-families-map-to-their-real-artifact
  ;; buddy, integrant and reitit are each several artifacts. One entry per
  ;; family made the gate blind inside it: every buddy.* namespace mapped to
  ;; buddy/buddy-core, so removing buddy/buddy-sign from a deps.edn produced no
  ;; violation — the coarse buddy-core gap was already allowlisted and absorbed
  ;; it.
  (testing "buddy namespaces resolve to their own artifacts"
    (is (= 'buddy/buddy-core    (sut/provider-of "buddy.core.codecs")))
    (is (= 'buddy/buddy-hashers (sut/provider-of "buddy.hashers")))
    (is (= 'buddy/buddy-sign    (sut/provider-of "buddy.sign.jwt"))))

  (testing "integrant.repl is a different artifact from integrant"
    (is (= 'integrant/integrant (sut/provider-of "integrant.core")))
    (is (= 'integrant/repl      (sut/provider-of "integrant.repl")))
    (is (= 'integrant/repl      (sut/provider-of "integrant.repl.state"))))

  (testing "reitit is consumed as modules, not the bundle"
    (is (= 'metosin/reitit-core       (sut/provider-of "reitit.core")))
    (is (= 'metosin/reitit-ring       (sut/provider-of "reitit.ring")))
    (is (= 'metosin/reitit-malli      (sut/provider-of "reitit.coercion.malli")))
    (is (= 'metosin/reitit-swagger    (sut/provider-of "reitit.swagger")))
    (is (= 'metosin/reitit-middleware (sut/provider-of "reitit.ring.middleware.muuntaja"))
        "the longer prefix has to win over reitit.ring"))

  (testing "the longest prefix wins, not whichever the map yields first"
    ;; `some` over a map picked an arbitrary match where prefixes overlap:
    ;; hash-map order is not insertion order past eight entries, so
    ;; reitit.ring.middleware.* could have resolved either way depending on
    ;; nothing the caller controls.
    (doseq [[ns-str expected] {"reitit.ring.middleware.exception" 'metosin/reitit-middleware
                               "reitit.ring.coercion"             'metosin/reitit-ring
                               "buddy.core.nonce"                 'buddy/buddy-core}]
      (is (= expected (sut/provider-of ns-str)) ns-str)))

  (testing "a gap inside a family is reported against the right artifact"
    (let [dir (temp-lib (str "{:deps {org.clojure/clojure {:mvn/version \"1.12.5\"}\n"
                             "        buddy/buddy-core {:mvn/version \"1.12.0-430\"}}}")
                        "buddy.sign.jwt")]
      (try
        (is (= [["probe" "buddy/buddy-sign"]]
               (map (juxt :lib (comp str :artifact)) (sut/third-party-gaps [["probe" dir]])))
            "declaring buddy-core must not answer for buddy-sign")
        (finally (delete-tree! dir))))))

(deftest ^:unit an-unmapped-namespace-is-a-violation-not-a-pass
  ;; The map is the gate's coverage, and it was silently incomplete:
  ;; ring.util.response, ring.adapter.jetty, hiccup2.core and a dozen more
  ;; resolved to nil, so removing ring/ring-core from a deps.edn passed. A gate
  ;; that under-covers without saying so is the failure it exists to catch.
  (testing "a namespace no prefix covers is reported"
    (let [dir (temp-lib "{:deps {org.clojure/clojure {:mvn/version \"1.12.5\"}}}"
                        "some.brand.new.library")]
      (try
        (is (= [["probe" "some.brand.new.library"]]
               (map (juxt :lib :ns) (sut/unmapped-third-party-namespaces [["probe" dir]])))
            "adding a dependency must not quietly widen what goes unchecked")
        (finally (delete-tree! dir)))))

  (testing "a mapped namespace is not reported as unmapped"
    (let [dir (temp-lib "{:deps {org.clojure/clojure {:mvn/version \"1.12.5\"}}}"
                        "ring.util.response")]
      (try
        (is (empty? (sut/unmapped-third-party-namespaces [["probe" dir]])))
        (finally (delete-tree! dir)))))

  (testing "Wagoe's own namespaces and the Clojure standard library are not third-party"
    (doseq [n ["wagoe.core.validation" "clojure.string" "clojure.java.io"]]
      (let [dir (temp-lib "{:deps {org.clojure/clojure {:mvn/version \"1.12.5\"}}}" n)]
        (try
          (is (empty? (sut/unmapped-third-party-namespaces [["probe" dir]])) n)
          (finally (delete-tree! dir))))))

  (testing "clojure.tools.* is third-party, whatever its name suggests"
    ;; tools.cli and tools.logging are separate artifacts. Treating the
    ;; clojure. prefix as standard library is the whole of BOU-273.
    (let [dir (temp-lib "{:deps {org.clojure/clojure {:mvn/version \"1.12.5\"}}}"
                        "clojure.tools.cli")]
      (try
        (is (empty? (sut/unmapped-third-party-namespaces [["probe" dir]]))
            "mapped, so not unmapped")
        (is (seq (sut/third-party-gaps [["probe" dir]]))
            "but still undeclared")
        (finally (delete-tree! dir)))))

  (testing "babashka namespaces are exempt only for the libraries bb runs"
    ;; The exemption was global while its justification is per-library: bb
    ;; supplies these to code it runs, and libs/tools is the only such library.
    ;; Any other library requiring babashka.fs needs babashka/fs in its POM, and
    ;; a blanket prefix rule would have let it ship without one.
    (doseq [n ["babashka.fs" "babashka.process" "babashka.http-client"]]
      (let [dir (temp-lib "{:deps {org.clojure/clojure {:mvn/version \"1.12.5\"}}}" n)]
        (try
          (is (empty? (sut/unmapped-third-party-namespaces [["tools" dir]]))
              (str n " is bundled by bb, and libs/tools runs under bb"))
          (is (seq (sut/unmapped-third-party-namespaces [["push" dir]]))
              (str n " in a JVM library is a real dependency"))
          (finally (delete-tree! dir)))))))

(deftest ^:unit every-namespace-this-repository-requires-is-covered
  ;; The guard that keeps the map honest as libraries are added. If this fails,
  ;; the fix is a map entry, not an allowlist entry.
  (testing "no library requires a third-party namespace the map does not know"
    (let [unmapped (sut/unmapped-third-party-namespaces (sut/library-entries))]
      (is (empty? unmapped)
          (str "unmapped: " (pr-str (map (juxt :lib :ns) unmapped)))))))

(deftest ^:unit hiccup2-is-covered-by-the-hiccup-family
  ;; hiccup 2.x moved the API to hiccup2.core, and "hiccup" is not a prefix of
  ;; "hiccup2.core" — so the family looked covered while every hiccup2 user in
  ;; the repository went unchecked.
  (testing "both namespaces resolve to the hiccup artifact"
    (is (= 'hiccup/hiccup (sut/provider-of "hiccup.core")))
    (is (= 'hiccup/hiccup (sut/provider-of "hiccup2.core")))))

(deftest ^:unit clojure-contrib-is-third-party
  ;; The predicate exempted the whole `clojure.` prefix apart from
  ;; `clojure.tools.*`, so a library could require clojure.data.csv or
  ;; clojure.core.async and ship a POM without org.clojure/data.csv — the exact
  ;; defect this gate exists to catch, reintroduced through the exemption.
  ;;
  ;; A prefix rule cannot express this: clojure.data is stdlib and
  ;; clojure.data.csv is contrib; clojure.java.io is stdlib and
  ;; clojure.java.jdbc is contrib. The set is exact, taken from the jar.
  (testing "contrib namespaces are reported"
    (doseq [n ["clojure.data.csv" "clojure.core.async" "clojure.java.jdbc"
               "clojure.data.json" "clojure.math.combinatorics"]]
      (let [dir (temp-lib "{:deps {org.clojure/clojure {:mvn/version \"1.12.5\"}}}" n)]
        (try
          (is (seq (sut/unmapped-third-party-namespaces [["probe" dir]]))
              (str n " is its own artifact, not standard library"))
          (finally (delete-tree! dir))))))

  (testing "genuine standard library namespaces are not"
    ;; Including the ones whose contrib siblings share their first two
    ;; segments — that pairing is why the check is a set and not a prefix.
    (doseq [n ["clojure.data" "clojure.java.io" "clojure.core.protocols"
               "clojure.core.reducers" "clojure.string" "clojure.set"
               "clojure.edn" "clojure.walk" "clojure.test" "clojure.pprint"
               "clojure.stacktrace" "clojure.java.shell" "clojure.zip"]]
      (let [dir (temp-lib "{:deps {org.clojure/clojure {:mvn/version \"1.12.5\"}}}" n)]
        (try
          (is (empty? (sut/unmapped-third-party-namespaces [["probe" dir]]))
              (str n " ships inside the clojure jar"))
          (is (empty? (sut/third-party-gaps [["probe" dir]])) n)
          (finally (delete-tree! dir))))))

  (testing "clojure.tools.* stays checked, as before"
    (let [dir (temp-lib "{:deps {org.clojure/clojure {:mvn/version \"1.12.5\"}}}"
                        "clojure.tools.cli")]
      (try
        (is (= [["probe" "org.clojure/tools.cli"]]
               (map (juxt :lib (comp str :artifact)) (sut/third-party-gaps [["probe" dir]]))))
        (finally (delete-tree! dir))))))
