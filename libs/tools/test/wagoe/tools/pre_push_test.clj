(ns wagoe.tools.pre-push-test
  "The pre-push hook runs `bb check` on a push to main, and only then (BOU-1025)."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.test :refer [deftest is testing]]))

(def ^:private hook (str (fs/absolutize ".githooks/pre-push")))

(defn- run-hook
  "Run the hook in a fresh repo whose `bb` exits 3, feeding `stdin`."
  [stdin dirty?]
  (let [repo (str (fs/create-temp-dir))
        bin  (str (fs/create-dirs (fs/path repo ".bin")))
        sh   #(apply process/shell {:dir repo :out :string :err :string} %&)]
    (try
      (spit (str bin "/bb") "#!/bin/sh\nexit 3\n")
      (fs/set-posix-file-permissions (str bin "/bb") "rwxr-xr-x")
      (sh "git" "init" "-q")
      (spit (str repo "/.gitignore") ".bin\n")
      (sh "git" "add" ".gitignore")
      (sh "git" "-c" "user.email=t@t" "-c" "user.name=t" "commit" "-qm" "init")
      (when dirty? (spit (str repo "/x.txt") "x"))
      (:exit (process/shell {:dir       repo
                             :in        stdin
                             :out       :string
                             :err       :string
                             :continue  true
                             :extra-env {"PATH" (str bin ":" (System/getenv "PATH"))}}
                            "bash" hook))
      (finally (fs/delete-tree repo)))))

(def ^:private sha "1111111111111111111111111111111111111111")

(deftest ^:unit pre-push-hook-test
  (testing "a push to main runs bb check and fails with it"
    (is (= 3 (run-hook (str "refs/heads/main " sha " refs/heads/main " sha "\n") false))))
  (testing "a push to another branch does not run it"
    (is (= 0 (run-hook (str "refs/heads/x " sha " refs/heads/x " sha "\n") false))))
  (testing "deleting a remote branch does not run it"
    (is (= 0 (run-hook (str "(delete) 0000000000000000000000000000000000000000 refs/heads/main " sha "\n") false))))
  (testing "a dirty tree is refused before bb check runs"
    (is (= 1 (run-hook (str "refs/heads/main " sha " refs/heads/main " sha "\n") true)))))
