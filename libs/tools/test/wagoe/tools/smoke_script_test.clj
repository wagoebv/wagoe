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
