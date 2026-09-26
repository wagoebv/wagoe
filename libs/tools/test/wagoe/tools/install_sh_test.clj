(ns wagoe.tools.install-sh-test
  "install_sdkman from scripts/install.sh, run against a fake curl.

   A failed first attempt left a half-written ~/.sdkman, and sdkman's installer
   refuses to run over an existing one, so the retries could never succeed
   (BOU-525). The fake curl reproduces both halves of that."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- install-sdkman-fn
  "The install_sdkman function's source, cut out of scripts/install.sh so the
   test runs the real code rather than a copy."
  []
  (let [src (slurp "scripts/install.sh")]
    (or (re-find #"(?ms)^install_sdkman\(\) \{\n.*?^\}\n" src)
        (throw (ex-info "install_sdkman not found in scripts/install.sh" {})))))

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
