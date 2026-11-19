(ns wagoe.tools.pre-push-test
  "The pre-push hook runs `bb check` on a push to main, and only then (BOU-1025)."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private hook (str (fs/absolutize ".githooks/pre-push")))

(defn- push-line [remote-ref sha] (str "refs/heads/x " sha " " remote-ref " " sha "\n"))

(deftest ^:unit pre-push-hook-test
  ;; One repo whose `bb` exits 3, so "ran bb check" shows as exit 3.
  (let [repo (str (fs/create-temp-dir))
        bin  (str (fs/create-dirs (fs/path repo ".bin")))
        sh   (fn [opts & args] (apply process/shell (merge {:dir repo :out :string :err :string} opts) args))
        hook! (fn [stdin]
                (:exit (sh {:in stdin :continue true
                            :extra-env {"PATH" (str bin ":" (System/getenv "PATH"))}}
                           "bash" hook)))]
    (try
      (spit (str bin "/bb") "#!/bin/sh\nexit 3\n")
      (fs/set-posix-file-permissions (str bin "/bb") "rwxr-xr-x")
      (spit (str repo "/.gitignore") ".bin\n")
      (sh {} "git" "init" "-q")
      (sh {} "git" "add" ".gitignore")
      (sh {} "git" "-c" "user.email=t@t" "-c" "user.name=t" "commit" "-qm" "init")
      (let [head (str/trim (:out (sh {} "git" "rev-parse" "HEAD")))]
        (testing "a push of HEAD to main runs bb check and fails with it"
          (is (= 3 (hook! (push-line "refs/heads/main" head)))))
        (testing "a push to another branch does not run it"
          (is (= 0 (hook! (push-line "refs/heads/x" head)))))
        (testing "deleting a remote main does not run it"
          (is (= 0 (hook! (push-line "refs/heads/main" (apply str (repeat 40 "0")))))))
        (testing "pushing a commit other than HEAD to main is refused"
          (is (= 1 (hook! (push-line "refs/heads/main" (apply str (repeat 40 "1")))))))
        (testing "a dirty tree is refused before bb check runs"
          (spit (str repo "/x.txt") "x")
          (is (= 1 (hook! (push-line "refs/heads/main" head))))))
      (finally (fs/delete-tree repo)))))
