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
  (let [src (or (re-find #"(?ms)^quickstart_failed_steps\(\) \{\n.*?^\}\n"
                         (slurp "scripts/first-run-smoke.sh"))
                (throw (ex-info "quickstart_failed_steps not found" {})))]
    (process/shell {:out :string :err :string :continue true}
                   "bash" "-euo" "pipefail" "-c" (str src "\nquickstart_failed_steps " log))))

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
