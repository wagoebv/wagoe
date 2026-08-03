(ns wagoe.user.shell.cli-entry-test
  "BOU-266: `bb create-admin` could not create a user. The :user-cli alias ran
   the CLI through `-e` reading *command-line-args*, and clojure.main takes the
   first non-option argument as a script path — so `create` was dropped and the
   CLI rejected `--email` as an unknown global option.

   These tests cover the entrypoint contract that replaced it: -main forwards
   its arguments verbatim and exits with the status run-cli! returned. The
   alias shape itself is asserted in libs/wagoe-cli's new_test, because that is
   where the defect actually lived — run-cli! was always correct when handed a
   well-formed vector, which is exactly why calling it directly in a test
   missed this."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.user.shell.cli-entry :as cli-entry]))

(defn- run-main
  "Call -main with run-cli! stubbed. Returns {:args ... :exit ...}."
  [status & args]
  (let [seen (atom nil)
        exit (atom nil)]
    (with-redefs [cli-entry/run-cli! (fn [a] (reset! seen a) status)]
      (binding [cli-entry/*exit!* #(reset! exit %)]
        (apply cli-entry/-main args)))
    {:args @seen :exit @exit}))

(deftest ^:unit main-forwards-arguments-verbatim
  (testing "the verb survives — this is the bug"
    (is (= ["create" "--email" "a@b.com" "--name" "Admin" "--role" "admin"]
           (:args (run-main 0 "create" "--email" "a@b.com" "--name" "Admin"
                            "--role" "admin")))
        "-main must pass every argument through, verb first"))

  (testing "arguments arrive as a vector, which run-cli! destructures"
    (is (vector? (:args (run-main 0 "list")))))

  (testing "no arguments is an empty vector, not nil"
    (is (= [] (:args (run-main 0))))))

(deftest ^:unit main-exits-with-the-cli-status
  (testing "success propagates"
    (is (= 0 (:exit (run-main 0 "list")))))

  (testing "failure propagates, so a script can tell"
    (is (= 1 (:exit (run-main 1 "create"))))))
