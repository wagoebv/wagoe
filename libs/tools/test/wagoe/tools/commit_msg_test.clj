(ns wagoe.tools.commit-msg-test
  "The commit-msg hook requires a Linear ticket in the subject (BOU-1031)."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.test :refer [deftest is testing]]))

(def ^:private hook (str (fs/absolutize ".githooks/commit-msg")))

(defn- exit-for [message]
  (let [f (str (fs/create-temp-file))]
    (try
      (spit f message)
      (:exit (process/shell {:out :string :err :string :continue true} "bash" hook f))
      (finally (fs/delete f)))))

(deftest ^:unit commit-msg-hook-test
  (testing "a subject naming a ticket passes"
    (is (zero? (exit-for "Move the green tag (BOU-1028)\n")))
    (is (zero? (exit-for "# a git comment line\nAddress review findings (BOU-12)\n\nbody\n"))))
  (testing "git's own merges, reverts and fixups pass"
    (doseq [s ["Merge branch 'x'" "Revert \"Move it\"" "fixup! Move it" "squash! Move it"]]
      (is (zero? (exit-for (str s "\n"))) s)))
  (testing "a subject without a ticket is refused, even when the body names one"
    (is (= 1 (exit-for "Move the green tag\n\nFor BOU-1028\n")))
    (is (= 1 (exit-for "\n")))))
