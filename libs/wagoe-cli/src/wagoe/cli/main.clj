(ns wagoe.cli.main)

(defn- usage []
  (println "boundary — Wagoe Framework project tool")
  (println)
  (println "Commands:")
  (println "  wagoe new <project-name>       Create a new project")
  (println "  wagoe add <module>             Add a module to the current project")
  (println "  wagoe list modules             List available modules")
  (println "  wagoe list modules --json      Machine-readable module list")
  (println "  boundary agents update [--check]  Refresh framework sections of AGENTS.md after an upgrade")
  (println "  boundary version                  Show CLI version"))

(defn -main [& args]
  (let [[cmd & rest-args] args]
    (case cmd
      "new"     (do (require 'wagoe.cli.new)
                    ((resolve 'wagoe.cli.new/-main) rest-args))
      "add"     (do (require 'wagoe.cli.add)
                    ((resolve 'wagoe.cli.add/-main) rest-args))
      "list"    (do (require 'wagoe.cli.list-modules)
                    ((resolve 'wagoe.cli.list-modules/-main) rest-args))
      "agents"  (if (= (first rest-args) "update")
                  (do (require 'wagoe.cli.agents-update)
                      ((resolve 'wagoe.cli.agents-update/-main) (rest rest-args)))
                  (do (println "Usage: boundary agents update [--check]")
                      (System/exit 1)))
      "version" (println "boundary CLI version 1.0.0-beta-1")
      (do (when cmd (println (str "Unknown command: " cmd "\n")))
          (usage)
          (System/exit (if cmd 1 0))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
