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
  "#!/usr/bin/env bash
{ printf '%s' \"$*\" | tr '\\n' ' '; echo; } >> \"$GH_LOG\"
case \"$1 $2\" in
  'issue list') printf '%s\\n' \"$FAKE_OPEN\" ;;
  'run view')   printf -- '- Smoke — fedora:41\\n- Adversarial — ubuntu:24.04\\n' ;;
esac
")

(defn- run-script [mode open]
  (let [dir (fs/create-temp-dir)
        log (str (fs/path dir "gh.log"))]
    (spit (str (fs/path dir "gh")) fake-gh)
    (fs/set-posix-file-permissions (fs/path dir "gh") "rwxr-xr-x")
    (try
      (let [r (process/shell {:out :string :err :string :continue true
                              :extra-env {"PATH" (str dir ":" (System/getenv "PATH"))
                                          "GH_LOG" log
                                          "FAKE_OPEN" open
                                          "GITHUB_REPOSITORY" "wagoebv/wagoe"
                                          "RUN_ID" "42"
                                          "RUN_URL" "https://github.com/wagoebv/wagoe/actions/runs/42"}}
                             "bash" "scripts/nightly-failure-issue.sh" mode)]
        (assoc r :calls (if (fs/exists? log) (str/split-lines (slurp log)) [])))
      (finally (fs/delete-tree dir)))))

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
    (is (empty? (calls-to r "issue comment")))))

(deftest ^:unit a-repeat-failure-comments-on-the-open-issue
  (let [r (run-script "fail" "17")
        [comment] (calls-to r "issue comment")]
    (is (zero? (:exit r)) (str (:out r) (:err r)))
    (is (empty? (calls-to r "issue create")) "no second issue")
    (is (str/starts-with? comment "issue comment 17 "))
    (is (str/includes? comment "actions/runs/42"))
    (is (str/includes? comment "- Adversarial — ubuntu:24.04"))))

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
