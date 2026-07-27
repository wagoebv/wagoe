#!/usr/bin/env bb
;; libs/tools/src/boundary/tools/check_hygiene.clj
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

(def ^:private cruft-pattern
  "Matches editor/backup cruft filenames: foo.clj.bak, .bak2, .backup,
   .backup2, .orig, and Emacs autosaves foo~. Does NOT match `.skip`."
  #"(?:\.(?:bak|backup|orig)\d*|~)$")

(defn- tracked-files
  "All files tracked by git, one per line."
  []
  (let [{:keys [exit out]} (process/shell {:out :string :err :string :continue true}
                                          "git" "ls-files")]
    (if (zero? exit)
      (->> (str/split-lines out)
           (remove str/blank?))
      [])))

(defn -main [& _args]
  (let [cruft (->> (tracked-files)
                   (filter #(re-find cruft-pattern %))
                   sort)]
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
