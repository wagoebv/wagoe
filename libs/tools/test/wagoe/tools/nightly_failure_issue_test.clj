(ns wagoe.tools.nightly-failure-issue-test
  "scripts/nightly-failure-issue.sh against a fake gh. The fake does not run
   --jq; it prints what the query would, so this tests the script's decisions,
   not GitHub's filtering (BOU-290)."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private fake-gh
  ;; FAKE_OPEN: the open nightly-failure issue number, or empty for none.
  ;; FAKE_LAST: the failed-job set the issue last recorded.
  ;; FAKE_RUN_VIEW=fail: listing the run's jobs fails.
  "#!/usr/bin/env bash
{ printf '%s' \"$*\" | tr '\\n' ' '; echo; } >> \"$GH_LOG\"
case \"$1 $2\" in
  'issue list') printf '%s\\n' \"$FAKE_OPEN\" ;;
  'issue view') printf '%s\\n' \"$FAKE_LAST\" ;;
  'run view')
    [ \"$FAKE_RUN_VIEW\" = fail ] && { echo 'HTTP 403' >&2; exit 1; }
    printf -- '- Smoke — fedora:41\\n- Adversarial — ubuntu:24.04\\n' ;;
esac
")

;; What the script records for the fake's two failed jobs: sorted, ;-joined.
(def ^:private reported-set "- Adversarial — ubuntu:24.04;- Smoke — fedora:41")

(defn- run-script
  ([mode open] (run-script mode open {}))
  ([mode open env]
   (let [dir (fs/create-temp-dir)
         log (str (fs/path dir "gh.log"))]
     (spit (str (fs/path dir "gh")) fake-gh)
     (fs/set-posix-file-permissions (fs/path dir "gh") "rwxr-xr-x")
     (try
       (let [r (process/shell {:out :string :err :string :continue true
                               :extra-env (merge {"PATH" (str dir ":" (System/getenv "PATH"))
                                                  "GH_LOG" log
                                                  "FAKE_OPEN" open
                                                  "FAKE_LAST" ""
                                                  "FAKE_RUN_VIEW" ""
                                                  "GITHUB_REPOSITORY" "wagoebv/wagoe"
                                                  "RUN_ID" "42"
                                                  "RUN_URL" "https://github.com/wagoebv/wagoe/actions/runs/42"}
                                                 env)}
                              "bash" "scripts/nightly-failure-issue.sh" mode)]
         (assoc r :calls (if (fs/exists? log) (str/split-lines (slurp log)) [])))
       (finally (fs/delete-tree dir))))))

(defn- calls-to [r cmd]
  (filter #(str/starts-with? % cmd) (:calls r)))

(deftest ^:unit a-first-failure-opens-one-issue
  (let [r (run-script "fail" "")
        [create] (calls-to r "issue create")]
    (is (zero? (:exit r)) (str (:out r) (:err r)))
    (is (= 1 (count (calls-to r "issue create"))))
    (is (str/includes? create "--label nightly-failure"))
    (is (str/includes? create "Nightly first-run matrix is failing"))
    (is (str/includes? create "https://github.com/wagoebv/wagoe/actions/runs/42"))
    (is (str/includes? create "- Smoke — fedora:41"))
    (is (str/includes? create (str "<!-- failed-jobs: " reported-set " -->"))
        "records the set, so a later night can compare against it")
    (is (empty? (calls-to r "issue comment")))))

(deftest ^:unit a-failure-with-a-new-set-of-jobs-comments-on-the-open-issue
  (let [r (run-script "fail" "17" {"FAKE_LAST" "- Smoke — fedora:41"})
        [comment] (calls-to r "issue comment")]
    (is (zero? (:exit r)) (str (:out r) (:err r)))
    (is (empty? (calls-to r "issue create")) "no second issue")
    (is (str/starts-with? comment "issue comment 17 "))
    (is (str/includes? comment "actions/runs/42"))
    (is (str/includes? comment "- Adversarial — ubuntu:24.04"))))

(deftest ^:unit the-same-failures-as-last-reported-are-not-commented-again
  ;; smoke-released stays red until a release; a comment a night is noise.
  (let [r (run-script "fail" "17" {"FAKE_LAST" reported-set})]
    (is (zero? (:exit r)) (str (:out r) (:err r)))
    (is (empty? (calls-to r "issue comment")))
    (is (empty? (calls-to r "issue create")))))

(deftest ^:unit a-failure-still-reports-when-the-jobs-cannot-be-listed
  (let [r (run-script "fail" "" {"FAKE_RUN_VIEW" "fail"})
        [create] (calls-to r "issue create")]
    (is (zero? (:exit r)) (str (:out r) (:err r)))
    (is (some? create) "the issue is opened anyway")
    (is (str/includes? create "could not list jobs"))
    (is (str/includes? create "actions/runs/42"))))

(deftest ^:unit a-pass-closes-the-open-issue
  (let [r (run-script "pass" "17")
        [close] (calls-to r "issue close")]
    (is (zero? (:exit r)) (str (:out r) (:err r)))
    (is (str/starts-with? close "issue close 17 "))
    (is (str/includes? close "actions/runs/42"))))

(deftest ^:unit a-pass-with-nothing-open-changes-nothing
  (let [r (run-script "pass" "")]
    (is (zero? (:exit r)) (str (:out r) (:err r)))
    (is (= ["issue list"] (map #(str/join " " (take 2 (str/split % #" "))) (:calls r))))))

(deftest ^:unit a-failing-gh-fails-the-step
  ;; A report that silently does nothing is the defect this script exists for.
  (let [dir (fs/create-temp-dir)]
    (try
      (spit (str (fs/path dir "gh")) "#!/usr/bin/env bash\nexit 1\n")
      (fs/set-posix-file-permissions (fs/path dir "gh") "rwxr-xr-x")
      (let [r (process/shell {:out :string :err :string :continue true
                              :extra-env {"PATH" (str dir ":" (System/getenv "PATH"))
                                          "GITHUB_REPOSITORY" "wagoebv/wagoe"
                                          "RUN_ID" "42" "RUN_URL" "u"}}
                             "bash" "scripts/nightly-failure-issue.sh" "fail")]
        (is (not (zero? (:exit r)))))
      (finally (fs/delete-tree dir)))))
