#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/check_hygiene.clj
;;
;; Rejects version-controlled editor/backup cruft. Backup files committed under
;; src/ or test/ shadow real namespaces, leak into target/classes, and inflate
;; the audited surface. This gate fails if any tracked file has a backup-style
;; extension so they can never be committed again.
;;
;; Allowed by design: the `.skip` convention for a deliberately-disabled test.

(ns wagoe.tools.check-hygiene
  (:require [clojure.string :as str]
            [babashka.process :as process]
            [wagoe.tools.ansi :as ansi]))

(def cruft-pattern
  "Matches editor/backup cruft filenames: foo.clj.bak, .bak2, .backup,
   .backup2, .orig, and Emacs autosaves foo~. Does NOT match `.skip`."
  #"(?:\.(?:bak|backup|orig)\d*|~)$")

(defn cruft-files
  "The cruft filenames in `paths`, sorted.

   Pure and public so a test can prove the gate still detects what it claims
   to. Testing `-main` is not an option — it exits the process."
  [paths]
  (->> paths
       (filter #(re-find cruft-pattern %))
       sort))

(defn tracked-files
  "All files tracked by git.

   Throws when git fails. It previously returned [] on a non-zero exit, which
   made the gate report `No tracked backup/cruft files` whenever git could not
   run — outside a work tree, or with git missing. A check that reports clean
   because it could not look is the failure mode BOU-250 is about, so an
   unusable git is now loud."
  []
  (let [{:keys [exit out err]} (process/shell {:out :string :err :string :continue true}
                                              "git" "ls-files")]
    (when-not (zero? exit)
      (throw (ex-info (str "git ls-files failed (exit " exit ") — cannot determine "
                           "tracked files, so this gate cannot report a verdict")
                      {:exit exit :err (str/trim (or err ""))})))
    (->> (str/split-lines out)
         (remove str/blank?))))

(defn -main [& _args]
  (let [cruft (cruft-files (tracked-files))]
    (if (seq cruft)
      (do
        (println (ansi/red "Tracked backup/cruft files found:"))
        (println)
        (doseq [f cruft] (println (str "  " f)))
        (println)
        (println (str (count cruft) " file(s) must not be committed. "
                      "Delete them (git rm) — they are covered by .gitignore."))
        (System/exit 1))
      (do
        (println (ansi/green "No tracked backup/cruft files."))
        (System/exit 0)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
