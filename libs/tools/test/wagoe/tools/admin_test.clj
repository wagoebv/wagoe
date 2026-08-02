(ns wagoe.tools.admin-test
  "BOU-236: the wagoe-setup skill drives `bb create-admin` non-interactively by
   piping the password on stdin. That works only because System/console returns
   nil when there is no TTY, so the prompt falls back to read-line. Nothing
   stated that as a contract; hardening the prompt to require a TTY would break
   the flagship skill silently, in a file nobody touched. These tests state it."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.tools.admin :as admin]))

(defn- quietly
  "Run `f`, discarding what it prints, and return its value. The prompts write
   to stdout; with-out-str alone would return the prompt text instead of the
   password."
  [f]
  (let [result (atom nil)]
    (with-out-str (reset! result (f)))
    @result))

;; The console is passed explicitly rather than looked up, so these exercise the
;; no-console branch whether or not the test JVM has a terminal attached.

(deftest ^:unit read-password-once-falls-back-to-stdin
  (testing "with no console, the password is read from stdin"
    (is (= "hunter2xyz"
           (with-in-str "hunter2xyz\n"
             (quietly #(#'admin/read-password-once "Password" nil)))))))

(deftest ^:unit read-confirmed-password-accepts-matching-pair
  (testing "two matching entries of sufficient length are accepted"
    (is (= "correct-horse"
           (with-in-str "correct-horse\ncorrect-horse\n"
             (quietly #(#'admin/read-confirmed-password nil)))))))

(deftest ^:unit read-confirmed-password-revalidates
  (testing "a mismatch re-prompts rather than failing"
    (is (= "second-try-ok"
           (with-in-str "typo-one\ntypo-two\nsecond-try-ok\nsecond-try-ok\n"
             (quietly #(#'admin/read-confirmed-password nil))))))

  (testing "a password under 8 characters re-prompts"
    (is (= "long-enough"
           (with-in-str "short\nshort\nlong-enough\nlong-enough\n"
             (quietly #(#'admin/read-confirmed-password nil))))))

  (testing "a blank password re-prompts"
    (is (= "not-blank-now"
           (with-in-str "\n\nnot-blank-now\nnot-blank-now\n"
             (quietly #(#'admin/read-confirmed-password nil)))))))
