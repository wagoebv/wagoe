(ns wagoe.tools.pre-push-test
  "The pre-push hook runs `bb check` on a push to main, and only then (BOU-1025)."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private hook (str (fs/absolutize ".githooks/pre-push")))

(defn- clean-env
  "This process's env without git's own variables: pushed from a linked worktree,
   an inherited GIT_DIR would point the temp repo's git at the real one."
  []
  (into {} (remove #(str/starts-with? (key %) "GIT_")) (System/getenv)))

(defn- push-line [remote-ref sha] (str "refs/heads/x " sha " " remote-ref " " sha "\n"))

(deftest ^:unit pre-push-hook-test
  ;; The fake `bb` records its env, so "bb check ran" is a file, and exits $BB_EXIT.
  (let [repo  (str (fs/create-temp-dir))
        bin   (str (fs/create-dirs (fs/path repo ".bin")))
        ran   (str bin "/ran")
        sh    (fn [opts & args]
                (apply process/shell (merge {:dir repo :out :string :err :string :env (clean-env)} opts) args))
        hook! (fn [stdin & {:keys [bb-exit env] :or {bb-exit 0}}]
                (fs/delete-if-exists ran)
                (:exit (sh {:in stdin :continue true
                            :env (merge (clean-env) env
                                        {"PATH" (str bin ":" (System/getenv "PATH")) "BB_EXIT" (str bb-exit)})}
                           "bash" hook)))]
    (try
      (spit (str bin "/bb") (str "#!/bin/sh\nenv > " ran "\nexit $BB_EXIT\n"))
      (fs/set-posix-file-permissions (str bin "/bb") "rwxr-xr-x")
      (spit (str repo "/.gitignore") ".bin\n")
      (sh {} "git" "init" "-q")
      (sh {} "git" "add" ".gitignore")
      (sh {} "git" "-c" "user.email=t@t" "-c" "user.name=t" "commit" "-qm" "init")
      (sh {} "git" "update-ref" "refs/remotes/origin/main" "HEAD")
      (let [head (str/trim (:out (sh {} "git" "rev-parse" "HEAD")))
            main (push-line "refs/heads/main" head)]
        (testing "a push of HEAD to main runs bb check and passes with it"
          (is (= 0 (hook! main)))
          (is (fs/exists? ran)))
        (testing "a failing bb check refuses the push"
          (is (= 1 (hook! main :bb-exit 3))))
        (testing "bb check runs without the GIT_DIR a linked worktree exports"
          (hook! main :env {"GIT_DIR" (str repo "/.git")})
          (is (not (str/includes? (slurp ran) "GIT_DIR="))))
        (testing "main among several refs, and an unterminated last line, still run it"
          (is (= 0 (hook! (str main (push-line "refs/heads/x" head)))))
          (is (fs/exists? ran))
          (is (= 0 (hook! (str/trim-newline main))))
          (is (fs/exists? ran)))
        (testing "other branches and deleting main do not run it"
          (is (= 0 (hook! (push-line "refs/heads/x" head))))
          (is (= 0 (hook! (push-line "refs/heads/main" (apply str (repeat 40 "0"))))))
          (is (not (fs/exists? ran))))
        (testing "a commit other than HEAD, or a HEAD behind origin/main, is refused before bb check"
          (is (= 1 (hook! (push-line "refs/heads/main" (apply str (repeat 40 "1"))))))
          (sh {} "git" "-c" "user.email=t@t" "-c" "user.name=t" "commit" "-q" "--allow-empty" "-m" "ahead")
          (sh {} "git" "update-ref" "refs/remotes/origin/main" "HEAD")
          (sh {} "git" "reset" "-q" "--hard" "HEAD~1")
          (is (= 1 (hook! main)))
          (is (not (fs/exists? ran)))
          (sh {} "git" "update-ref" "refs/remotes/origin/main" "HEAD"))
        (testing "every commit needs Reviewed-by and Simplified-by, or Review-skipped (BOU-1041)"
          (let [ok      "Do a thing (BOU-1)\n\nReviewed-by: review-pr\nSimplified-by: simplify"
                commit! (fn [& msgs]
                          (doseq [m msgs]
                            (sh {} "git" "-c" "user.email=t@t" "-c" "user.name=t" "commit" "-q" "--allow-empty" "-m" m))
                          (push-line "refs/heads/main" (str/trim (:out (sh {} "git" "rev-parse" "HEAD")))))]
            (doseq [[want msgs why] [[1 ["Do a thing (BOU-1)"] "no trailers"]
                                     [1 ["Do a thing (BOU-1)\n\nReviewed-by: review-pr"] "only reviewed"]
                                     [1 ["Do a thing (BOU-1)\n\nSimplified-by: simplify"] "only simplified"]
                                     [1 ["Tab\tin it (BOU-1)\n\nReviewed-by: review-pr"] "a tab in the subject"]
                                     [1 ["Do a thing (BOU-1)" ok] "an earlier commit without them"]
                                     [0 [ok] "both"]
                                     [0 ["Fix a typo (BOU-1)\n\nReview-skipped: typo in a docstring"] "skipped, with a reason"]
                                     [0 ["Revert \"Do a thing (BOU-1)\"\n\nThis reverts commit abc."] "a revert"]]]
              (is (= want (hook! (apply commit! msgs))) why)
              (when (= 1 want) (is (not (fs/exists? ran)) why))
              (sh {} "git" "reset" "-q" "--hard" "refs/remotes/origin/main"))))
        (testing "a dirty tree is refused before bb check runs"
          (spit (str repo "/x.txt") "x")
          (is (= 1 (hook! main)))
          (is (not (fs/exists? ran)))))
      (finally (fs/delete-tree repo)))))
