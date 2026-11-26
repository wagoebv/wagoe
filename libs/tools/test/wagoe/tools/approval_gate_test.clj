(ns wagoe.tools.approval-gate-test
  "scripts/approval-gate.sh against a fake gh that serves recorded API JSON
   through the real jq, so the script's --jq filters run too (BOU-1117)."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private fake-gh
  ;; $F/<name>.json is the API's answer; a DELETE that succeeds drops
  ;; `approved` from labels.json. FAKE_DELETE: ok, 404 or 500.
  "#!/usr/bin/env bash
echo \"$*\" >> \"$F/gh.log\"
expr=. path= method=
while [ $# -gt 0 ]; do
  case \"$1\" in
    --jq) expr=$2; shift 2 ;;
    -X) method=$2; shift 2 ;;
    api|--paginate) shift ;;
    *) path=$1; shift ;;
  esac
done
if [ \"$method\" = DELETE ]; then
  case \"$FAKE_DELETE\" in
    404) echo 'gh: Label does not exist (HTTP 404)' >&2; exit 1 ;;
    500) echo 'gh: Server Error (HTTP 500)' >&2; exit 1 ;;
  esac
  jq 'map(select(.name != \"approved\"))' \"$F/labels.json\" > \"$F/l\" && mv \"$F/l\" \"$F/labels.json\"
  exit 0
fi
case \"$path\" in
  */pulls/7/files) f=files ;;
  */pulls/7) f=pull ;;
  */issues/7/events) f=events ;;
  */issues/7/labels) f=labels ;;
  */actions/workflows/ci.yml/runs*) f=runs ;;
  */permission) u=${path%/permission}; f=role-${u##*/} ;;
esac
jq -r \"$expr\" \"$F/$f.json\"
")

(defn- labelled [who at] {:event "labeled" :label {:name "approved"} :actor {:login who} :created_at at})

(defn- run-of [at & {:keys [branch repo] :or {branch "feature" repo "wagoebv/wagoe"}}]
  {:head_branch branch :head_repository {:full_name repo} :created_at at})

(def ^:private approved
  "A developer labelled it after ci.yml's run for the head."
  {:events [(labelled "dev" "2026-11-26T11:00:00Z")]
   :labels [{:name "approved"}]
   :runs   [(run-of "2026-11-26T10:00:00Z")]})

(defn- gate
  "Runs the gate on PR 7 changing `files` (\"new\\told\" for a rename). `api`
   overrides the recorded answers, `env` the environment."
  ([files] (gate files {}))
  ([files api] (gate files api {}))
  ([files {:keys [events labels runs total roles] :or {roles {"dev" "write"}}} env]
   (let [dir   (fs/create-temp-dir)
         write (fn [n v] (spit (str (fs/path dir (str n ".json"))) (json/generate-string v)))]
     (spit (str (fs/path dir "gh")) fake-gh)
     (fs/set-posix-file-permissions (fs/path dir "gh") "rwxr-xr-x")
     (write "files" (for [f files :let [[new old] (str/split f #"\t")]]
                      (cond-> {:filename new} old (assoc :previous_filename old))))
     (write "pull" {:changed_files (or total (count files))})
     (write "events" (or events []))
     (write "labels" (or labels []))
     (write "runs" {:workflow_runs (or runs [])})
     (doseq [[who role] roles] (write (str "role-" who) {:role_name role}))
     (try
       (let [r (process/shell {:out :string :err :string :continue true
                               :extra-env (merge {"PATH" (str dir ":" (System/getenv "PATH"))
                                                  "F" (str dir)
                                                  "GITHUB_REPOSITORY" "wagoebv/wagoe"
                                                  "PR" "7" "HEAD_SHA" "abc"
                                                  "HEAD_REF" "feature" "HEAD_REPO" "wagoebv/wagoe"
                                                  "EVENT" "opened" "BASE_CHANGED" ""
                                                  "FAKE_DELETE" "ok"}
                                                 env)}
                              "bash" "scripts/approval-gate.sh")]
         (assoc r :calls (str/split-lines (slurp (str (fs/path dir "gh.log"))))))
       (finally (fs/delete-tree dir))))))

(def ^:private code ["src/x.clj"])

(deftest ^:unit docs-tests-and-test-data-pass-without-approval
  (let [r (gate ["CHANGELOG.md" "dev-docs/adr/x.adoc" "docs/modules/a/pages/b.adoc"
                 "test/wagoe/x_test.clj" "libs/user/test/wagoe/user/y_test.cljc"
                 "test/fixtures/a.edn" "libs/user/test/snap.json"
                 "libs/user/README.md" "changes/BOU-1.md"])]
    (is (zero? (:exit r)) (:out r))))

(deftest ^:unit code-agent-docs-test-code-and-shipped-markdown-need-approval
  (doseq [f ["libs/user/src/wagoe/user/core.clj" "deps.edn" ".github/workflows/ci.yml"
             "AGENTS.md" "libs/user/AGENTS.md" "CLAUDE.md" "CONTRIBUTING.md"
             "dev-docs/agents/testing.md" "ai/.apm/skills/x/SKILL.md"
             "libs/scaffolder/resources/templates/README.md"
             "test/user.clj" "libs/user/test/data_readers.cljc"
             "test/wagoe/core/utils/redirect.clj" "test/logback-test.xml"]]
    (let [r (gate ["README.md" f])]
      (is (= 1 (:exit r)) f)
      (is (str/includes? (:out r) f)))))

(deftest ^:unit a-dot-in-a-pattern-is-literal
  (is (= 1 (:exit (gate ["READMEXmd"])))))

(deftest ^:unit a-rename-counts-under-its-old-name
  (is (= 1 (:exit (gate ["docs/x.md\tlibs/user/src/x.clj"])))))

(deftest ^:unit more-files-than-the-api-lists-need-approval
  (is (= 1 (:exit (gate ["README.md"] {:total 3001})))))

(deftest ^:unit the-approved-label-from-a-writer-passes
  (is (zero? (:exit (gate code approved))))
  (is (zero? (:exit (gate code (assoc approved :roles {"dev" "maintain"})))))
  (is (= 1 (:exit (gate code (assoc approved :roles {"dev" "triage"})))))
  (is (= 1 (:exit (gate code (assoc approved :labels [{:name "bug"}]))))))

(deftest ^:unit the-latest-label-wins
  (let [events [(labelled "dev" "2026-11-26T11:00:00Z")
                {:event "unlabeled" :label {:name "approved"} :created_at "2026-11-26T11:30:00Z"}
                (labelled "guest" "2026-11-26T12:00:00Z")]
        roles  {"dev" "write" "guest" "read"}]
    (is (= 1 (:exit (gate code (assoc approved :events events :roles roles)))))
    (is (zero? (:exit (gate code (assoc approved :events (reverse events) :roles roles)))))))

(deftest ^:unit the-head-must-have-arrived-before-the-label
  (doseq [[why runs] {"no run"                  []
                      "the head arrived later"  [(run-of "2026-11-26T10:00:00Z") (run-of "2026-11-26T12:00:00Z")]
                      "a run on another branch" [(run-of "2026-11-26T10:00:00Z" :branch "other")]
                      "a run from a fork"       [(run-of "2026-11-26T10:00:00Z" :repo "fork/wagoe")]}]
    (is (= 1 (:exit (gate code (assoc approved :runs runs) {"EVENT" "reopened"}))) why)))

(deftest ^:unit a-retarget-after-the-label-needs-a-new-one
  (let [retarget {:event "base_ref_changed" :actor {:login "dev"} :created_at "2026-11-26T12:00:00Z"}]
    (is (= 1 (:exit (gate code (update approved :events conj retarget)))))))

(deftest ^:unit an-edit-removes-the-label-only-when-the-base-changed
  (is (zero? (:exit (gate code approved {"EVENT" "edited"}))))
  (let [r (gate code approved {"EVENT" "edited" "BASE_CHANGED" "true"})]
    (is (some #(str/includes? % "-X DELETE") (:calls r)))
    (is (= 1 (:exit r)))))

(deftest ^:unit a-push-removes-the-label
  (let [r (gate code approved {"EVENT" "synchronize"})]
    (is (some #(str/includes? % "-X DELETE repos/wagoebv/wagoe/issues/7/labels/approved") (:calls r)))
    (is (= 1 (:exit r)) "the label is gone"))
  (is (not-any? #(str/includes? % "DELETE") (:calls (gate ["README.md"])))))

(deftest ^:unit removing-the-label-fails-closed
  (is (zero? (:exit (gate ["README.md"] {} {"EVENT" "synchronize" "FAKE_DELETE" "404"}))))
  (let [r (gate ["README.md"] {} {"EVENT" "synchronize" "FAKE_DELETE" "500"})]
    (is (= 1 (:exit r)))
    (is (str/includes? (:out r) "HTTP 500"))))
