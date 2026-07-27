#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/admin_entity.clj
;;
;; Babashka wrapper for AI-powered admin entity EDN generation.
;; Delegates to the Clojure AI CLI: wagoe.ai.shell.cli-entry admin-entity
;;
;; Invoked via: bb ai admin-entity "products with name, price, status"

(ns wagoe.tools.admin-entity
  (:require [wagoe.tools.ansi :refer [bold red dim]]
            [clojure.string :as str]
            [babashka.process :refer [shell]]))

;; =============================================================================
;; Main entry point
;; =============================================================================

(defn -main [description & {:keys [yes?] :or {yes? false}}]
  (when (or (nil? description) (str/blank? description))
    (println (red "Please provide an entity description."))
    (println "  Example: bb ai admin-entity \"products with name, price, status\"")
    (System/exit 1))

  (println)
  (println (bold "✦ Boundary AI Admin Entity Generator"))
  (println (dim (str "Parsing: " description)))
  (println)

  (try
    (if yes?
      (shell "clojure" "-M" "-m" "wagoe.ai.shell.cli-entry" "admin-entity" "--yes" description)
      (shell "clojure" "-M" "-m" "wagoe.ai.shell.cli-entry" "admin-entity" description))
    (catch Exception e
      (println (red (str "Admin entity generator exited with error: " (.getMessage e))))
      (System/exit 1))))

;; Run when executed directly (not via bb.edn task)
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
