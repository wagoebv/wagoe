#!/usr/bin/env bb
;; scripts/docs_lint.clj
;; 
;; Documentation drift linter for Wagoe Framework.
;; Detects stale paths, broken links, unknown namespaces, and invalid aliases.
;;
;; Usage (babashka):
;;   bb scripts/docs_lint.clj
;;   bb scripts/docs_lint.clj --verbose
;;   bb scripts/docs_lint.clj --out-dir build/docs-lint
;;
;; Usage (Clojure CLI via wrapper):
;;   clojure -M:dev -m wagoe.tools.docs-lint
;;
;; Output:
;;   build/docs-lint/report.edn   - structured report
;;   build/docs-lint/report.txt   - human-readable summary
;;   Console summary with top warnings
;;
;; All findings are warnings (exit code 0).

(ns docs-lint
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:dynamic *verbose* false)
(def ^:dynamic *out-dir* "build/docs-lint")

(def doc-modules
  #{"ROOT" "architecture" "getting-started" "guides" "libraries"})

;; Files/directories to scan
(def include-patterns
  ["README.md"
   "README.adoc"
   "AGENTS.md"
   "docs"
   "examples"
   "libs/core/README.md"
   "libs/observability/README.md"
   "libs/platform/README.md"
   "libs/user/README.md"
   "libs/admin/README.md"
   "libs/storage/README.md"
   "libs/scaffolder/README.md"
   "libs/external/README.md"])

;; Patterns to exclude (even if matched by includes)
(def exclude-patterns
  [#".*/src/.*/README.*"   ;; module READMEs inside code trees
   #"^build/.*"
   #"^target/.*"
   #"^\.cpcache/.*"
   #"^\.git/.*"
   #"^node_modules/.*"
   #"^docs/archive/.*"])   ;; archived/deprecated docs

;; Known stale/pre-split path patterns to warn about
(def stale-path-patterns
  [[#"(?<![/a-zA-Z])src/boundary/" "Pre-split path: code moved to libs/*/src/boundary/"]
   [#"(?<![/a-zA-Z])test/boundary/" "Pre-split path: tests moved to libs/*/test/boundary/"]
   [#"cd libs/\w+ && clojure" "Consider using root-level commands instead of cd into libs"]])

;; =============================================================================
;; File Discovery
;; =============================================================================

(defn file-exists? [path]
  (.exists (io/file path)))

(defn directory? [path]
  (.isDirectory (io/file path)))

(defn list-files-recursive [dir]
  (let [d (io/file dir)]
    (when (.exists d)
      (->> (file-seq d)
           (filter #(.isFile %))
           (map #(.getPath %))))))

(defn matches-exclude? [path]
  (some #(re-find % path) exclude-patterns))

(defn discover-doc-files []
  (let [base-dir (System/getProperty "user.dir")]
    (->> include-patterns
         (mapcat (fn [pattern]
                   (let [f (io/file base-dir pattern)]
                     (cond
                       (not (.exists f)) []
                       (.isFile f) [(.getPath f)]
                       (.isDirectory f) (list-files-recursive f)
                       :else []))))
         (filter #(or (str/ends-with? % ".md")
                      (str/ends-with? % ".adoc")
                      (str/ends-with? % ".txt")))
         (map #(str/replace % (str base-dir "/") ""))
         (remove matches-exclude?)
         (distinct)
         (sort))))

;; =============================================================================
;; Namespace Discovery
;; =============================================================================

(defn discover-clojure-files []
  (let [base-dir (System/getProperty "user.dir")]
    (->> (concat
          (list-files-recursive (io/file base-dir "src"))
          (list-files-recursive (io/file base-dir "libs")))
         (filter #(or (str/ends-with? % ".clj")
                      (str/ends-with? % ".cljc")))
         (remove #(str/includes? % "/test/")))))

(defn extract-namespace [file-path]
  (try
    (let [content (slurp file-path)]
      (when-let [match (re-find #"\(ns\s+([a-zA-Z0-9._-]+)" content)]
        (second match)))
    (catch Exception _ nil)))

(defn discover-namespaces []
  (->> (discover-clojure-files)
       (map extract-namespace)
       (remove nil?)
       (set)))

;; =============================================================================
;; deps.edn Alias Discovery
;; =============================================================================

(defn discover-aliases []
  (try
    (let [deps-file (io/file (System/getProperty "user.dir") "deps.edn")
          content (slurp deps-file)
          deps (edn/read-string content)]
      (set (keys (:aliases deps))))
    (catch Exception e
      (println "Warning: could not parse deps.edn:" (.getMessage e))
      #{})))

;; =============================================================================
;; Library Discovery
;; =============================================================================

(defn discover-libraries []
  (let [libs-dir (io/file (System/getProperty "user.dir") "libs")]
    (if (.exists libs-dir)
      (->> (.listFiles libs-dir)
           (filter #(.isDirectory %))
           (map #(.getName %))
           (map keyword)
           (set))
      #{})))

;; =============================================================================
;; Check: Internal Links
;; =============================================================================

(defn extract-md-links [content]
  ;; Match [text](path) but not [text](http...) or [text](#anchor)
  (let [pattern #"\[([^\]]*)\]\(([^)]+)\)"]
    (->> (re-seq pattern content)
         (map (fn [[_ text path]]
                {:text text :path path}))
         (remove #(or (str/starts-with? (:path %) "http")
                      (str/starts-with? (:path %) "mailto:")
                      (str/starts-with? (:path %) "#"))))))

(defn extract-adoc-links [content]
  ;; Match link:path[text] and xref:path[text]
  (let [pattern #"(?:link|xref):([^\[]+)\["]
    (->> (re-seq pattern content)
         (map second)
         (remove #(or (str/starts-with? % "http")
                      (str/starts-with? % "mailto:")
                      (str/starts-with? % "#")))
         (map (fn [path] {:text "" :path path})))))

(defn- current-doc-module [file-path]
  (second (re-find #"docs/modules/([^/]+)/" file-path)))

(defn- resolve-adoc-link [base-dir file-path file-dir clean-path]
  (cond
    (str/starts-with? clean-path "/")
    (io/file base-dir (subs clean-path 1))

    ;; Antora style xref/module target: module:page.adoc
    (and (str/includes? clean-path ":")
         (not (re-find #"^[a-zA-Z]+://" clean-path)))
    (let [[module target] (str/split clean-path #":" 2)]
      (if (and (contains? doc-modules module)
               (str/ends-with? target ".adoc"))
        (io/file base-dir (str "docs/modules/" module "/pages/" target))
        (io/file file-dir clean-path)))

    ;; Antora nav files typically target pages in their own module.
    (and (str/ends-with? file-path "/nav.adoc")
         (str/ends-with? clean-path ".adoc")
         (current-doc-module file-path))
    (io/file base-dir
             (str "docs/modules/" (current-doc-module file-path) "/pages/" clean-path))

    :else
    (io/file file-dir clean-path)))

(defn check-internal-links [file-path content]
  (let [base-dir (System/getProperty "user.dir")
        file-dir (.getParent (io/file base-dir file-path))
        links (if (str/ends-with? file-path ".adoc")
                (extract-adoc-links content)
                (extract-md-links content))]
    (->> links
         (map (fn [{:keys [path]}]
                ;; Strip anchor from path
                (let [clean-path (first (str/split path #"#"))
                      resolved (if (str/ends-with? file-path ".adoc")
                                 (resolve-adoc-link base-dir file-path file-dir clean-path)
                                 (if (str/starts-with? clean-path "/")
                                   (io/file base-dir (subs clean-path 1))
                                   (io/file file-dir clean-path)))]
                  (when-not (.exists resolved)
                    {:type :broken-link
                     :file file-path
                     :message (str "Broken link: " path)
                     :context path}))))
         (remove nil?))))

;; =============================================================================
;; Check: Stale/Pre-split Paths
;; =============================================================================

(defn check-stale-paths [file-path content]
  (let [lines (str/split-lines content)]
    (->> lines
         (map-indexed (fn [idx line]
                        (for [[pattern msg] stale-path-patterns
                              :when (re-find pattern line)]
                          {:type :stale-path
                           :file file-path
                           :line (inc idx)
                           :message msg
                           :context (str/trim line)})))
         (apply concat))))

;; =============================================================================
;; Check: Namespace References
;; =============================================================================

(defn extract-namespace-references [content]
  ;; Look for wagoe.* namespace-like tokens
  (let [pattern #"boundary\.[a-zA-Z0-9._-]+"]
    (->> (re-seq pattern content)
         (distinct))))

(defn check-namespace-references [file-path content known-namespaces]
  (let [lines (str/split-lines content)
        refs (extract-namespace-references content)]
    (->> refs
         ;; Ignore file references and placeholder examples used in docs prose.
         (remove #(or (str/ends-with? % ".adoc")
                      (str/ends-with? % ".md")
                      (str/ends-with? % ".txt")
                      (str/ends-with? % ".")
                      (str/starts-with? % "wagoe.product.")
                      (= % "wagoe.test.fixtures")))
         (remove #(contains? known-namespaces %))
         ;; Also allow partial matches (e.g., wagoe.user matches wagoe.user.core.user)
         (remove (fn [ref]
                   (some #(str/starts-with? % ref) known-namespaces)))
         (map (fn [ns-ref]
                ;; Find line number
                (let [line-num (->> lines
                                    (map-indexed vector)
                                    (filter (fn [[_ line]] (str/includes? line ns-ref)))
                                    (first)
                                    (first))]
                  {:type :unknown-namespace
                   :file file-path
                   :line (when line-num (inc line-num))
                   :message (str "Unknown namespace reference: " ns-ref)
                   :context ns-ref}))))))

;; =============================================================================
;; Check: Command Aliases
;; =============================================================================

(defn extract-clojure-commands [content]
  ;; Match clojure -M:alias1:alias2 patterns
  (let [pattern #"clojure\s+-[MTX]:([a-zA-Z0-9:/_-]+)"]
    (->> (re-seq pattern content)
         (map second))))

(defn parse-aliases-from-command [alias-str]
  ;; Split :foo:bar:baz into [:foo :bar :baz]
  (->> (str/split alias-str #":")
       (remove str/blank?)
       (map keyword)))

(defn check-command-aliases [file-path content known-aliases known-libs]
  (let [lines (str/split-lines content)
        commands (extract-clojure-commands content)]
    (->> commands
         (mapcat (fn [alias-str]
                   (let [aliases (parse-aliases-from-command alias-str)]
                     (->> aliases
                          (remove #(contains? known-aliases %))
                          ;; Special case: :db/h2 style aliases (namespace is "db")
                          (remove #(= (namespace %) "db"))
                          ;; Library keywords like :core, :user etc
                          (remove #(contains? known-libs %))
                          (map (fn [unknown-alias]
                                 (let [line-num (->> lines
                                                     (map-indexed vector)
                                                     (filter (fn [[_ line]] (str/includes? line alias-str)))
                                                     (first)
                                                     (first))]
                                   {:type :unknown-alias
                                    :file file-path
                                    :line (when line-num (inc line-num))
                                    :message (str "Unknown deps.edn alias: " unknown-alias)
                                    :context alias-str})))))))
         (distinct))))

;; =============================================================================
;; Main Linting Logic
;; =============================================================================

(defn lint-file [file-path known-namespaces known-aliases known-libs]
  (try
    (let [base-dir (System/getProperty "user.dir")
          full-path (io/file base-dir file-path)
          content (slurp full-path)]
      (when *verbose*
        (println "  Scanning:" file-path))
      (concat
       (check-internal-links file-path content)
       (check-stale-paths file-path content)
       (check-namespace-references file-path content known-namespaces)
       (check-command-aliases file-path content known-aliases known-libs)))
    (catch Exception e
      [{:type :error
        :file file-path
        :message (str "Error reading file: " (.getMessage e))}])))

(defn run-lint []
  (println "Wagoe Docs Lint")
  (println "==================")
  (println)

  ;; Discover context
  (print "Discovering namespaces...")
  (let [known-namespaces (discover-namespaces)]
    (println " found" (count known-namespaces)))

  (print "Discovering aliases...")
  (let [known-aliases (discover-aliases)]
    (println " found" (count known-aliases) (vec known-aliases)))

  (print "Discovering libraries...")
  (let [known-libs (discover-libraries)]
    (println " found" (count known-libs) (vec known-libs)))

  (print "Discovering doc files...")
  (let [doc-files (discover-doc-files)]
    (println " found" (count doc-files))
    (println)

    (let [known-namespaces (discover-namespaces)
          known-aliases (discover-aliases)
          known-libs (discover-libraries)

          ;; Run all checks
          warnings (->> doc-files
                        (mapcat #(lint-file % known-namespaces known-aliases known-libs))
                        (remove nil?)
                        (vec))

          ;; Group by type
          by-type (group-by :type warnings)

          ;; Summary
          summary {:total (count warnings)
                   :by-type (into {} (map (fn [[k v]] [k (count v)]) by-type))
                   :files-scanned (count doc-files)}

          report {:summary summary
                  :warnings warnings
                  :scanned-files doc-files}]

      ;; Ensure output directory exists
      (let [out-dir (io/file *out-dir*)]
        (.mkdirs out-dir)

        ;; Write EDN report
        (spit (io/file out-dir "report.edn")
              (pr-str report))

        ;; Write text report
        (spit (io/file out-dir "report.txt")
              (with-out-str
                (println "Wagoe Docs Lint Report")
                (println "=========================")
                (println)
                (println "Summary:")
                (println "  Files scanned:" (count doc-files))
                (println "  Total warnings:" (count warnings))
                (println)
                (println "By type:")
                (doseq [[t cnt] (sort-by val > (:by-type summary))]
                  (println (str "  " (name t) ": " cnt)))
                (println)
                (println "Warnings:")
                (doseq [w (take 50 warnings)]
                  (println (str "  [" (name (:type w)) "] " (:file w)
                                (when (:line w) (str ":" (:line w)))
                                " - " (:message w)))))))

      ;; Console summary
      (println "Results:")
      (println "  Files scanned:" (count doc-files))
      (println "  Total warnings:" (count warnings))
      (println)

      (when (seq warnings)
        (println "By type:")
        (doseq [[t cnt] (sort-by val > (:by-type summary))]
          (println (str "  " (name t) ": " cnt)))
        (println)

        (println "Top warnings (max 10):")
        (doseq [w (take 10 warnings)]
          (println (str "  [" (name (:type w)) "] " (:file w)
                        (when (:line w) (str ":" (:line w)))
                        " - " (:message w)))))

      (println)
      (println "Reports written to:" *out-dir*)
      (println "  - report.edn")
      (println "  - report.txt")

      ;; Always exit 0 (warn-only)
      report)))

;; =============================================================================
;; CLI Entry Point
;; =============================================================================

(defn parse-args [args]
  (loop [args args
         opts {}]
    (if (empty? args)
      opts
      (let [[arg & rest] args]
        (cond
          (= arg "--verbose") (recur rest (assoc opts :verbose true))
          (= arg "--out-dir") (recur (drop 1 rest) (assoc opts :out-dir (first rest)))
          :else (recur rest opts))))))

(defn -main [& args]
  (let [opts (parse-args args)]
    (binding [*verbose* (:verbose opts false)
              *out-dir* (:out-dir opts "build/docs-lint")]
      (run-lint))))

;; Run when executed directly
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
