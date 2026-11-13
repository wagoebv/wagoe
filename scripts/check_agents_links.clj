#!/usr/bin/env bb
;; scripts/check_agents_links.clj
;;
;; Validate local markdown links in AGENTS documentation only.
;;
;; Checks:
;; - root AGENTS.md
;; - libs/*/AGENTS.md
;; - dev-docs/agents/**/*.md
;;
;; Exits non-zero when broken local links are found.
;;
;; Usage (babashka):
;;   bb scripts/check_agents_links.clj
;;   bb check-links              (via bb.edn task)

(ns check-agents-links
  (:require [clojure.string :as str]
            [clojure.java.io :as io]))

(def root-dir (io/file (System/getProperty "user.dir")))

(defn iter-agents-files []
  (let [root-agents (io/file root-dir "AGENTS.md")
        libs-dir (io/file root-dir "libs")
        lib-agents (when (.exists libs-dir)
                     (->> (.listFiles libs-dir)
                          (filter #(.isDirectory %))
                          (sort-by #(.getName %))
                          (map #(io/file % "AGENTS.md"))))]
    (->> (concat [root-agents] lib-agents
                 (->> (file-seq (io/file root-dir "dev-docs/agents"))
                      (filter #(and (.isFile %) (str/ends-with? (.getName %) ".md")))
                      (sort-by #(.getPath %))))
         (filter #(.exists %)))))

(defn skippable? [link]
  (or (str/starts-with? link "http://")
      (str/starts-with? link "https://")
      (str/starts-with? link "mailto:")
      (str/starts-with? link "#")))

(defn resolve-target [base-file link]
  (let [target (first (str/split link #"#"))]
    (if (str/starts-with? target "/")
      (io/file root-dir (subs target 1))
      (.getCanonicalFile (io/file (.getParentFile base-file) target)))))

(defn check-file [file]
  (let [content (slurp file)
        link-pattern #"\[[^\]]+\]\(([^)]+)\)"
        local-links (->> (re-seq link-pattern content)
                         (map second)
                         (map str/trim)
                         (remove skippable?))
        broken (->> local-links
                    (map (fn [link]
                           (let [target (resolve-target file link)]
                             (when-not (.exists target)
                               {:file file :link link :target target}))))
                    (remove nil?))]
    {:checked (count local-links)
     :broken broken}))

(defn -main []
  (let [files (vec (iter-agents-files))
        results (map check-file files)
        total-checked (reduce + (map :checked results))
        all-broken (vec (mapcat :broken results))]
    (println (str "AGENTS files checked: " (count files)))
    (println (str "Local links checked: " total-checked))
    (println (str "Broken links: " (count all-broken)))
    (when (seq all-broken)
      (doseq [{:keys [file link target]} all-broken]
        (let [rel (.relativize (.toPath root-dir) (.toPath file))]
          (println (str "\n" rel "\n  -> " link "\n  => " (.getPath target))))))
    (if (seq all-broken) 1 0)))

;; Run when executed directly
(when (= *file* (System/getProperty "babashka.file"))
  (System/exit (-main)))
