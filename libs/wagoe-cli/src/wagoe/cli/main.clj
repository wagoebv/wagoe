(ns wagoe.cli.main
  ;; The catalogue is the only version the CLI has — `:cli-version` there is
  ;; gated by `bb check:versions` and rewritten by `bb bump`. A literal here
  ;; answered `1.0.0-beta-5` for four releases after beta-5, because no rule
  ;; reads a printed banner. Required eagerly rather than per-command like the
  ;; rest: it loads the catalogue behind a delay, so nothing is read until asked.
  (:require [wagoe.cli.catalogue :as catalogue]))

(defn- usage []
  (println "wagoe — Wagoe Framework project tool")
  (println)
  (println "Commands:")
  (println "  wagoe new <project-name>       Create a new project")
  (println "  wagoe add <module>             Add a module to the current project")
  (println "  wagoe doctor                   Check this project and say what to do next")
  (println "  wagoe list modules             List available modules")
  (println "  wagoe list modules --json      Machine-readable module list")
  (println "  wagoe agents update [--check]  Refresh framework sections of AGENTS.md after an upgrade")
  (println "  wagoe version                  Show CLI version"))

(defn -main [& args]
  (let [[cmd & rest-args] args]
    (case cmd
      "new"     (do (require 'wagoe.cli.new)
                    ((resolve 'wagoe.cli.new/-main) rest-args))
      "add"     (do (require 'wagoe.cli.add)
                    ((resolve 'wagoe.cli.add/-main) rest-args))
      "doctor"  (do (require 'wagoe.cli.doctor)
                    ((resolve 'wagoe.cli.doctor/-main) rest-args))
      "list"    (do (require 'wagoe.cli.list-modules)
                    ((resolve 'wagoe.cli.list-modules/-main) rest-args))
      "agents"  (if (= (first rest-args) "update")
                  (do (require 'wagoe.cli.agents-update)
                      ((resolve 'wagoe.cli.agents-update/-main) (rest rest-args)))
                  (do (println "Usage: wagoe agents update [--check]")
                      (System/exit 1)))
      "version" (println (str "wagoe CLI version "
                              (:cli-version (catalogue/load-catalogue))))
      (do (when cmd (println (str "Unknown command: " cmd "\n")))
          (usage)
          (System/exit (if cmd 1 0))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
