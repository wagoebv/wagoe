(ns wagoe.tools.bump-test
  "BOU-316: the release bump was a blind global sed, copied from the README.

   Three things were wrong with it, and they compounded:

     - `OLD` and `NEW` were set to the *same string* in the documented snippet,
       so a copy-paste run rewrote nothing and reported success — and the
       verification step was `grep -r \"$OLD\"`, which then found nothing and
       agreed;
     - `sed -i ''` is macOS-only, so the documented command fails on the CI
       image and on any Linux maintainer's machine;
     - it rewrote *every* occurrence of the version string, so a dependency pin
       or test fixture that happened to equal the current version was silently
       rewritten too.

   `check:versions` already discovers the locations by pattern. The mutation
   uses that same discovery rather than a second list, because a second list is
   what the gate exists to catch."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [wagoe.tools.bump :as sut]))

;; =============================================================================
;; What may be written
;; =============================================================================

(deftest ^:unit a-target-version-must-look-like-a-suite-version
  ;; `bb bump v1.0.0-beta-6` writing a leading v into 96 locations is a worse
  ;; outcome than refusing, because check:versions would then agree with itself.
  (testing "accepted forms"
    (is (sut/valid-version? "1.0.0-beta-6"))
    (is (sut/valid-version? "1.0.1-alpha-43"))
    (is (sut/valid-version? "2.0.0")))

  (testing "refused forms"
    (is (not (sut/valid-version? "v1.0.0-beta-6")) "a leading v is a tag, not a version")
    (is (not (sut/valid-version? "1.0")))
    (is (not (sut/valid-version? "1.0.0-beta")) "the suite scheme numbers its betas")
    (is (not (sut/valid-version? "")))
    (is (not (sut/valid-version? nil)))))

;; =============================================================================
;; The mutation
;; =============================================================================

(deftest ^:unit only-discovered-locations-are-rewritten
  ;; The defect that makes a blind sed dangerous rather than merely clumsy.
  (testing "a coordinate is rewritten and an unrelated match is not"
    (let [content  (str "{:deps {com.wagoe/wagoe-core {:mvn/version \"1.0.0-beta-5\"}\n"
                        "        com.h2database/h2 {:mvn/version \"1.0.0-beta-5\"}}}\n")
          findings [{:line 1 :excerpt "com.wagoe/wagoe-core {:mvn/version \"1.0.0-beta-5\""
                     :version "1.0.0-beta-5"}]]
      (is (= (str "{:deps {com.wagoe/wagoe-core {:mvn/version \"1.0.0-beta-6\"}\n"
                  "        com.h2database/h2 {:mvn/version \"1.0.0-beta-5\"}}}\n")
             (sut/rewrite content findings "1.0.0-beta-6"))
          "the third-party pin keeps its own version, which happens to match")))

  (testing "every discovered location on its own line is rewritten"
    (let [content  (str "com.wagoe/wagoe-platform {:mvn/version \"1.0.0-beta-5\"}\n"
                        "com.wagoe/wagoe-user     {:mvn/version \"1.0.0-beta-5\"}\n")
          findings [{:line 1 :excerpt "com.wagoe/wagoe-platform {:mvn/version \"1.0.0-beta-5\""
                     :version "1.0.0-beta-5"}
                    {:line 2 :excerpt "com.wagoe/wagoe-user     {:mvn/version \"1.0.0-beta-5\""
                     :version "1.0.0-beta-5"}]]
      (is (= (str "com.wagoe/wagoe-platform {:mvn/version \"1.0.0-beta-6\"}\n"
                  "com.wagoe/wagoe-user     {:mvn/version \"1.0.0-beta-6\"}\n")
             (sut/rewrite content findings "1.0.0-beta-6")))))

  (testing "a location already at the target is left alone"
    ;; Re-running a bump must be a no-op, not a second edit.
    (let [content  "com.wagoe/wagoe-core {:mvn/version \"1.0.0-beta-6\"}\n"
          findings [{:line 1 :excerpt "com.wagoe/wagoe-core {:mvn/version \"1.0.0-beta-6\""
                     :version "1.0.0-beta-6"}]]
      (is (= content (sut/rewrite content findings "1.0.0-beta-6")))))

  (testing "trailing-newline handling does not eat or add a line"
    ;; A rewrite that drops the final newline shows up as a whole-file diff and
    ;; buries the two lines that actually changed.
    (let [content "a\ncom.wagoe/wagoe-core {:mvn/version \"1.0.0-beta-5\"}\nb\n"]
      (is (str/ends-with? (sut/rewrite content
                                       [{:line 2
                                         :excerpt "com.wagoe/wagoe-core {:mvn/version \"1.0.0-beta-5\""
                                         :version "1.0.0-beta-5"}]
                                       "1.0.0-beta-6")
                          "\nb\n")))))

;; =============================================================================
;; Planning across the repository
;; =============================================================================

(deftest ^:unit the-plan-is-built-from-the-gates-own-discovery
  ;; Item 1 of the ticket: one source of truth for checking and mutating. If
  ;; these two ever diverge, the gate is checking locations the bump does not
  ;; write — which is how bb.edn pins sat two releases behind deps.edn.
  (let [plan (sut/plan "1.0.0-beta-6")]

    (testing "it covers real files, not an empty map"
      (is (< 20 (count plan))
          "expected the repository's version locations, so a run means something"))

    (testing "it names both source and documentation"
      (is (some #(str/ends-with? % "build.clj") (keys plan)))
      (is (some #(str/ends-with? % ".adoc") (keys plan))
          "documentation is in scope for the mutation because BOU-317 put it in
           scope for the verification"))

    (testing "it never plans to touch a historical document"
      (is (not-any? #(or (= "CHANGELOG.md" %)
                         (str/starts-with? % "dev-docs/adr/")
                         (str/starts-with? % "docs/superpowers/"))
                    (keys plan))))

    (testing "planning is read-only"
      ;; Guards the obvious catastrophe: a --dry-run that is not dry.
      (is (= plan (sut/plan "1.0.0-beta-6"))))))

(deftest ^:unit bumping-to-the-current-version-plans-no-edits
  (let [current (:version (first (sut/current-sources)))]
    (testing "every discovered location already names it, so nothing changes"
      (is (empty? (sut/changed-files (sut/plan current)))
          "a re-run of the same bump must be a no-op"))))
