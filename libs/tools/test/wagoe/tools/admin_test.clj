(ns wagoe.tools.admin-test
  "BOU-236: the wagoe-setup skill drives `bb create-admin` non-interactively by
   piping the password on stdin. That works only because System/console returns
   nil when there is no TTY, so the prompt falls back to read-line. Nothing
   stated that as a contract; hardening the prompt to require a TTY would break
   the flagship skill silently, in a file nobody touched. These tests state it.

   BOU-565: one piped password looped forever at 85% CPU. Every reading test
   runs under `bounded`, so a regression fails in seconds instead of hanging."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.tools.admin :as admin]))

(defn- quietly
  "Run `f`, discarding what it prints, and return its value. The prompts write
   to stdout; with-out-str alone would return the prompt text instead of the
   password."
  [f]
  (let [result (atom nil)]
    (with-out-str (reset! result (f)))
    @result))

(defn- bounded
  "Run `f` on stdin `input`. Returns its value, `{:thrown <ex-data+message>}`,
   or ::timed-out after 5 seconds."
  [input f]
  (let [fut (future
              (with-in-str input
                (try
                  (quietly f)
                  (catch clojure.lang.ExceptionInfo e
                    {:thrown (assoc (ex-data e) :message (ex-message e))}))))
        v   (deref fut 5000 ::timed-out)]
    (when (= ::timed-out v) (future-cancel fut))
    v))

(defn- scripted
  "A console stand-in: returns the given answers in order, then nil (EOF)."
  [& answers]
  (let [q (atom answers)]
    (fn [_label]
      (let [[a & more] @q]
        (reset! q more)
        a))))

(deftest ^:unit read-password-once-falls-back-to-stdin
  (testing "with no console, the password is read from stdin"
    (is (= "hunter2xyz"
           (with-in-str "hunter2xyz\n"
             (quietly #(#'admin/read-password-once "Password" nil)))))))

(deftest ^:unit piped-password-is-read-once
  (testing "one piped line is the password; there is nothing to confirm against"
    (is (= "Invoice2026!"
           (bounded "Invoice2026!\n" #(#'admin/read-admin-password nil)))))

  (testing "a second piped line is ignored, so scripts that pipe it twice still work"
    (is (= "correct-horse"
           (bounded "correct-horse\ncorrect-horse\n" #(#'admin/read-admin-password nil))))))

(deftest ^:unit piped-eof-fails-instead-of-looping
  (testing "empty stdin is an error, not an endless re-prompt"
    (let [r (bounded "" #(#'admin/read-admin-password nil))]
      (is (not= ::timed-out r))
      (is (= :validation-error (get-in r [:thrown :type])))
      (is (str/includes? (str (get-in r [:thrown :message])) "stdin")))))

(deftest ^:unit piped-invalid-password-fails-at-once
  (testing "a short piped password fails; there is no one to re-prompt"
    (let [r (bounded "short\n" #(#'admin/read-admin-password nil))]
      (is (= :validation-error (get-in r [:thrown :type])))
      (is (str/includes? (str (get-in r [:thrown :message])) "8 characters")))))

(deftest ^:unit interactive-password-revalidates
  (testing "two matching entries are accepted"
    (is (= "correct-horse"
           (bounded "" #(#'admin/read-admin-password (scripted "correct-horse" "correct-horse"))))))

  (testing "a mismatch re-prompts"
    (is (= "second-try-ok"
           (bounded "" #(#'admin/read-admin-password
                         (scripted "typo-one" "typo-two" "second-try-ok" "second-try-ok"))))))

  (testing "a password under 8 characters re-prompts"
    (is (= "long-enough"
           (bounded "" #(#'admin/read-admin-password
                         (scripted "short" "short" "long-enough" "long-enough"))))))

  (testing "a blank password re-prompts"
    (is (= "not-blank-now"
           (bounded "" #(#'admin/read-admin-password
                         (scripted "" "" "not-blank-now" "not-blank-now")))))))

(deftest ^:unit interactive-retries-are-bounded
  (testing "three bad attempts end with an error"
    (let [r (bounded "" #(#'admin/read-admin-password
                          (apply scripted (repeat 100 ""))))]
      (is (not= ::timed-out r))
      (is (= :validation-error (get-in r [:thrown :type])))))

  (testing "EOF at the console (Ctrl-D) ends with an error"
    (let [r (bounded "" #(#'admin/read-admin-password (scripted)))]
      (is (= :validation-error (get-in r [:thrown :type]))))))

(deftest ^:unit prompts-end-on-eof
  (testing "email and name prompts fail on EOF instead of re-prompting forever"
    (let [r (bounded "" #(#'admin/ask nil "Admin email address" (constantly nil)))]
      (is (not= ::timed-out r))
      (is (= :validation-error (get-in r [:thrown :type])))))

  (testing "an invalid --email flag fails instead of re-checking the same value forever"
    (let [r (bounded "" #(#'admin/ask "not-an-email" "Admin email address"
                                      (fn [s] (when-not (str/includes? s "@") "Not a valid email address."))))]
      (is (not= ::timed-out r))
      (is (= "Not a valid email address." (get-in r [:thrown :message]))))))

(deftest ^:unit user-cli-logs-to-stderr-only
  (testing "the user CLI's schema logging stays off stdout"
    (let [cmd (#'admin/user-cli-command "a@b.cd" "Admin" "/tmp/x.xml")]
      (is (= ["clojure" "-J-Dlogback.configurationFile=/tmp/x.xml" "-M:user-cli" "create"]
             (take 4 cmd)))
      (is (str/includes? @#'admin/quiet-logback "<target>System.err</target>"))
      (is (str/includes? @#'admin/quiet-logback "<root level=\"WARN\">")))))
