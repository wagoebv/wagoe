(ns wagoe.tools.deploy-test
  "Release-safety helpers used by the Clojars publish flow: a pre-deploy guard
   that every lib's build.clj version matches the release tag (prevents the
   'version ahead of source' stale-artifact class), and a post-deploy check that
   every artifact actually landed on Clojars."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
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
