(ns wagoe.tools.approval-gate-test
  "scripts/approval-gate.sh against a fake gh, which prints what each --jq
   query would (BOU-1117)."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private fake-gh
  ;; $LABELS is a state file: a DELETE that succeeds drops `approved` from it.
  ;; FAKE_DELETE: ok, 404 or 500.
  "#!/usr/bin/env bash
echo \"$*\" >> \"$GH_LOG\"
case \"$*\" in
  *'-X DELETE'*)
    case \"$FAKE_DELETE\" in
      404) echo 'gh: Label does not exist (HTTP 404)' >&2; exit 1 ;;
      500) echo 'gh: Server Error (HTTP 500)' >&2; exit 1 ;;
    esac
    grep -vx approved \"$LABELS\" > \"$LABELS.new\" || true
    mv \"$LABELS.new\" \"$LABELS\" ;;
  *pulls/7/files*) printf '%b' \"$FAKE_FILES\" ;;
  *pulls/7*) echo \"$FAKE_TOTAL\" ;;
  *issues/7/events*) printf '%b' \"$FAKE_LABELLED\" ;;
  *issues/7/labels*) cat \"$LABELS\" ;;
  *check-suites*) echo \"$FAKE_ARRIVED\" ;;
  *permission*) echo \"$FAKE_ROLE\" ;;
esac
")

(defn- gate
  "Runs the gate on PR 7 changing `files` (\"new\\told\" for a rename)."
  ([files] (gate files {}))
  ([files env]
   (let [dir    (fs/create-temp-dir)
         log    (str (fs/path dir "gh.log"))
         labels (str (fs/path dir "labels"))]
     (spit (str (fs/path dir "gh")) fake-gh)
     (fs/set-posix-file-permissions (fs/path dir "gh") "rwxr-xr-x")
     (spit labels (get env "LABELS" ""))
     (try
       (let [r (process/shell {:out :string :err :string :continue true
                               :extra-env (merge {"PATH" (str dir ":" (System/getenv "PATH"))
                                                  "GH_LOG" log
                                                  "GITHUB_REPOSITORY" "wagoebv/wagoe"
                                                  "PR" "7"
                                                  "HEAD_SHA" "abc"
                                                  "EVENT" "opened"
                                                  "FAKE_FILES" (str (str/join "\\n" files) "\\n")
                                                  "FAKE_TOTAL" (str (count files))
                                                  "FAKE_LABELLED" ""
                                                  "FAKE_ARRIVED" "2026-11-26T10:00:00Z"
                                                  "FAKE_ROLE" "write"
                                                  "FAKE_DELETE" "ok"}
                                                 (dissoc env "LABELS")
                                                 {"LABELS" labels})}
                              "bash" "scripts/approval-gate.sh")]
         (assoc r :calls (str/split-lines (slurp log))))
       (finally (fs/delete-tree dir))))))

(def ^:private approved
  "Labelled after the head arrived."
  {"FAKE_LABELLED" "dev 2026-11-26T11:00:00Z\\n" "LABELS" "approved\n"})

(deftest ^:unit docs-and-tests-pass-without-approval
  (let [r (gate ["CHANGELOG.md" "dev-docs/adr/x.adoc" "docs/modules/a/pages/b.adoc"
                 "test/wagoe/x_test.clj" "libs/user/test/wagoe/user/y_test.clj"
                 "libs/user/README.md" "changes/BOU-1.md"])]
    (is (zero? (:exit r)) (:out r))))

(deftest ^:unit code-agent-docs-startup-files-and-shipped-markdown-need-approval
  (doseq [f ["libs/user/src/wagoe/user/core.clj" "deps.edn" ".github/workflows/ci.yml"
             "AGENTS.md" "libs/user/AGENTS.md" "CLAUDE.md" "CONTRIBUTING.md"
             "dev-docs/agents/testing.md" "ai/.apm/skills/x/SKILL.md"
             "libs/scaffolder/resources/templates/README.md"
             "test/user.clj" "libs/user/test/user.cljc"
             "test/data_readers.clj" "libs/user/test/data_readers.cljc"]]
    (let [r (gate ["README.md" f])]
      (is (= 1 (:exit r)) f)
      (is (str/includes? (:out r) f)))))

(deftest ^:unit a-dot-in-a-pattern-is-literal
  (is (= 1 (:exit (gate ["READMEXmd"])))))

(deftest ^:unit a-rename-counts-under-its-old-name
  (is (= 1 (:exit (gate ["docs/x.md\tlibs/user/src/x.clj"])))))

(deftest ^:unit more-files-than-the-api-lists-need-approval
  (is (= 1 (:exit (gate ["README.md"] {"FAKE_TOTAL" "3001"})))))

(deftest ^:unit the-approved-label-from-a-writer-passes
  (is (zero? (:exit (gate ["src/x.clj"] approved))))
  (is (zero? (:exit (gate ["src/x.clj"] (assoc approved "FAKE_ROLE" "maintain")))))
  (is (= 1 (:exit (gate ["src/x.clj"] (assoc approved "FAKE_ROLE" "triage")))))
  (is (= 1 (:exit (gate ["src/x.clj"] (assoc approved "LABELS" "bug\n"))))))

(deftest ^:unit a-label-older-than-the-head-does-not-pass
  (let [stale (assoc approved "FAKE_LABELLED" "dev 2026-11-26T09:00:00Z\\n")]
    (is (= 1 (:exit (gate ["src/x.clj"] (assoc stale "EVENT" "reopened")))))
    (is (= 1 (:exit (gate ["src/x.clj"] (assoc stale "EVENT" "synchronize")))))))

(deftest ^:unit a-push-removes-the-label
  (let [r (gate ["src/x.clj"] (assoc approved "EVENT" "synchronize"))]
    (is (some #(str/includes? % "-X DELETE repos/wagoebv/wagoe/issues/7/labels/approved") (:calls r)))
    (is (= 1 (:exit r)) "the label is gone"))
  (is (not-any? #(str/includes? % "DELETE") (:calls (gate ["README.md"])))))

(deftest ^:unit removing-the-label-fails-closed
  (is (zero? (:exit (gate ["README.md"] {"EVENT" "synchronize" "FAKE_DELETE" "404"}))))
  (let [r (gate ["README.md"] {"EVENT" "synchronize" "FAKE_DELETE" "500"})]
    (is (= 1 (:exit r)))
    (is (str/includes? (:out r) "HTTP 500"))))
