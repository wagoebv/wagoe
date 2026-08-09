#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/i18n.clj
;;
;; i18n tooling for the Wagoe framework.
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
            [clojure.string :as str]
            [wagoe.tools.parsing :as parsing]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- detect-wagoe-root []
  (cond
    (fs/exists? "libs/i18n/resources/wagoe/i18n/translations") "."
    (fs/exists? "../wagoe/libs/i18n/resources/wagoe/i18n/translations") "../wagoe"
    :else nil))

(defn- translations-dir []
  (when-let [wagoe-root (detect-wagoe-root)]
    (str wagoe-root "/libs/i18n/resources/wagoe/i18n/translations")))

(defn- ui-src-dirs []
  (let [wagoe-root (detect-wagoe-root)
        wagoe-dirs (when wagoe-root
                     [(str wagoe-root "/libs/user/src")
                      (str wagoe-root "/libs/admin/src")
                      (str wagoe-root "/libs/search/src")
                      (str wagoe-root "/libs/calendar/src")
                      (str wagoe-root "/libs/workflow/src")])]
    (->> (concat ["src"] wagoe-dirs)
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
      (println "No Wagoe i18n catalogue found. Expected either:")
      (println "  libs/i18n/resources/wagoe/i18n/translations")
      (println "  ../wagoe/libs/i18n/resources/wagoe/i18n/translations")
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

(def user-visible-text
  "What counts as translatable prose.

   Starts with a capital and continues with letters or spaces, so CSS classes,
   keywords, paths and identifiers are not swept up. Deliberately blind to
   lowercase fragments: `(str \" hour\" \" ago\")` is unexternalised English too,
   but matching lowercase turns every map key and option name into a finding."
  #"^[A-Z][A-Za-z ]{3,}")

(def ^:private pattern-arg
  "Calls whose string argument is a machine pattern, not prose.

   A date format and a regex read as capitalised text and are not translated
   through the catalogue."
  #"(?:ofPattern|re-pattern|re-find|re-matches|re-seq)\s+$")

(defn scan-violations
  "Unexternalised user-visible literals in `content`.

   Pure, so the rules are testable without a source tree. `lines` is used only
   to skip interpolation arguments — `[:t :k {:name \"Alice\"}]` is already
   externalised and its argument is data.

   Returns a seq of {:line int :text str}."
  [content]
  (let [lines (vec (str/split-lines content))]
    (for [lit  (parsing/string-literals content)
          :when (re-find user-visible-text (:text lit))
          :when (not (:regex? lit))
          :when (not (parsing/docstring? lit))
          :when (not (re-find pattern-arg (:preceding-code lit)))
          :when (not (str/includes? (get lines (dec (:line lit)) "") "[:t "))]
      {:line (:line lit) :text (:text lit)})))

(defn scan
  "Scan core/ui.clj files for hardcoded English string literals.
   Exits 1 if any are found (suitable as a CI gate).

   Only `**/core/ui.clj` is covered — Hiccup elsewhere (shell/web_handlers,
   admin views) is not scanned, so a clean run is not proof the whole codebase
   is externalised."
  []
  (let [ui-files   (mapcat #(fs/glob % "**/core/ui.clj") (ui-src-dirs))
        violations (for [f ui-files
                         v (scan-violations (slurp (str f)))]
                     (assoc v :file (str f)))]
    (if (seq violations)
      (do
        (println "FAIL: Unexternalised string literals found in core/ui.clj files:")
        (doseq [{:keys [file line text]} violations]
          (println (format "  %s:%d  \"%s\"" file line text)))
        (System/exit 1))
      (println (format "OK: No unexternalised string literals found in %d core/ui.clj file(s)."
                       (count ui-files))))))

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
      (println "No Wagoe i18n catalogue found.")
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
      (println "No Wagoe i18n catalogue found.")
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
