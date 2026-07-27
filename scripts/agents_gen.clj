#!/usr/bin/env bb
;; scripts/agents_gen.clj
;; Deterministic generator for the framework AGENTS.md and the downstream
;; AGENTS.md.tmpl, from resources/agents/knowledge.edn + modules-catalogue.edn.
;; Usage:
;;   bb agents:gen            ; write both targets
;;   bb agents:gen --check    ; verify in sync + module-source valid; non-zero on drift
(ns agents-gen
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn splice-region
  "Replace the content between <!-- gen:SECTION --> and <!-- /gen:SECTION -->
   with body (markers preserved, body placed on its own lines). Throws if a
   marker is missing."
  [content section body]
  (let [open  (str "<!-- gen:" section " -->")
        close (str "<!-- /gen:" section " -->")
        oi    (str/index-of content open)
        ci    (str/index-of content close)]
    (when (or (nil? oi) (nil? ci))
      (throw (ex-info "gen marker not found" {:section section})))
    (str (subs content 0 (+ oi (count open)))
         "\n" body "\n"
         (subs content ci))))

(defn- sub-ns [s ns-token] (str/replace s "{{ns}}" ns-token))

(defn- seg-label
  "Human label for an FC/IS layer segment keyword (acronyms upper-cased)."
  [kw]
  (let [n (name kw)]
    (get {"io" "IO" "fcis" "FC/IS"} n (str/capitalize n))))

(defn- esc-cell
  "Escape pipe chars so dynamic text can't break a markdown table cell."
  [s]
  (str/replace (str s) "|" "\\|"))

(defn render-fc-is
  "Render the FC/IS layer rules section as markdown. ns-token replaces {{ns}}."
  [{:keys [layers rules ports-required example]} ns-token]
  (let [arrow (fn [{:keys [from to allowed reason]}]
                (format "| %s → %s | %s |"
                        (seg-label from)
                        (seg-label to)
                        (if allowed "✅ allowed"
                            (str "❌ NEVER — " (esc-cell reason)))))]
    (str "| Direction | Allowed? |\n"
         "|-----------|----------|\n"
         (str/join "\n" (map arrow layers)) "\n\n"
         (when ports-required "Every module MUST define `ports.clj`.\n\n")
         (str/join "\n" (map #(str "- " %) rules)) "\n\n"
         "```clojure\n" (sub-ns example ns-token) "\n```")))

(defn render-pitfalls
  "Render pitfalls whose :surfaces contains `surface`. ns-token replaces {{ns}}.
   Output order follows the input vector (deterministic). An optional :example is
   rendered as a fenced clojure block after the Fix line."
  [pitfalls surface ns-token]
  (->> pitfalls
       (filter #(contains? (:surfaces %) surface))
       (map-indexed
        (fn [i {:keys [title symptom cause fix example]}]
          (sub-ns
           (str (format "### %d. %s\n\n- **Symptom:** %s\n- **Cause:** %s\n- **Fix:** %s"
                        (inc i) title symptom cause fix)
                (when example (str "\n\n```clojure\n" example "\n```")))
           ns-token)))
       (str/join "\n\n")))

(defn render-naming
  "Render the case-convention table as markdown."
  [rows]
  (let [label {:clojure "All Clojure code" :db "Database boundary only" :api "API/JSON boundary only"}
        row (fn [{:keys [context case example]}]
              (format "| %s | %s | `%s` |" (label context) (name case) (esc-cell example)))]
    (str "| Location | Convention | Example |\n"
         "|----------|-----------|---------|\n"
         (str/join "\n" (map row rows)))))

(defn render-modules
  "Render the framework module table from catalogue :modules entries.
   Name links to the lib's AGENTS.md; no version/clojars (avoids version drift)."
  [modules]
  (let [sorted (sort-by :name modules)
        cell-name (fn [{:keys [name docs-url]}] (format "[%s](%s)" name docs-url))
        names (map cell-name sorted)
        descs (map :description sorted)
        w1 (apply max (count "Module") (map count names))
        w2 (apply max (count "Description") (map count descs))
        pad (fn [s w] (str s (apply str (repeat (- w (count s)) " "))))
        row (fn [a b] (format "| %s | %s |" (pad a w1) (pad b w2)))]
    (str (row "Module" "Description") "\n"
         (format "|%s|%s|"
                 (apply str (repeat (+ w1 2) "-"))
                 (apply str (repeat (+ w2 2) "-"))) "\n"
         (str/join "\n" (map #(row (cell-name %) (esc-cell (:description %))) sorted)))))

(def knowledge-path "resources/agents/knowledge.edn")
(def catalogue-resource "wagoe/cli/modules-catalogue.edn")
(def tmpl-path "libs/boundary-cli/resources/boundary/cli/templates/AGENTS.md.tmpl")

(defn load-knowledge [] (edn/read-string (slurp knowledge-path)))
(defn load-modules []
  (if-let [r (io/resource catalogue-resource)]
    (-> r slurp edn/read-string :modules)
    (throw (ex-info "modules-catalogue.edn not found on classpath"
                    {:resource catalogue-resource}))))

(def targets
  [{:file "AGENTS.md"
    :sections [:naming :fc-is :pitfalls :modules]
    :ns-token "myapp" :pitfall-surface :framework}
   {:file tmpl-path
    :sections [:naming :fc-is :pitfalls]
    :ns-token "{{project-ns}}" :pitfall-surface :downstream}])

(defn render-section [section knowledge modules {:keys [ns-token pitfall-surface]}]
  (case section
    :naming   (render-naming (:naming knowledge))
    :fc-is    (render-fc-is (:fc-is knowledge) ns-token)
    :pitfalls (render-pitfalls (:pitfalls knowledge) pitfall-surface ns-token)
    :modules  (render-modules (concat modules (:dev-modules knowledge)))))

(defn render-target
  "Return the target file content with each owned section spliced in."
  [content knowledge modules {:keys [sections] :as opts}]
  (reduce (fn [doc section]
            (splice-region doc (name section)
                           (render-section section knowledge modules opts)))
          content sections))

(defn- generate-file [knowledge modules {:keys [file] :as target}]
  (let [current  (slurp file)
        rendered (render-target current knowledge modules target)]
    {:file file :current current :rendered rendered}))

(defn drifted-files
  "Return the seq of target files whose current content differs from rendered."
  [results]
  (->> results (remove #(= (:current %) (:rendered %))) (map :file)))

(defn libs-with-agents
  "Set of lib names under libs/ that contain an AGENTS.md."
  []
  (->> (or (.listFiles (io/file "libs")) [])
       (filter #(.isDirectory %))
       (filter #(.exists (io/file % "AGENTS.md")))
       (map #(.getName %))
       set))

(defn- docs-url->lib
  "Parse the libs/<lib>/AGENTS.md suffix out of a docs URL. nil if no match."
  [url]
  (some-> (re-find #"libs/([^/]+)/AGENTS\.md" (str url)) second))

(defn validate-modules
  "Return a seq of human-readable problems. Empty = valid.
   1) Every libs/<lib> with an AGENTS.md (minus :dev-modules names) must be in the catalogue.
   2) Every catalogue :docs-url must parse to libs/<lib>/AGENTS.md and resolve to an existing file."
  [modules {:keys [dev-modules]}]
  (let [allowlist  (set (map :name dev-modules))
        cat-names  (set (map :name modules))
        documented (libs-with-agents)
        missing    (sort (remove allowlist (remove cat-names documented)))
        bad-url    (for [m modules
                         :when (nil? (docs-url->lib (:docs-url m)))]
                     (str "catalogue entry '" (:name m) "' has an unparseable :docs-url (expected .../libs/<lib>/AGENTS.md)"))
        dead       (for [m modules
                         :let [lib (docs-url->lib (:docs-url m))]
                         :when (and lib (not (.exists (io/file "libs" lib "AGENTS.md"))))]
                     (str "catalogue entry '" (:name m) "' docs-url points at missing libs/" lib "/AGENTS.md"))]
    (concat
     (map #(str "lib '" % "' has AGENTS.md but no modules-catalogue.edn entry (add it or add to :dev-modules)") missing)
     bad-url
     dead)))

(defn run-check
  "Print drift + validation problems; System/exit 1 if any."
  [results modules knowledge]
  (let [drift   (drifted-files results)
        invalid (validate-modules modules knowledge)]
    (doseq [f drift] (println "✗ out of sync (run bb agents:gen):" f))
    (doseq [p invalid] (println "✗" p))
    (if (or (seq drift) (seq invalid))
      (System/exit 1)
      (println "✓ AGENTS files in sync; module catalogue valid"))))

(defn -main [& args]
  (let [check?    (some #{"--check"} args)
        knowledge (load-knowledge)
        modules   (load-modules)
        results   (map #(generate-file knowledge modules %) targets)]
    (if check?
      (run-check results modules knowledge)
      (do (doseq [{:keys [file rendered]} results] (spit file rendered))
          (println "agents:gen — wrote" (count results) "targets")))))

(when (= *file* (System/getProperty "babashka.file")) (apply -main *command-line-args*))
