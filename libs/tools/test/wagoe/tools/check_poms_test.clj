(ns wagoe.tools.check-poms-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [wagoe.tools.check-poms :as poms])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- tmp-dir []
  (.toFile (Files/createTempDirectory "check-poms-test" (make-array FileAttribute 0))))

(defn- spit-file [dir name content]
  (let [f (io/file dir name)]
    (spit f content)
    f))

;; ---------------------------------------------------------------------------
;; wagoe-dep->coord — mirrors build-shared/rewrite-wagoe-deps
;; ---------------------------------------------------------------------------

(deftest ^:unit wagoe-dep->coord-maps-to-published-artifact
  (is (= 'org.wagoe/wagoe-user (poms/wagoe-dep->coord 'wagoe/user)))
  (is (= 'org.wagoe/wagoe-ui-style (poms/wagoe-dep->coord 'wagoe/ui-style)))
  (is (= 'org.wagoe/wagoe-shared-ui (poms/wagoe-dep->coord 'wagoe/shared-ui))))

;; ---------------------------------------------------------------------------
;; wagoe-local-deps — only :local/root boundary deps, as {:dir :coord}
;; ---------------------------------------------------------------------------

(deftest ^:unit wagoe-local-deps-selects-only-local-root-wagoe-deps
  (let [dir (tmp-dir)]
    (spit-file dir "deps.edn"
               (pr-str {:deps {'wagoe/core     {:local/root "../core"}
                               'wagoe/platform {:local/root "../platform"}
                               ;; already-published boundary coord — not local/root, ignored
                               'org.wagoe/wagoe-i18n {:mvn/version "1.0.0"}
                               ;; third-party mvn dep — ignored
                               'ring/ring-core    {:mvn/version "1.15.4"}}}))
    (is (= [{:dir "core"     :coord 'org.wagoe/wagoe-core}
            {:dir "platform" :coord 'org.wagoe/wagoe-platform}]
           (poms/wagoe-local-deps dir)))))

(deftest ^:unit wagoe-local-deps-dir-tracks-local-root-target-not-dep-symbol
  (testing ":dir is the :local/root target (authoritative), even if it diverges from the coord suffix"
    (let [dir (tmp-dir)]
      (spit-file dir "deps.edn"
                 (pr-str {:deps {'wagoe/shared-ui {:local/root "../shared-ui"}}}))
      (is (= [{:dir "shared-ui" :coord 'org.wagoe/wagoe-shared-ui}]
             (poms/wagoe-local-deps dir))))))

(deftest ^:unit wagoe-local-deps-empty-when-no-deps-file
  (is (empty? (poms/wagoe-local-deps (tmp-dir)))))

;; ---------------------------------------------------------------------------
;; pom-basis-wired? — regression mode B guard (write-pom basis bound to pom-basis)
;; ---------------------------------------------------------------------------

(def ^:private canonical-build-clj
  "(def basis (build-shared/pom-basis version))
   (defn jar [_] (b/write-pom {:class-dir class-dir :lib lib :basis basis :src-dirs [\"src\"]}))")

(deftest ^:unit pom-basis-wired?-accepts-canonical-pattern
  (is (poms/pom-basis-wired? canonical-build-clj)))

(deftest ^:unit pom-basis-wired?-rejects-raw-create-basis
  (is (not (poms/pom-basis-wired?
            "(def basis (b/create-basis {:project \"deps.edn\"}))
             (defn jar [_] (b/write-pom {:basis basis}))"))))

(deftest ^:unit pom-basis-wired?-rejects-pom-basis-computed-but-not-passed
  (testing "the tightened hole: pom-basis is bound but write-pom is fed a different basis"
    (is (not (poms/pom-basis-wired?
              "(def basis (build-shared/pom-basis version))
               (def raw (b/create-basis {:project \"deps.edn\"}))
               (defn jar [_] (b/write-pom {:basis raw}))")))))

(deftest ^:unit pom-basis-wired?-nil-source
  (is (not (poms/pom-basis-wired? nil))))

;; ---------------------------------------------------------------------------
;; check-lib — regression mode B (publishable build.clj must use pom-basis)
;; ---------------------------------------------------------------------------

(deftest ^:unit check-lib-flags-write-pom-without-pom-basis
  (testing "write-pom fed a raw basis is a violation"
    (let [dir (tmp-dir)]
      (spit-file dir "deps.edn" (pr-str {:deps {'wagoe/core {:local/root "../core"}}}))
      (spit-file dir "build.clj"
                 "(def basis (b/create-basis {:project \"deps.edn\"}))
                  (defn jar [_] (b/write-pom {:basis basis}))")
      (let [r (poms/check-lib ["straggler" dir])]
        (is (:publishable? r))
        (is (not (:uses-pom-basis? r)))
        (is (:violation? r))))))

(deftest ^:unit check-lib-passes-write-pom-with-pom-basis
  (let [dir (tmp-dir)]
    (spit-file dir "deps.edn" (pr-str {:deps {'wagoe/core {:local/root "../core"}}}))
    (spit-file dir "build.clj" canonical-build-clj)
    (let [r (poms/check-lib ["good" dir])]
      (is (:publishable? r))
      (is (:uses-pom-basis? r))
      (is (not (:violation? r)))
      (is (= [{:dir "core" :coord 'org.wagoe/wagoe-core}]
             (:wagoe-deps r))))))

(deftest ^:unit check-lib-exempts-non-publishable-lib
  (testing "a build.clj with no write-pom (e.g. uberjar CLI) is exempt"
    (let [dir (tmp-dir)]
      (spit-file dir "deps.edn" (pr-str {:deps {}}))
      (spit-file dir "build.clj" "(defn uber [_] (b/uber {}))")
      (let [r (poms/check-lib ["cli" dir])]
        (is (not (:publishable? r)))
        (is (not (:violation? r)))))))

;; ---------------------------------------------------------------------------
;; check-build-shared — regression mode A (rewrite must remain in build_shared)
;; ---------------------------------------------------------------------------

(deftest ^:unit check-build-shared-healthy-when-rewrite-present
  (let [dir (tmp-dir)
        f   (spit-file dir "build_shared.clj"
                       "(defn pom-basis [version]
                          ;; rewrites boundary/<x> :local/root -> org.wagoe coords
                          (symbol \"org.wagoe\" ...))")]
    (is (empty? (poms/check-build-shared f)))))

(deftest ^:unit check-build-shared-flags-missing-file
  (is (= [:build-shared-missing]
         (map :type (poms/check-build-shared (io/file (tmp-dir) "nope.clj"))))))

(deftest ^:unit check-build-shared-flags-lost-rewrite
  (testing "build_shared present but no longer rewrites local/root deps"
    (let [dir (tmp-dir)
          f   (spit-file dir "build_shared.clj"
                         "(defn pom-basis [version] (b/create-basis {:project (slurp \"deps.edn\")}))")]
      (is (= [:build-shared-no-rewrite]
             (map :type (poms/check-build-shared f)))))))

;; ---------------------------------------------------------------------------
;; unpublishable-deps — regression mode C (referenced dep must be publishable)
;; ---------------------------------------------------------------------------

(defn- dep-entry [d]
  {:dir (name d) :coord (poms/wagoe-dep->coord d)})

(deftest ^:unit unpublishable-deps-flags-referenced-non-publishable-lib
  (testing "the shared-ui failure: user's POM references wagoe-shared-ui but shared-ui has no build.clj"
    (let [results [{:lib "user" :publishable? true :wagoe-deps [(dep-entry 'wagoe/shared-ui)]}
                   {:lib "shared-ui" :publishable? false :wagoe-deps []}]]
      (is (= [{:lib "user" :dep 'org.wagoe/wagoe-shared-ui}]
             (poms/unpublishable-deps results))))))

(deftest ^:unit unpublishable-deps-passes-when-all-deps-publishable
  (let [results [{:lib "user" :publishable? true :wagoe-deps [(dep-entry 'wagoe/shared-ui)]}
                 {:lib "shared-ui" :publishable? true :wagoe-deps []}]]
    (is (empty? (poms/unpublishable-deps results)))))

(deftest ^:unit unpublishable-deps-ignores-non-publishable-referrer
  (testing "a non-publishable harness (no build.clj) emits no POM, so its deps are exempt"
    (let [results [{:lib "e2e" :publishable? false :wagoe-deps [(dep-entry 'wagoe/user)]}
                   {:lib "user" :publishable? true :wagoe-deps []}]]
      (is (empty? (poms/unpublishable-deps results))))))
