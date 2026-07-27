#!/usr/bin/env bb
;; libs/tools/src/boundary/tools/i18n.clj
;;
;; i18n tooling for the Boundary framework.
;;
;; Usage (via bb.edn tasks):
;;   bb i18n:find "Sign in"       ; find key by substring in en.edn, then grep codebase
;;   bb i18n:find :user/sign-in   ; find by exact keyword
;;   bb i18n:scan                 ; scan core/ui.clj files for unexternalised string literals
;;   bb i18n:missing              ; report keys present in en.edn but missing from nl.edn
;;   bb i18n:unused               ; report catalogue keys not referenced in source

(ns wagoe.tools.i18n
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- detect-boundary-root []
  (cond
    (fs/exists? "libs/i18n/resources/boundary/i18n/translations") "."
    (fs/exists? "../boundary/libs/i18n/resources/boundary/i18n/translations") "../boundary"
    :else nil))

(defn- translations-dir []
  (when-let [boundary-root (detect-boundary-root)]
    (str boundary-root "/libs/i18n/resources/boundary/i18n/translations")))

(defn- ui-src-dirs []
  (let [boundary-root (detect-boundary-root)
        boundary-dirs (when boundary-root
                        [(str boundary-root "/libs/user/src")
                         (str boundary-root "/libs/admin/src")
                         (str boundary-root "/libs/search/src")
                         (str boundary-root "/libs/calendar/src")
                         (str boundary-root "/libs/workflow/src")])]
    (->> (concat ["src"] boundary-dirs)
         distinct
         (filter fs/exists?))))

(defn- load-edn [path]
  (when (fs/exists? path)
    (edn/read-string (slurp (str path)))))

(defn- load-locale [locale]
  (when-let [dir (translations-dir)]
    (load-edn (str dir "/" (name locale) ".edn"))))

(defn- flat-keys [m]
  (set (keys m)))

(defn- grep [pattern paths & {:keys [quiet?]}]
  (let [args (concat ["rg" "--no-heading" "-n" pattern] paths)
        result (apply proc/shell {:out :string :err :string :continue true} args)]
    (when-not quiet?
      (print (:out result)))
    (:out result)))

;; =============================================================================
;; find-key — find a key in the catalogue and all source files
;; =============================================================================

(defn find-key
  "Find a key (by substring or exact keyword) in en.edn, then grep codebase."
  [query]
  (let [en (load-locale :en)]
    (when-not en
      (println "No Boundary i18n catalogue found. Expected either:")
      (println "  libs/i18n/resources/boundary/i18n/translations")
      (println "  ../boundary/libs/i18n/resources/boundary/i18n/translations")
      (System/exit 1))
    (println (str "=== Catalogue entries matching: " query " ==="))
    (doseq [[k v] (sort-by first en)
            :when (or (str/includes? (str v) query)
                      (str/includes? (str k) query))]
      (println (format "  %-50s %s" k v)))
    (println)
    (println (str "=== Source references matching: " query " ==="))
    (grep query (ui-src-dirs))))

;; =============================================================================
;; scan — find unexternalised string literals in core/ui.clj files
;; =============================================================================

(defn scan
  "Scan core/ui.clj files for hardcoded English string literals.
   Exits 1 if any are found (suitable as a CI gate).

   Heuristic: strings that start with an uppercase letter and are at least 4
   chars long, in Hiccup position (not attribute values or CSS classes)."
  []
  (let [ui-files (mapcat #(fs/glob % "**/core/ui.clj") (ui-src-dirs))
        ;; Pattern: string literals starting with uppercase, min 4 chars,
        ;; that look like user-visible text (not CSS classes or :keywords)
        ;; Exclude strings that are all-caps (constants) or contain slashes (paths)
        pattern "\"[A-Z][A-Za-z ]{3,}[^\"]*\""
        violations (atom [])]
    (doseq [f ui-files]
      (let [content (slurp (str f))
            lines   (str/split-lines content)]
        (doseq [[i line] (map-indexed vector lines)
                :when (re-find (re-pattern pattern) line)
                ;; Skip lines that are pure comments
                :when (not (str/starts-with? (str/trim line) ";"))
                ;; Skip lines that look like they already have [:t ...]
                :when (not (str/includes? line "[:t "))
                ;; Skip docstrings (line before contains defn/defmethod)
                :when (not (str/includes? line "\""))
                :let [match (re-find (re-pattern pattern) line)]]
          (swap! violations conj {:file (str f) :line (inc i) :text match}))))
    (if (seq @violations)
      (do
        (println "FAIL: Unexternalised string literals found in core/ui.clj files:")
        (doseq [{:keys [file line text]} @violations]
          (println (format "  %s:%d  %s" file line text)))
        (System/exit 1))
      (println "OK: No unexternalised string literals found."))))

;; =============================================================================
;; missing — report keys present in en.edn but absent from other locales
;; =============================================================================

(defn missing
  "Report translation keys present in en.edn but missing from other locales."
  []
  (let [en-keys (flat-keys (load-locale :en))
        locales [:nl]
        found-missing? (atom false)]
    (when-not en-keys
      (println "No Boundary i18n catalogue found.")
      (System/exit 1))
    (doseq [locale locales]
      (let [other-keys (flat-keys (load-locale locale))
            gaps       (set/difference en-keys other-keys)]
        (when (seq gaps)
          (reset! found-missing? true)
          (println (str "\nMissing from " (name locale) ".edn (" (count gaps) " keys):"))
          (doseq [k (sort gaps)]
            (println (str "  " k))))))
    (if @found-missing?
      (do (println "\nRun `bb i18n:missing` to see gaps.")
          (System/exit 1))
      (println "OK: All locales have complete translations."))))

;; =============================================================================
;; unused — report catalogue keys not referenced in source
;; =============================================================================

(defn unused
  "Report catalogue keys that are not referenced in any source file."
  []
  (let [en-keys  (flat-keys (load-locale :en))
        all-src  (concat
                  (mapcat #(fs/glob % "**/*.clj") (ui-src-dirs))
                  (fs/glob "src" "**/*.clj"))
        content  (str/join "\n" (map #(slurp (str %)) all-src))
        used     (into #{} (keep (fn [k]
                                   (when (str/includes? content (str k))
                                     k))
                                 en-keys))
        unused   (set/difference en-keys used)]
    (when-not en-keys
      (println "No Boundary i18n catalogue found.")
      (System/exit 1))
    (if (seq unused)
      (do
        (println (str "Unused catalogue keys (" (count unused) "):"))
        (doseq [k (sort unused)]
          (println (str "  " k))))
      (println "OK: All catalogue keys are referenced in source."))))

;; =============================================================================
;; Entry point
;; =============================================================================

(defn -main [& args]
  (let [[cmd & rest-args] args]
    (case cmd
      "find"    (find-key (first rest-args))
      "scan"    (scan)
      "missing" (missing)
      "unused"  (unused)
      (do (println "Usage: bb i18n <find|scan|missing|unused> [args]")
          (System/exit 1)))))
