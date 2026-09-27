(ns wagoe.cli.templates
  "Shared template helpers for the wagoe CLI: template loading, {{var}}
   rendering, and AGENTS.md's marker-delimited blocks."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn render
  "Replace {{key}} placeholders in template with substitution values."
  [template substitutions]
  (reduce (fn [s [k v]] (str/replace s (str "{{" (name k) "}}") v))
          template
          substitutions))

(defn read-template
  "Slurp a template from the CLI's resources. Throws when missing."
  [tmpl-name]
  (let [r (io/resource (str "wagoe/cli/templates/" tmpl-name))]
    (when-not r
      (throw (ex-info (str "Template not found: " tmpl-name)
                      {:type :internal-error
                       :template tmpl-name})))
    (slurp r)))

;; ─── Marker-delimited blocks ─────────────────────────────────────────────────
;; AGENTS.md uses <!-- name --> ... <!-- /name --> sentinel pairs to delimit
;; framework-owned sections. All helpers operate on the FIRST pair only, by
;; index splicing — a user-duplicated marker elsewhere is never touched.

(defn- open-marker [block] (str "<!-- " block " -->"))
(defn- close-marker [block] (str "<!-- /" block " -->"))

(defn- block-bounds
  "[start-of-body end-of-body] indices of the first marker pair, or nil."
  [content block]
  (let [open  (open-marker block)
        close (close-marker block)
        start (str/index-of content open)
        end   (when start (str/index-of content close start))]
    (when (and start end)
      [(+ start (count open)) end])))

(defn block-content
  "Content between the first marker pair (exclusive), or nil when absent."
  [content block]
  (when-let [[start end] (block-bounds content block)]
    (subs content start end)))

(defn replace-block
  "Replace the content of the first marker pair in target with new-body.
   Returns target unchanged when the markers are missing."
  [target block new-body]
  (if-let [[start end] (block-bounds target block)]
    (str (subs target 0 start) new-body (subs target end))
    target))

(defn update-block
  "Apply f to the body of the first marker pair; no-op when markers absent."
  [content block f]
  (if-let [body (block-content content block)]
    (replace-block content block (f body))
    content))
