(ns wagoe.tools.deploy-test
  "Release-safety helpers used by the Clojars publish flow: a pre-deploy guard
   that every lib's build.clj version matches the release tag (prevents the
   'version ahead of source' stale-artifact class), and a post-deploy check that
   every artifact actually landed on Clojars."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.http-client]
            [babashka.process]
            [wagoe.tools.deploy :as deploy]))

(deftest ^:unit artifact-name-test
  (testing "normal libs get the wagoe- prefix"
    (is (= "wagoe-platform" (deploy/artifact-name "platform")))
    (is (= "wagoe-core" (deploy/artifact-name "core"))))

  (testing "a lib dir already starting with wagoe- is not double-prefixed"
    ;; libs/wagoe-cli publishes com.wagoe/wagoe-cli, NOT wagoe-wagoe-cli —
    ;; artifact-name reads the real coordinate from build.clj rather than
    ;; string-prefixing the dir name.
    (is (= "wagoe-cli" (deploy/artifact-name "wagoe-cli")))))

(deftest ^:unit pom-url-test
  (testing "the Clojars verify URL uses the CURRENT group in path form"
    ;; The group appears here as com/wagoe (path), not com.wagoe (coord). A
    ;; rename that only rewrites the coord form leaves this on the old group and
    ;; every artifact silently reports unpublished (BOU-213 review finding).
    (let [url (deploy/pom-url "core")]
      (is (str/includes? url "clojars.org/repo/com/wagoe/"))
      (is (not (str/includes? url "boundary-app")))
      (is (str/includes? url "wagoe-core"))
      (is (str/ends-with? url ".pom")))))

(deftest ^:unit version-mismatches-test
  (testing "no mismatches when expected equals the suite's current build.clj version"
    (let [current (deploy/read-version "core")]
      (is (string? current) "read-version should find the core lib version")
      (is (empty? (deploy/version-mismatches current))
          "every lib's build.clj should be in lockstep with core")))

  (testing "every lib mismatches a bogus expected version"
    (let [ms (deploy/version-mismatches "9.9.9-nope")]
      (is (= (count deploy/all-libs) (count ms))
          "all libs should be reported as mismatched")
      (is (every? :lib ms))
      (is (every? #(= "9.9.9-nope" (:expected %)) ms)))))

(deftest ^:unit unpublished-libs-test
  (testing "none unpublished when the predicate reports all present"
    (is (empty? (deploy/unpublished-libs ["a" "b"] (constantly true)))))

  (testing "all unpublished when the predicate reports none present"
    (is (= ["a" "b"] (deploy/unpublished-libs ["a" "b"] (constantly false)))))

  (testing "only the unpublished libs are returned, in order"
    (is (= ["b"] (deploy/unpublished-libs ["a" "b" "c"]
                                          (fn [lib] (not= "b" lib)))))))

;; ---------------------------------------------------------------------------
;; Publish order — topological sort (BOU-203)
;; ---------------------------------------------------------------------------

(deftest ^:unit topo-sort-orders-deps-before-dependents
  (testing "a lib always lands after every dep, ignoring deps outside the set"
    (let [deps {"a" ["b" "c"] "b" ["c"] "c" [] "d" ["z"]} ; z not in the set
          out  (deploy/topo-sort ["a" "b" "c" "d"] deps)
          idx  (zipmap out (range))]
      (is (= #{"a" "b" "c" "d"} (set out)))
      (is (< (idx "c") (idx "b")))
      (is (< (idx "b") (idx "a")))))
  (testing "stable: earliest-in-input among ready libs wins"
    (is (= ["x" "y" "z"] (deploy/topo-sort ["x" "y" "z"] {"x" [] "y" [] "z" []}))))
  (testing "throws on a cycle"
    (is (thrown? clojure.lang.ExceptionInfo
                 (deploy/topo-sort ["a" "b"] {"a" ["b"] "b" ["a"]})))))

(deftest ^:unit publish-order-is-a-valid-topological-order
  (testing "publish-order is a permutation of all-libs"
    (is (= (set deploy/all-libs) (set deploy/publish-order)))
    (is (= (count deploy/all-libs) (count deploy/publish-order))))
  (testing "every lib is published after all of its boundary deps (the BOU-203 invariant)"
    (let [idx (zipmap deploy/publish-order (range))]
      (doseq [lib deploy/publish-order
              dep (deploy/wagoe-dep-dirs lib)
              :when (idx dep)] ; only deps that are themselves published
        (is (< (idx dep) (idx lib))
            (str dep " must be published before " lib))))))

(deftest ^:unit cljdoc-request-never-aborts-a-release
  ;; The docs trigger runs after the artifact is already on Clojars, inside the
  ;; doseq that publishes the rest. Anything it throws takes the remaining
  ;; artifacts with it — a cljdoc outage halting a 29-artifact release halfway.
  ;;
  ;; `:throw false` is not enough on its own: it suppresses non-2xx RESPONSES,
  ;; while DNS failures, refused connections and timeouts throw regardless
  ;; because there is no response to inspect.
  (with-redefs [deploy/artifact-name (constantly "wagoe-core")]
    (testing "a transport failure is caught, not propagated"
      (with-redefs [babashka.http-client/post
                    (fn [& _] (throw (java.net.ConnectException. "Connection refused")))]
        (let [out (with-out-str (deploy/request-cljdoc-build! "core" "1.0.0"))]
          (is (str/includes? out "Connection refused")
              "the reason should reach the operator")
          (is (str/includes? out "trigger manually")
              "and so should the recovery step"))))

    (testing "a non-2xx response warns rather than throwing"
      (with-redefs [babashka.http-client/post (constantly {:status 500})]
        (is (str/includes? (with-out-str (deploy/request-cljdoc-build! "core" "1.0.0"))
                           "HTTP 500"))))

    (testing "a successful request is reported as such"
      (with-redefs [babashka.http-client/post (constantly {:status 200})]
        (is (str/includes? (with-out-str (deploy/request-cljdoc-build! "core" "1.0.0"))
                           "cljdoc build requested"))))))

(deftest ^:unit deploy-has-one-registry
  (testing "scripts/deploy.clj holds no registry of its own"
    ;; It used to carry a second all-libs vector, kept in step by a test that
    ;; compared the two SETS — while the behaviour around them drifted in both
    ;; directions unnoticed: the mirror alone requested cljdoc builds, the
    ;; canonical alone had --check-versions and --verify (BOU-250). It is now a
    ;; shim over this namespace, so there is nothing left to keep in sync.
    (let [src (slurp (io/file (System/getProperty "user.dir") "scripts" "deploy.clj"))]
      (is (not (str/includes? src "(def all-libs"))
          "scripts/deploy.clj has grown a registry again — it should delegate")
      (is (str/includes? src "wagoe.tools.deploy")
          "scripts/deploy.clj should delegate to the canonical namespace"))))

(deftest ^:unit a-jar-may-only-carry-its-own-code
  (testing "the pre-rename tree a stale target/classes packages beside the current one"
    (is (= ["boundary/core/validation.clj" "boundary/user/ports.clj"]
           (deploy/foreign-code-entries
            ["META-INF/MANIFEST.MF" "wagoe/core/validation.clj"
             "boundary/user/ports.clj" "boundary/core/validation.clj"]))))

  (testing "resources are not code — these are shipped on purpose"
    ;; ui-style ships public/ and tailwind/, devtools a dashboard/, wagoe-mcp a
    ;; logback.xml. Verified against the published beta-8 jars, which the rule
    ;; must not reject.
    (is (empty? (deploy/foreign-code-entries
                 ["META-INF/maven/com.wagoe/wagoe-ui-style/pom.xml"
                  "public/css/pilot.css" "tailwind/input.css" "logback.xml"
                  "dashboard/index.html" "wagoe/ui_style/core.clj"]))))

  (testing "a compiled class from somewhere else counts too"
    (is (= ["other/Thing.class"]
           (deploy/foreign-code-entries ["wagoe/core.clj" "other/Thing.class"])))))

(deftest ^:unit no-deploy-path-can-publish-a-patch-prerelease
  ;; --check-versions is the workflow's guard, and `bb deploy --all`,
  ;; `--missing` and a named library never reach it: they publish straight from
  ;; build.clj. A Clojars coordinate cannot be withdrawn, so each path has to
  ;; refuse before it builds (BOU-435 review).
  (testing "the guard fires on the shape that caused it"
    (doseq [v ["1.0.1-alpha-1" "1.0.1-beta-3" "2.3.7-rc-1"]]
      (is (thrown? Throwable (deploy/refuse-patch-prerelease! v "test"))
          (str v " was allowed through"))))

  (testing "and passes what may be published"
    (doseq [v ["1.0.0-beta-9" "1.0.0-rc-1" "1.1.0-alpha-1" "1.0.1" "2.0.0"]]
      (is (nil? (deploy/refuse-patch-prerelease! v "test"))
          (str v " was refused"))))

  (testing "deploy-lib! consults it before shelling out to the build"
    ;; Asserted through deploy-lib!, not by reading the source: the first
    ;; version of this guard existed and was reachable from one command only.
    (with-redefs [deploy/read-version (constantly "1.0.1-alpha-1")]
      (let [shelled (atom [])]
        (with-redefs [babashka.process/shell (fn [& args] (swap! shelled conj args) nil)]
          (is (thrown? Throwable (deploy/deploy-lib! "core"))))
        (is (empty? @shelled)
            "it shelled out to the build before refusing the version")))))

(deftest ^:unit a-lib-is-installed-locally-before-it-is-published
  ;; Each lib's build.clj resolves its wagoe deps at the suite version, so
  ;; before this the next lib in the sequence resolved its predecessor from
  ;; Clojars and the run had to sleep for indexing — a guess that, when wrong,
  ;; leaves the suite half published.
  (let [shelled (atom [])]
    (with-redefs [deploy/read-version        (constantly "1.0.0-rc-1")
                  deploy/verify-jar!         (constantly nil)
                  deploy/request-cljdoc-build! (constantly nil)
                  babashka.process/shell     (fn [_opts & args] (swap! shelled conj (vec args)) nil)]
      (deploy/deploy-lib! "core"))
    (let [tasks (map last @shelled)]
      (is (= ["clean" "jar" "install" "deploy"] tasks))
      (is (< (.indexOf (vec tasks) "install") (.indexOf (vec tasks) "deploy"))
          "publishing before installing leaves the next lib resolving over the network"))))

(deftest ^:unit the-sequence-does-not-pause-between-libraries
  ;; The 30s-per-lib sleep is gone with the network dependency that needed it.
  ;; Timed rather than asserted on the absent var: what matters is that a
  ;; 31-artifact release no longer spends a quarter of an hour asleep.
  (with-redefs [deploy/deploy-lib! (constantly nil)]
    (let [start (System/currentTimeMillis)]
      (deploy/deploy-sequence! ["core" "observability" "platform"])
      (is (< (- (System/currentTimeMillis) start) 1000)
          "deploy-sequence! is sleeping between libraries"))))
