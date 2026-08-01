(ns wagoe.tools.docs-lint
  "Documentation drift linter for Wagoe Framework.
   
   This is a Clojure CLI wrapper for the babashka docs-lint script,
   enabling CI to run docs-lint without requiring babashka installed.
   
   Usage:
     clojure -M:dev -m wagoe.tools.docs-lint
     clojure -M:dev -m wagoe.tools.docs-lint --verbose
     clojure -M:dev -m wagoe.tools.docs-lint --out-dir build/docs-lint
   
   Also available via babashka:
     bb scripts/docs_lint.clj
   
   Output:
     build/docs-lint/report.edn   - structured report
     build/docs-lint/report.txt   - human-readable summary
   
   All findings are warnings (exit code 0)."
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
;; Hand-listing individual lib READMEs left most of the documentation
;; unwatched: `dev-docs/`, CONTRIBUTING.md and 20-odd libs/*/AGENTS.md were
;; never scanned, so the :db/h2 drift (BOU-257) could not have been caught here
;; even with the alias check working. "libs" covers every lib doc as they are
;; added; exclude-patterns still drops READMEs that sit inside code trees.
(def include-patterns
  ["README.md"
   "README.adoc"
   "AGENTS.md"
   "CONTRIBUTING.md"
   "docs"
   "dev-docs"
   "examples"
   "libs"])

;; Patterns to exclude (even if matched by includes)
(def exclude-patterns
  [#".*/src/.*/README.*"   ;; module READMEs inside code trees
   #"^build/.*"
   #".*(^|/)target/.*"     ;; build output, anywhere — was anchored at the repo
                           ;; root only, so libs/*/target/classes/** (stale,
                           ;; pre-rename copies of real docs) was being linted
   #"libs/scaffolder/existing-dir/.*"  ;; scaffolder test fixture, not docs
   #"^\.cpcache/.*"
   #"^\.git/.*"
   #"^node_modules/.*"
   #"^docs/archive/.*"     ;; archived/deprecated docs
   #"^docs/superpowers/.*"    ;; dated plans/specs — a record of what was true
                              ;; then, not instructions to follow now. Linting
                              ;; them would either falsify the record or hold
                              ;; the gate permanently red. Same treatment as
                              ;; CHANGELOG.md in .wagoe/check-no-boundary.edn.
   #"^dev-docs/adr/.*"])      ;; ADRs record decisions, including Proposed ones
                              ;; whose commands do not exist yet by design —
                              ;; ADR-004 documents `clojure -M:backup` under a
                              ;; "Status: Proposed" heading. Flagging that as
                              ;; drift would punish the ADR for being a proposal.

;; Known stale/pre-split path patterns to warn about.
;; NOTE: these keep the pre-rename `boundary` spelling on purpose. They detect
;; the obsolete pre-monorepo-split layout, which used boundary.* namespaces.
;; Post-rename, `src/wagoe/` is the CORRECT layout for a generated app (`wagoe
;; new` puts modules at src/wagoe/<module>/), so matching on `wagoe` would flag
;; correct docs.
(def stale-path-patterns
  [[#"(?<![/a-zA-Z])src/boundary/" "Pre-split path: pre-rename layout; code lives in libs/*/src/wagoe/"]
   [#"(?<![/a-zA-Z])test/boundary/" "Pre-split path: pre-rename layout; tests live in libs/*/test/wagoe/"]
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
          (list-files-recursive (io/file base-dir "dev"))
          (list-files-recursive (io/file base-dir "libs"))
          (list-files-recursive (io/file base-dir "libs/tools/src")))
         (filter #(or (str/ends-with? % ".clj")
                      (str/ends-with? % ".cljc"))))))

(defn extract-namespace [file-path]
  (try
    (let [content (slurp file-path)]
      (when-let [match (re-find #"\(ns\s+(?:\^[^\s]+\s+)*([a-zA-Z0-9._-]+)" content)]
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

;; Aliases that are legitimately absent from deps.edn. Kept as a short, named
;; list with a reason each — NOT as a namespace-wide skip. A blanket "ignore
;; everything under :db/*" is what let BOU-257 through.
(def external-aliases
  {:deps "built into the Clojure CLI itself (clojure -X:deps find-versions)"
   :nvd  "third-party CVE scanner the security checklist tells readers to add to THEIR project"})

(def ^:private project-template-path
  "libs/wagoe-cli/resources/wagoe/cli/templates/deps.edn.tmpl")

(defn discover-template-aliases
  "Aliases a `wagoe new` project gets.

   Most of docs/modules/getting-started and docs/modules/ROOT walk the reader
   through a GENERATED project, not this monorepo — `wagoe new my-app`, `cd
   my-app`, `source .env`. Those commands run against the template's deps.edn,
   which is a different alias set: it defines `:repl` (nREPL on 7888) and has
   no `:repl-clj`. Validating that documentation against the monorepo's aliases
   alone reports correct commands as broken, and 'fixing' them breaks the
   quickstart for every new user.

   The {{version}} placeholders are not valid EDN, so they are substituted with
   a dummy before parsing. Parsing beats scraping keys with a regex here: the
   alias map nests :extra-deps, :extra-paths and friends, and a pattern loose
   enough to catch the alias keys also catches those — which would then count
   as valid aliases and defeat the check."
  []
  (try
    (let [f (io/file (System/getProperty "user.dir") project-template-path)]
      (if-not (.exists f)
        #{}
        (-> (slurp f)
            (str/replace #"\{\{[^}]*\}\}" "0.0.0")
            edn/read-string
            :aliases
            keys
            set)))
    (catch Exception e
      (println "Warning: could not read project template aliases:" (.getMessage e))
      #{})))

(defn discover-aliases []
  (try
    (let [deps-file (io/file (System/getProperty "user.dir") "deps.edn")
          content (slurp deps-file)
          deps (edn/read-string content)]
      (into (into (set (keys (:aliases deps))) (keys external-aliases))
            (discover-template-aliases)))
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
  ;; Look for wagoe.* namespace-like tokens. The rename introduced a collision
  ;; the old `boundary.` prefix never had: wagoe.com (the domain) and file names
  ;; like wagoe.service / wagoe.jar / wagoe.env also start with `wagoe.`, so a
  ;; bare prefix match reports them as unknown namespaces. Drop those suffixes.
  (let [pattern  #"wagoe\.[a-zA-Z0-9._-]+"
        non-ns   #"^wagoe\.(com|org|net|io|dev|app|jar|service|env|edn|clj|cljs|cljc|yml|yaml|json|toml|conf|lua|css|js|md|adoc|sh|xml|png|svg|pdf|csv|log|db|sql|properties)\b"]
    (->> (re-seq pattern content)
         (remove #(re-find non-ns %))
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
                          ;; NOTE no blanket exemption for namespaced aliases.
                          ;; This used to skip everything under the "db"
                          ;; namespace, which is precisely the family that broke
                          ;; (BOU-257): deps.edn dropped :db/h2 and friends for a
                          ;; single :db, and 264 documented commands kept naming
                          ;; the dead alias for months. `clojure -M:test:db/h2`
                          ;; does not fail — it warns and skips every test — so
                          ;; nothing else was going to catch it. A namespaced
                          ;; alias is checkable like any other; if one is
                          ;; genuinely dynamic, exempt that alias by name.
                          ;; Library keywords like :core, :user etc
                          (remove #(contains? known-libs %))
                          ;; Aliases belonging to external example projects (not this repo)
                          (remove #(contains? #{:run} %))
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

(defn failing-warnings
  "The findings in `report` that make this a failing run, as opposed to a
   report of pre-existing debt.

   Only `:unknown-alias` qualifies. It is objectively decidable — the alias is
   in deps.edn or it is not — so there are no false positives to argue with,
   and its failure mode is silent: `clojure -M:test:db/h2` on a dropped alias
   does not error, it warns and skips every test. That is how 264 documented
   commands stayed broken for months (BOU-257). Broken links and unknown
   namespaces stay warn-only; that debt is BOU-253's.

   Exposed separately from `run-lint` so a caller can decide what to do about
   a failure — exit, aggregate, or ignore — without `run-lint` deciding for it."
  [report]
  (filter #(= :unknown-alias (:type %)) (:warnings report)))

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

      ;; Most findings are warn-only: broken links and stale paths are real but
      ;; pre-existing debt (BOU-253 owns them), and failing on those would just
      ;; hold CI red without anyone able to act on it in passing.
      ;;
      ;; `unknown-alias` fails. It is objectively decidable — the alias is in
      ;; deps.edn or it is not — so there are no false positives to argue with,
      ;; and its failure mode is silent: `clojure -M:test:db/h2` on a dropped
      ;; alias does not error, it warns and skips every test. That is how 264
      ;; documented commands stayed broken for months (BOU-257). A warning in a
      ;; report nobody opens was not enough.
      (let [failing (failing-warnings report)]
        (when (seq failing)
          (println)
          (println (str "FAIL: " (count failing)
                        " documented command(s) name a deps.edn alias that does not exist:"))
          (doseq [w failing]
            (println (str "  " (:file w) (when (:line w) (str ":" (:line w)))
                          " — " (:message w))))
          (println)
          (println "Fix the command, or add the alias to deps.edn.")))

      ;; Returns the report and leaves the process alone. Exiting from here
      ;; would tear down the JVM under any programmatic caller — a REPL
      ;; session, or `bb check`, which runs this alongside eleven other checks
      ;; and needs to report all of them. `-main` owns the exit code.
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
      (let [report (run-lint)]
        ;; Explicit exit to ensure clean shutdown
        (shutdown-agents)
        (when (seq (failing-warnings report))
          (System/exit 1))))))
