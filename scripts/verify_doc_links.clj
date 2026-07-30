#!/usr/bin/env bb
;; scripts/verify_doc_links.clj
;;
;; Verify internal documentation links are valid.
;; Checks link references in .md and .adoc files under docs/.
;;
;; Exits non-zero when broken local links are found.
;;
;; Usage (babashka):
;;   bb scripts/verify_doc_links.clj
;;   bb verify-doc-links          (via bb.edn task)

(ns verify-doc-links
  (:require [clojure.string :as str]
            [clojure.java.io :as io]))

(def root-dir (io/file (System/getProperty "user.dir")))

(defn find-doc-files []
  (let [docs-dir (io/file root-dir "docs")]
    (when (.exists docs-dir)
      (->> (file-seq docs-dir)
           (filter #(.isFile %))
           (filter #(let [n (.getName %)]
                      (or (str/ends-with? n ".md")
                          (str/ends-with? n ".adoc"))))
           (sort-by #(.getPath %))))))

(defn skippable? [url]
  (or (str/starts-with? url "http://")
      (str/starts-with? url "https://")
      (str/starts-with? url "mailto:")
      (str/starts-with? url "#")))

(defn extract-links [content file-type]
  (case file-type
    "md"   (->> (re-seq #"\[([^\]]+)\]\(([^)]+)\)" content)
                (map (fn [[_ text url]] {:text text :url url}))
                (remove #(skippable? (:url %))))
    "adoc" (->> (re-seq #"link:([^\[]+)\[([^\]]+)\]" content)
                (map (fn [[_ url text]] {:text text :url url}))
                (remove #(skippable? (:url %))))))

(defn resolve-target [base-file url]
  (let [path (first (str/split url #"#"))]
    (.getCanonicalFile (io/file (.getParentFile base-file) path))))

(defn check-file [file]
  (let [content (slurp file)
        file-type (if (str/ends-with? (.getName file) ".md") "md" "adoc")
        links (extract-links content file-type)
        broken (->> links
                    (map (fn [{:keys [url text]}]
                           (let [target (resolve-target file url)]
                             (when-not (.exists target)
                               {:file file :link url :text text :target target}))))
                    (remove nil?))]
    {:checked (count links) :broken broken}))

(defn -main []
  (let [docs-dir (io/file root-dir "docs")]
    (when-not (.exists docs-dir)
      (println "docs/ directory not found")
      (System/exit 1))
    (let [files     (vec (find-doc-files))
          _         (println (str "Checking " (count files) " documentation files..."))
          results   (map check-file files)
          total     (reduce + (map :checked results))
          broken    (vec (mapcat :broken results))]
      (println "\nResults:")
      (println (str "  Files checked: " (count files)))
      (println (str "  Links checked: " total))
      (println (str "  Broken links:  " (count broken)))
      (when (seq broken)
        (println "\nBroken Links Found:\n")
        (doseq [{:keys [file link text target]} broken]
          (let [rel (.relativize (.toPath root-dir) (.toPath file))]
            (println (str "  " rel))
            (println (str "    -> " link " ('" text "')"))
            (println (str "    Expected: " (.getPath target)))
            (println))))
      (if (seq broken) 1 0))))

(when (= *file* (System/getProperty "babashka.file"))
  (System/exit (-main)))
