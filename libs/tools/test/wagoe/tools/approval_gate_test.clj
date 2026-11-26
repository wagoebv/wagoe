(ns wagoe.tools.approval-gate-test
  "scripts/approval-gate.sh against a fake gh, which prints what each --jq
   query would (BOU-1117)."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private fake-gh
  "#!/usr/bin/env bash
echo \"$*\" >> \"$GH_LOG\"
case \"$*\" in
  *'-X DELETE'*) ;;
  *pulls/7/files*) printf '%b' \"$FAKE_FILES\" ;;
  *pulls/7*) echo \"$FAKE_TOTAL\" ;;
  *issues/7/events*) printf '%b' \"$FAKE_LABELLER\" ;;
  *issues/7/labels*) printf '%b' \"$FAKE_LABELS\" ;;
  *permission*) echo \"$FAKE_PERM\" ;;
esac
")

(defn- gate
  "Runs the gate on PR 7 changing `files` (\"new\\told\" for a rename)."
  [files & {:as env}]
  (let [dir (fs/create-temp-dir)
        log (str (fs/path dir "gh.log"))]
    (spit (str (fs/path dir "gh")) fake-gh)
    (fs/set-posix-file-permissions (fs/path dir "gh") "rwxr-xr-x")
    (try
      (let [r (process/shell {:out :string :err :string :continue true
                              :extra-env (merge {"PATH" (str dir ":" (System/getenv "PATH"))
                                                 "GH_LOG" log
                                                 "GITHUB_REPOSITORY" "wagoebv/wagoe"
                                                 "PR" "7"
                                                 "EVENT" "opened"
                                                 "FAKE_FILES" (str (str/join "\\n" files) "\\n")
                                                 "FAKE_TOTAL" (str (count files))
                                                 "FAKE_LABELLER" ""
                                                 "FAKE_LABELS" ""
                                                 "FAKE_PERM" "write"}
                                                env)}
                             "bash" "scripts/approval-gate.sh")]
        (assoc r :calls (str/split-lines (slurp log))))
      (finally (fs/delete-tree dir)))))

(def ^:private approved {"FAKE_LABELLER" "dev\\n" "FAKE_LABELS" "approved\\n"})

(deftest ^:unit docs-and-tests-pass-without-approval
  (let [r (gate ["CHANGELOG.md" "dev-docs/adr/x.adoc" "docs/modules/a/pages/b.adoc"
                 "test/wagoe/x_test.clj" "libs/user/test/wagoe/user/y_test.clj"
                 "libs/user/README.md" "changes/BOU-1.md"])]
    (is (zero? (:exit r)) (:out r))))

(deftest ^:unit code-agent-docs-and-shipped-markdown-need-approval
  (doseq [f ["libs/user/src/wagoe/user/core.clj" "deps.edn" ".github/workflows/ci.yml"
             "AGENTS.md" "libs/user/AGENTS.md" "ai/.apm/skills/x/SKILL.md"
             "libs/scaffolder/resources/templates/README.md"]]
    (let [r (gate ["README.md" f])]
      (is (= 1 (:exit r)) f)
      (is (str/includes? (:out r) f)))))

(deftest ^:unit a-rename-counts-under-its-old-name
  (is (= 1 (:exit (gate ["docs/x.md\tlibs/user/src/x.clj"])))))

(deftest ^:unit more-files-than-the-api-lists-need-approval
  (is (= 1 (:exit (gate ["README.md"] "FAKE_TOTAL" "3001")))))

(deftest ^:unit the-approved-label-from-a-writer-passes
  (is (zero? (:exit (apply gate ["src/x.clj"] (mapcat identity approved)))))
  (is (= 1 (:exit (apply gate ["src/x.clj"] (mapcat identity (assoc approved "FAKE_PERM" "read"))))))
  (is (= 1 (:exit (apply gate ["src/x.clj"] (mapcat identity (assoc approved "FAKE_LABELS" "bug\\n")))))))

(deftest ^:unit a-push-removes-the-label
  (is (some #(str/includes? % "-X DELETE repos/wagoebv/wagoe/issues/7/labels/approved")
            (:calls (gate ["README.md"] "EVENT" "synchronize"))))
  (is (not-any? #(str/includes? % "DELETE") (:calls (gate ["README.md"])))))
