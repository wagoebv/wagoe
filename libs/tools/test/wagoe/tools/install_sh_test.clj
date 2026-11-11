(ns wagoe.tools.install-sh-test
  "install_sdkman from scripts/install.sh, run against a fake curl.

   A failed first attempt left a half-written ~/.sdkman, and sdkman's installer
   refuses to run over an existing one, so the retries could never succeed
   (BOU-525). The fake curl reproduces both halves of that."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- install-sh-fn
  "Function `fname`'s source, cut out of scripts/install.sh so the test runs
   the real code rather than a copy."
  [fname]
  (let [src (slurp "scripts/install.sh")]
    (or (re-find (re-pattern (str "(?ms)^" fname "\\(\\) \\{\\n.*?^\\}\\n")) src)
        (throw (ex-info (str fname " not found in scripts/install.sh") {})))))

(defn- install-sdkman-fn [] (install-sh-fn "install_sdkman"))

(def ^:private fake-curl
  ;; Prints a script for the piped `bash`, the way get.sdkman.io does. The
  ;; first call writes part of ~/.sdkman and fails. Later calls refuse an
  ;; existing ~/.sdkman, as sdkman does, and otherwise install.
  "#!/usr/bin/env bash
n=$(( $(cat \"$HOME/.curl-calls\" 2>/dev/null || echo 0) + 1 )); echo $n > \"$HOME/.curl-calls\"
if [ \"$n\" -eq 1 ] && [ \"$FAKE_FIRST\" = fail ]; then
  echo 'mkdir -p \"$HOME/.sdkman/tmp\"; exit 1'
elif [ \"$FAKE_ALWAYS\" = fail ]; then
  echo 'exit 1'
else
  echo 'if [ -d \"$HOME/.sdkman\" ]; then echo \"You already have SDKMAN installed.\"; exit 1; fi
mkdir -p \"$HOME/.sdkman/bin\"; echo \"# init\" > \"$HOME/.sdkman/bin/sdkman-init.sh\"'
fi
")

(defn- run-install [home env]
  (let [bin (fs/create-dirs (fs/path home "fakebin"))]
    (spit (str (fs/path bin "curl")) fake-curl)
    (fs/set-posix-file-permissions (fs/path bin "curl") "rwxr-xr-x")
    (process/shell {:out :string :err :string :continue true
                    :extra-env (merge {"HOME" (str home)
                                       "PATH" (str bin ":" (System/getenv "PATH"))}
                                      env)}
                   "bash" "-c"
                   (str "info() { echo \"$*\"; }\nsleep() { :; }\n"
                        (install-sdkman-fn)
                        "install_sdkman"))))

(deftest ^:unit a-failed-first-attempt-does-not-block-the-retries
  (let [home (fs/create-temp-dir)]
    (try
      (let [r (run-install home {"FAKE_FIRST" "fail"})]
        (is (zero? (:exit r)) (str (:out r) (:err r)))
        (is (fs/exists? (fs/path home ".sdkman/bin/sdkman-init.sh")))
        (is (= "2" (str/trim (slurp (str (fs/path home ".curl-calls")))))
            "succeeds on the second attempt"))
      (finally (fs/delete-tree home)))))

(deftest ^:unit a-users-own-sdkman-directory-is-never-removed
  (let [home (fs/create-temp-dir)]
    (try
      (fs/create-dirs (fs/path home ".sdkman" "candidates"))
      (spit (str (fs/path home ".sdkman" "candidates" "mine")) "keep")
      (let [r (run-install home {"FAKE_ALWAYS" "fail"})]
        (is (not (zero? (:exit r))))
        (is (fs/exists? (fs/path home ".sdkman" "candidates" "mine"))
            "a directory this run did not create is left alone"))
      (finally (fs/delete-tree home)))))

(defn- verify
  "verify_sha256 run as the installer runs it: strict mode, with its fail."
  [file expected]
  (let [fail (re-find #"(?m)^fail\(\) .*\n" (slurp "scripts/install.sh"))]
    (process/shell {:out :string :err :string :continue true}
                   "bash" "-c" (str "set -euo pipefail\nRED= RESET=\n" fail (install-sh-fn "verify_sha256")
                                    "verify_sha256 \"$1\" \"$2\"")
                   "_" (str file) expected)))

(deftest ^:unit ^:security a-download-that-does-not-match-its-pin-is-removed
  ;; BOU-938: a pinned installer that does not match its hash is never run.
  (let [file (fs/create-temp-file)
        ;; sha256 of "hello\n"
        good "5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03"]
    (spit (str file) "hello\n")
    (is (zero? (:exit (verify file good))))
    (is (fs/exists? file))
    (is (= 1 (:exit (verify file (apply str (repeat 64 "0"))))))
    (is (not (fs/exists? file)) "a mismatch removes the file")))
