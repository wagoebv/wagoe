(ns wagoe.tools.smoke-script-test
  "fail() from scripts/first-run-smoke.sh, run under the script's own -euo pipefail."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- fail-fn
  "fail(), cut out of the smoke script, reading logs from `dir` instead of /tmp."
  [dir]
  (-> (or (re-find #"(?ms)^fail\(\) \{\n.*?^\}\n" (slurp "scripts/first-run-smoke.sh"))
          (throw (ex-info "fail() not found in scripts/first-run-smoke.sh" {})))
      (str/replace "/tmp/*.log" (str dir "/*.log"))))

(defn- run-fail [dir]
  (process/shell {:out :string :err :string :continue true}
                 "bash" "-euo" "pipefail" "-c" (str (fail-fn dir) "\nfail \"it broke\"")))

(deftest ^:unit fail-reports-with-and-without-a-log
  (let [dir (fs/create-temp-dir)]
    (try
      (testing "before any step has written a log, the message still appears"
        ;; ls finding nothing ended the shell under pipefail, message unprinted.
        (let [r (run-fail dir)]
          (is (= 1 (:exit r)))
          (is (str/includes? (:out r) "SMOKE FAILURE: it broke"))))
      (testing "with a log, its tail comes first"
        (spit (str (fs/path dir "step.log")) "Error: database is locked\n")
        (let [r (run-fail dir)]
          (is (= 1 (:exit r)))
          (is (str/includes? (:out r) "Error: database is locked"))
          (is (str/includes? (:out r) "SMOKE FAILURE: it broke"))))
      (finally (fs/delete-tree dir)))))

(defn- run-failed-steps [log]
  (process/shell {:out :string :err :string :continue true}
                 "bash" "-euo" "pipefail" "-c"
                 (str ". scripts/lib/quickstart-failed-steps.sh\nquickstart_failed_steps " log)))

(deftest ^:unit a-failed-quickstart-step-is-shown-not-just-the-tail
  ;; The scaffold step is early in the log; the last 40 lines began after it,
  ;; so its error never reached the CI log (BOU-545).
  (let [dir (fs/create-temp-dir)
        log (str (fs/path dir "quickstart.log"))
        filler (str/join "\n" (repeat 200 "noise"))]
    (try
      (testing "a long log: only the failed step's section"
        (spit log (str "\u001b[1m[3/8] Validating configuration\u001b[0m\n  Done\n"
                       "\u001b[1m[4/8] Scaffolding sample module\u001b[0m\n"
                       "Could not transfer artifact rewrite-clj:rewrite-clj:jar:1.2.57\n  Failed\n\n"
                       "\u001b[1m[7/8] Running database migrations\u001b[0m\n" filler "\n"
                       "━━━ Quickstart Completed with 1 failed step(s): [4/8] Scaffolding sample module ━━━\n"))
        (let [r (run-failed-steps log)]
          (is (zero? (:exit r)) (:err r))
          (is (str/includes? (:out r) "Could not transfer artifact"))
          (is (not (str/includes? (:out r) "noise")))
          (is (not (str/includes? (:out r) "Validating configuration")))))
      (testing "a short log: all of it"
        (spit log "[4/8] Scaffolding sample module\nboom\nCompleted with 1 failed step(s): [4/8] x\n")
        (is (str/includes? (:out (run-failed-steps log)) "boom")))
      (testing "no failed step: nothing, and a non-zero return"
        (spit log (str "[4/8] Scaffolding sample module\n  Done\n" filler "\nQuickstart Complete\n"))
        (let [r (run-failed-steps log)]
          (is (= 1 (:exit r)))
          (is (str/blank? (:out r)))))
      (finally (fs/delete-tree dir)))))

;; Both scripts run their checks inside `docker run ... bash -c '<body>'`. A fake
;; `docker` hands back that body, so a test can run the part it cares about.

(defn- docker-body [script]
  (let [dir (fs/create-temp-dir)
        body (fs/path dir "body")]
    (try
      (spit (str (fs/path dir "docker")) "#!/usr/bin/env bash\nprintf '%s' \"${@: -1}\" > \"$BODY\"\n")
      (fs/set-posix-file-permissions (fs/path dir "docker") "rwxr-xr-x")
      (process/shell {:out :string :err :string
                      :extra-env {"PATH" (str dir ":" (System/getenv "PATH")) "BODY" (str body)}}
                     "bash" script)
      (slurp (str body))
      (finally (fs/delete-tree dir)))))

(deftest ^:unit the-smoke-container-gets-the-failed-step-check
  (is (str/includes? (docker-body "scripts/first-run-smoke.sh") "quickstart_failed_steps ()")))

(defn- run-skill-quickstart
  "The skill check's quickstart block, from its container body, with a fake
   `bb` that exits 0 and logs `log`."
  [log]
  (let [body (docker-body "scripts/wagoe-setup-skill-verify.sh")
        block (or (re-find #"(?ms)^\s*bb quickstart </dev/null.*?quickstart ok\"\n" body)
                  (throw (ex-info "quickstart block not found" {})))
        dir (fs/create-temp-dir)
        qlog (str (fs/path dir "quickstart.log"))]
    (try
      (spit (str (fs/path dir "bb")) (str "#!/usr/bin/env bash\nprintf '%s' '" log "'\n"))
      (fs/set-posix-file-permissions (fs/path dir "bb") "rwxr-xr-x")
      (process/shell {:out :string :err :string :continue true
                      :extra-env {"PATH" (str dir ":" (System/getenv "PATH"))}}
                     "bash" "-euo" "pipefail" "-c"
                     ;; Whatever the script puts ahead of its own body, then the block.
                     (str (subs body 0 (str/index-of body "apt-get update"))
                          "\n" (str/replace block "/tmp/quickstart.log" qlog)))
      (finally (fs/delete-tree dir)))))

(deftest ^:unit the-skill-check-fails-on-a-failed-quickstart-step
  ;; quickstart exits 0 when its sample module fails (BOU-545), so the exit code
  ;; alone passed the skill check.
  (testing "a failed step fails the check and shows the step"
    (let [r (run-skill-quickstart
             (str "[4/8] Scaffolding sample module\nCould not find artifact rewrite-clj\n"
                  "Quickstart Completed with 1 failed step(s): [4/8] Scaffolding sample module\n"))]
      (is (= 1 (:exit r)) (:out r))
      (is (str/includes? (:out r) "Could not find artifact rewrite-clj"))
      (is (not (str/includes? (:out r) "quickstart ok")))))
  (testing "a clean run passes"
    (let [r (run-skill-quickstart "[4/8] Scaffolding sample module\n  Done\nQuickstart Complete\n")]
      (is (zero? (:exit r)) (str (:out r) (:err r)))
      (is (str/includes? (:out r) "quickstart ok")))))
