(ns wagoe.cli.agents-update
  "Refresh the framework-owned sections of a project's AGENTS.md after a
   Wagoe upgrade.

   `wagoe new` renders AGENTS.md from a template that evolves with the
   framework (new pitfalls, conventions, modules). This command re-renders the
   marker-delimited blocks from the currently installed CLI's template and
   splices them into the project file, leaving everything the user wrote
   outside the markers untouched.

   Synced from the template:
     <!-- gen:fc-is -->               FC/IS rules
     <!-- gen:naming -->              case conventions
     <!-- gen:pitfalls -->            common pitfalls

   The two module blocks are rendered from the project's deps.edn and
   config.edn instead, as `wagoe new` and `wagoe add` render them."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [wagoe.cli.add :as add]
            [wagoe.cli.templates :as templates]))

(def ^:private synced-blocks
  ["gen:fc-is" "gen:naming" "gen:pitfalls"])

(def ^:private module-blocks
  ["wagoe:available-modules" "wagoe:installed-modules"])

(def ^:private block-content templates/block-content)
(def ^:private replace-block templates/replace-block)

(defn project-name-from-agents
  "Project name from the AGENTS.md title line, or nil."
  [content]
  (second (re-find #"(?m)^# (.+?) — Developer Reference" content)))

(defn update-agents-content
  "Pure core of the update: returns {:content new-content :updated [...] :missing [...]}.
   `states` are the project's modules as `add/module-states` returns them;
   nil leaves the module blocks alone."
  [current template substitutions states]
  (let [rendered (templates/render template substitutions)
        synced   (reduce (fn [{:keys [content updated missing]} block]
                           (let [new-body (block-content rendered block)
                                 old-body (block-content content block)]
                             (cond
                               ;; Markers absent in the project file — or in the template
                               ;; itself: splicing a nil body would silently empty the
                               ;; project's block, so both count as "cannot sync, skip".
                               (or (nil? old-body) (nil? new-body))
                               {:content content :updated updated :missing (conj missing block)}

                               (= old-body new-body)
                               {:content content :updated updated :missing missing}

                               :else
                               {:content (replace-block content block new-body)
                                :updated (conj updated block)
                                :missing missing})))
                         {:content current :updated [] :missing []}
                         synced-blocks)
        content  (:content synced)
        absent   (filterv #(nil? (block-content content %)) module-blocks)]
    (cond
      (nil? states)  synced
      (seq absent)   (update synced :missing into absent)
      :else
      (let [modules (add/render-module-blocks content states)]
        (assoc synced
               :content modules
               :updated (into (:updated synced)
                              (remove #(= (block-content content %) (block-content modules %))
                                      module-blocks)))))))

(defn -main [args]
  (let [check?        (some #{"--check"} args)
        ;; The module blocks alone: they follow the project, the rest stays
        ;; as the user has it.
        modules-only? (some #{"--modules"} args)
        f             (io/file "AGENTS.md")]
    (if-not (.exists f)
      (do (println "No AGENTS.md found in the current directory.")
          (println "Run this from a Wagoe project root (created with `wagoe new`).")
          (System/exit 1))
      (let [current      (slurp f)
            project-name (or (project-name-from-agents current)
                             (.getName (.getCanonicalFile (io/file "."))))
            project-ns   (str/replace project-name "-" "_")
            dir          (System/getProperty "user.dir")
            states       (when (.exists (io/file dir "deps.edn"))
                           (add/module-states dir))
            {:keys [content updated missing]}
            (if modules-only?
              ;; A template whose gen blocks are the project's own is a no-op for them.
              (update-agents-content current current {} states)
              (update-agents-content current (templates/read-template "AGENTS.md.tmpl")
                                     {:project-name project-name
                                      :project-ns   project-ns}
                                     states))]
        (doseq [block missing]
          (println (str "  Warning: markers for '" block "' not found — block skipped")))
        (cond
          (= content current)
          (println "AGENTS.md is up to date.")

          check?
          (do (println (str "AGENTS.md is out of date. Stale blocks: " (str/join ", " updated)))
              (println "Run `wagoe agents update` (or `bb agents:update`) to refresh.")
              (System/exit 1))

          :else
          (do (spit f content)
              (println (str "AGENTS.md updated. Refreshed blocks: " (str/join ", " updated)))
              (println "Sections outside the framework markers were left untouched.")))))))
