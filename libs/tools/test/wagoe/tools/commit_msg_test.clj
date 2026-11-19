(ns wagoe.tools.commit-msg-test
  "The commit-msg hook requires a Linear ticket in the subject (BOU-1031)."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
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
    (is (zero? (exit-for "\n# a git comment line\nAddress review findings (BOU-12)\n\nbody\n"))))
  (testing "a `git commit -v` message with a long diff passes"
    (is (zero? (exit-for (str "Add it (BOU-2)\n# ------------------------ >8 ------------------------\n"
                              (str/join "\n" (repeat 30000 "+ a line of the diff")))))))
  (testing "git's own merges, reverts and fixups pass"
    (doseq [s ["Merge branch 'x'" "Merge pull request #12 from a/b" "Revert \"Move it\""
               "fixup! Move it" "squash! Move it" "amend! Move it" "Release 1.3.0"]]
      (is (zero? (exit-for (str s "\n"))) s)))
  (testing "a subject without a ticket is refused, even when the body names one"
    (is (= 1 (exit-for "Move the green tag\n\nFor BOU-1028\n")))
    (is (= 1 (exit-for "Merge the two config loaders\n")))
    (is (= 1 (exit-for "Revert the cache tweak\n")))
    (is (= 1 (exit-for "Release 1 thing\n")))
    (is (= 1 (exit-for "\n")))))
