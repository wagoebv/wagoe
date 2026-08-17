(ns wagoe.tools.check-versions-test
  "BOU-317: `check:versions` covered 59 code locations and no documentation.

   That is roughly 60% of the version surface, and the missing 40% is the half
   users read. `installation.adoc` sat 43 releases behind — pinning
   `1.0.1-alpha-42`, a line Maven sorts *newer* than every beta — through every
   bump, every release and every green CI run, because nothing was looking.

   The release procedure made it worse rather than better: the documented bump
   was a global `sed` over `.md`/`.adoc` as well as source, so documentation was
   in scope for the mutation and out of scope for the verification. A step that
   can silently do nothing, checked by nothing, is how a page drifts that far."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [wagoe.tools.bump :as bump]
            [wagoe.tools.check-versions :as sut]))

;; =============================================================================
;; Which files the rules apply to
;; =============================================================================

(deftest ^:unit historical-documents-are-out-of-scope
  ;; Same principle as check:doc-counts: a CHANGELOG entry naming alpha-32 was
  ;; true at that release, and rewriting it would be a lie about the past.
  (testing "changelogs, ADRs, delivered talks and dated design docs are excluded"
    (is (not (sut/doc-in-scope? "CHANGELOG.md")))
    (is (not (sut/doc-in-scope? "dev-docs/adr/ADR-015-wagoe-tools.adoc")))
    (is (not (sut/doc-in-scope? "dev-docs/presentations/fc-is-hexagonal-nl.adoc")))
    (is (not (sut/doc-in-scope? "docs/superpowers/specs/2026-04-29-cli-design.md"))))

  (testing "the stability page is excluded because the old versions are its subject"
    ;; It exists to explain that 1.0.0-beta-1 sorts older than 1.0.1-alpha-42.
    ;; Bumping those two strings would delete the explanation.
    (is (not (sut/doc-in-scope? "docs/modules/ROOT/pages/stability.adoc"))))

  (testing "live documentation is in scope"
    (is (sut/doc-in-scope? "README.md"))
    (is (sut/doc-in-scope? "docs/modules/getting-started/pages/installation.adoc"))
    (is (sut/doc-in-scope? "libs/core/README.md")))

  (testing "non-documentation and build output are not"
    (is (not (sut/doc-in-scope? "libs/core/build.clj")))
    (is (not (sut/doc-in-scope? "libs/tools/target/classes/README.md")))))

;; =============================================================================
;; Rule 1 — coordinates users copy
;; =============================================================================

(deftest ^:unit a-stale-coordinate-in-live-documentation-is-found
  ;; The BOU-313 defect, as an assertion.
  (testing "a com.wagoe pin is reported with its line"
    (let [[f :as fs] (sut/doc-version-findings
                      "docs/modules/getting-started/pages/installation.adoc"
                      ";; Validation utilities only\n{:deps {com.wagoe/wagoe-core {:mvn/version \"1.0.1-alpha-42\"}}}\n")]
      (is (= 1 (count fs)))
      (is (= "1.0.1-alpha-42" (:version f)))
      (is (= 2 (:line f)))
      (is (= "com.wagoe pin" (:what f)))))

  (testing "every coordinate on a multi-line block is found, not just the first"
    ;; installation.adoc named the version four times. Reporting one would let a
    ;; partial fix pass.
    (is (= 3 (count (sut/doc-version-findings
                     "README.md"
                     (str "{:deps {com.wagoe/wagoe-platform {:mvn/version \"1.0.0-beta-5\"}\n"
                          "        com.wagoe/wagoe-user     {:mvn/version \"1.0.0-beta-5\"}\n"
                          "        com.wagoe/wagoe-admin    {:mvn/version \"1.0.0-beta-5\"}}}\n")))))))

;; =============================================================================
;; Rule 2 — install commands that pin a git tag
;; =============================================================================

(deftest ^:unit a-tag-pin-counts-only-when-it-is-our-tag
  (testing "the bbin recipe for this repository is in scope"
    (let [[f] (sut/doc-version-findings
               "docs/modules/libraries/pages/cli.adoc"
               (str "bbin install https://github.com/wagoebv/wagoe \\\n"
                    "  --tag v1.0.1-alpha-15 \\\n"
                    "  --as wagoe\n"))]
      (is (= "1.0.1-alpha-15" (:version f)))
      (is (= "git tag pin" (:what f)))))

  (testing "a third party's tag is not our version"
    ;; AGENTS.md and development-commands.adoc install clojure-mcp-light at
    ;; v0.2.2. That is the correct version of someone else's tool, and a rule
    ;; that bumped it would break the documented command.
    (is (empty? (sut/doc-version-findings
                 "AGENTS.md"
                 (str "bbin install https://github.com/bhauman/clojure-mcp-light.git"
                      " --tag v0.2.2 --as clj-nrepl-eval\n")))))

  (testing "the repository is identified within the block, not the line"
    ;; `bbin install <url> \` and `--tag v… \` are different lines of one
    ;; command, so a line-scoped rule sees the tag with no repository next to it.
    (is (seq (sut/doc-version-findings
              "x.adoc"
              (str "Install it:\n\n"
                   "bbin install https://github.com/wagoebv/wagoe \\\n"
                   "  --tag v1.0.0-beta-5\n"))))
    (is (empty? (sut/doc-version-findings
                 "x.adoc"
                 (str "bbin install https://github.com/wagoebv/wagoe --as wagoe\n"
                      "\n"
                      "Unrelated paragraph:\n\n"
                      "bbin install https://github.com/someone/else --tag v3.2.1\n"))))))

;; =============================================================================
;; Both rules, on a line that carries more than one match
;; =============================================================================

(deftest ^:unit a-second-match-on-the-same-line-is-not-lost
  ;; Found reviewing BOU-317 after it merged. The source scanner reads every
  ;; match on a line (`matches-in` uses re-seq); this one read the first
  ;; (`re-find`). A README that writes two coordinates on one line — which is
  ;; ordinary Clojure formatting — was therefore gated on its first coordinate
  ;; only.
  ;;
  ;; The consequence is worse than a plain miss, because `bb bump` rewrites what
  ;; this function discovers: the second coordinate stays stale, and the
  ;; verification afterwards passes, because the check has the identical blind
  ;; spot. Both halves agree with each other while both are wrong — which is the
  ;; failure mode this whole epic is about.
  (testing "two coordinates on one line are two findings"
    (let [fs* (sut/doc-version-findings
               "README.md"
               (str "{:deps {com.wagoe/wagoe-core {:mvn/version \"1.0.1-alpha-42\"} "
                    "com.wagoe/wagoe-user {:mvn/version \"1.0.1-alpha-42\"}}}\n"))]
      (is (= 2 (count fs*)))
      (is (= ["wagoe-core" "wagoe-user"]
             (map #(second (re-find #"com\.wagoe/([a-z0-9-]+)" (:excerpt %))) fs*)))))

  (testing "and the bump rewrites both, not just the first"
    (let [line     (str "{:deps {com.wagoe/wagoe-core {:mvn/version \"1.0.1-alpha-42\"} "
                        "com.wagoe/wagoe-user {:mvn/version \"1.0.1-alpha-42\"}}}\n")
          findings (sut/doc-version-findings "README.md" line)]
      (is (not (str/includes? (bump/rewrite line findings "1.0.0-beta-5")
                              "1.0.1-alpha-42"))))))

;; =============================================================================
;; Rule 2, continued — whose tag is it
;; =============================================================================

(deftest ^:unit a-tag-belongs-to-the-install-command-above-it
  ;; Also found reviewing BOU-317 after it merged, and the more dangerous of the
  ;; two. `--tag` was scoped to the blank-line block, so one install command for
  ;; this repository made *every* --tag in that block ours. AGENTS.md already
  ;; writes two `bbin install` lines with no blank line between them; add a
  ;; wagoe install to such a block and `bb bump` would rewrite someone else's
  ;; tool to our version, breaking the documented command.
  ;;
  ;; That is precisely the collateral damage BOU-316 removed from the sed, so it
  ;; must not come back through the scanner.
  (testing "a third party's tag beneath ours is not ours"
    (let [fs* (sut/doc-version-findings
               "AGENTS.md"
               (str "bbin install https://github.com/wagoebv/wagoe --tag v1.0.0-beta-5 --as wagoe\n"
                    "bbin install https://github.com/bhauman/clojure-mcp-light.git"
                    " --tag v0.2.2 --as clj-nrepl-eval\n"))]
      (is (= ["1.0.0-beta-5"] (map :version fs*))
          "v0.2.2 belongs to clojure-mcp-light, and rewriting it breaks the command")))

  (testing "our tag still counts when the install spans lines"
    ;; The reason the rule was block-scoped in the first place: the URL and the
    ;; --tag are different lines of one command.
    (is (= ["1.0.0-beta-5"]
           (map :version (sut/doc-version-findings
                          "cli.adoc"
                          (str "bbin install https://github.com/wagoebv/wagoe \\\n"
                               "  --tag v1.0.0-beta-5 \\\n"
                               "  --as wagoe\n"))))))

  (testing "a tag with no install command above it is nobody's"
    (is (empty? (sut/doc-version-findings "x.adoc" "  --tag v9.9.9\n")))))

;; =============================================================================
;; Rule 3 — prose that pins a version
;; =============================================================================

(deftest ^:unit prose-that-names-a-release-is-found
  ;; `libs/realtime/README.md` carried "NEW in v1.0.1-alpha-26" for 16 releases,
  ;; describing a feature nobody would call new.
  (testing "a novelty marker is reported"
    (let [[f] (sut/doc-version-findings
               "libs/realtime/README.md"
               "**NEW in v1.0.1-alpha-26**: Connections can subscribe to topics.\n")]
      (is (= "1.0.1-alpha-26" (:version f)))
      (is (= "release-pinned prose" (:what f)))))

  (testing "a version in ordinary prose is left alone"
    ;; Only the novelty phrasings are claimed. Matching every version-shaped
    ;; string in prose would fire on the sentence explaining why versions drift.
    (is (empty? (sut/doc-version-findings
                 "x.adoc"
                 "The 1.0.1-alpha line is discontinued and receives no fixes.\n")))))

;; =============================================================================
;; Consensus — documentation is checked, it does not vote
;; =============================================================================

(deftest ^:unit documentation-cannot-outvote-the-source
  ;; The majority rule works when a bump touches most locations and misses a
  ;; few. It inverts when a *category* is missed: had all 30 doc locations
  ;; stayed on alpha-42 while a handful of libs were bumped, majority-wins would
  ;; have named the correctly-bumped files as the offenders. The version the
  ;; code declares is the version, so `expected` is passed in.
  (let [src (fn [v file what] {:file file :version v :what what})]
    (testing "an explicit expected version overrides the majority"
      (let [{:keys [consensus offenders]}
            (sut/disagreements [(src "1.0.0-beta-5" "libs/core/build.clj" "build.clj")
                                (src "1.0.1-alpha-42" "README.md" "com.wagoe pin")
                                (src "1.0.1-alpha-42" "installation.adoc" "com.wagoe pin")
                                (src "1.0.1-alpha-42" "index.adoc" "com.wagoe pin")]
                               "1.0.0-beta-5")]
        (is (= "1.0.0-beta-5" consensus))
        (is (= ["README.md" "index.adoc" "installation.adoc"]
               (map :file offenders))
            "the three stale docs, not the one correct build.clj")))

    (testing "without an expected version the majority still decides"
      ;; The existing arity, unchanged — `bb check:versions` with no argument
      ;; has no independent source of truth to consult.
      (is (= "1.0.0-beta-5"
             (:consensus (sut/disagreements
                          [(src "1.0.0-beta-5" "libs/core/build.clj" "build.clj")
                           (src "1.0.0-beta-5" "libs/user/build.clj" "build.clj")
                           (src "1.0.1-alpha-32" "bb.edn" "com.wagoe pin")])))))

    (testing "agreement with the expected version is not a finding"
      (is (nil? (sut/disagreements [(src "1.0.0-beta-5" "libs/core/build.clj" "build.clj")
                                    (src "1.0.0-beta-5" "README.md" "com.wagoe pin")]
                                   "1.0.0-beta-5"))))))

;; =============================================================================
;; The repository, right now
;; =============================================================================

(deftest ^:unit the-gate-looks-at-real-documentation
  ;; The BOU-250 shape: a live check scanning nothing reports clean forever.
  (let [docs (sut/tracked-docs)]
    (testing "documentation is discovered"
      (is (< 20 (count docs)))
      (is (some #(= "README.md" %) docs))
      (is (not-any? #(= "CHANGELOG.md" %) docs)))

    (testing "and it finds the coordinates users copy"
      (let [sources (sut/doc-sources)]
        (is (< 10 (count sources))
            "expected the documented com.wagoe pins, so a passing run means something")))))

(deftest ^:unit the-repository-currently-agrees
  (let [code (sut/version-sources)
        all  (concat code (sut/doc-sources))]
    (is (seq code))
    (is (nil? (sut/disagreements all (:version (first code))))
        (str "locations naming a different version: "
             (pr-str (map (juxt :file :version)
                          (:offenders (sut/disagreements all (:version (first code))))))))))
