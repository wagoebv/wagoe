#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/check_fcis.clj
;;
;; FC/IS boundary enforcement: ensures core/ namespaces never import
;; shell code, I/O libraries, logging, or database drivers.
;; See ADR-021 for rationale.

(ns wagoe.tools.check-fcis
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [wagoe.tools.ansi :as ansi]
            [wagoe.tools.parsing :as parsing]))

;; ---------------------------------------------------------------------------
;; Forbidden patterns — any :require in a core/ namespace matching these
;; constitutes an FC/IS violation.
;; ---------------------------------------------------------------------------

(def ^:private forbidden-require-patterns
  "Patterns that must never appear in core namespace :require vectors.
   Covers shell namespaces, I/O, logging, database, HTTP, and caching libraries."
  [#"\.shell\."
   #"^clojure\.tools\.logging$"
   #"^clojure\.java\.io$"
   #"^clojure\.java\.shell$"
   #"^clojure\.java\.jdbc"
   #"^next\.jdbc"
   #"^clj-http"
   #"^org\.httpkit"
   #"^ring\."
   #"^hikari-cp\."
   #"^taoensso\.carmine"])

(def ^:private forbidden-import-packages
  "Java class patterns that must never appear in core namespace :import vectors.
   Targets I/O, database connection, and network access classes.
   Value types like java.sql.Timestamp are allowed (pure type coercion)."
  [#"^java\.sql\.DriverManager$"
   #"^java\.sql\.Connection$"
   #"^java\.sql\.Statement$"
   #"^java\.sql\.PreparedStatement$"
   #"^java\.sql\.CallableStatement$"
   #"^java\.sql\.ResultSet$"
   #"^javax\.sql\."
   #"^java\.net\.http\."
   #"^java\.io\.File$"
   #"^java\.io\.FileInputStream$"
   #"^java\.io\.FileOutputStream$"
   #"^java\.io\.BufferedWriter$"
   #"^java\.io\.BufferedReader$"])

(def ^:private forbidden-fq-patterns
  "Fully-qualified symbol prefixes that must never appear in core namespace bodies.
   Catches calls even without a :require or :import.
   Applied to stripped source (no comments/strings).
   Value types like java.sql.Timestamp are allowed (pure type coercion)."
  [#"clojure\.tools\.logging/"
   #"clojure\.java\.io/"
   #"clojure\.java\.shell/"
   #"next\.jdbc/"
   #"clj-http\.\w+/"
   #"org\.httpkit\.\w+/"
   #"ring\.\w+/"
   #"hikari-cp\.\w+/"
   #"taoensso\.carmine/"
   #"java\.io\.File\b"
   #"java\.io\.FileInputStream"
   #"java\.io\.FileOutputStream"
   #"java\.io\.BufferedWriter"
   #"java\.io\.BufferedReader"
   #"java\.sql\.DriverManager"
   #"java\.sql\.Connection\b"
   #"java\.sql\.Statement\b"
   #"java\.sql\.PreparedStatement"
   #"java\.sql\.CallableStatement"
   #"java\.sql\.ResultSet"
   #"javax\.sql\.\w+"
   #"java\.net\.http\.\w+"
   #"java\.util\.UUID/randomUUID"
   #"java\.time\.Instant/now"
   #"java\.time\.LocalDate/now"
   #"java\.time\.LocalDateTime/now"
   #"java\.time\.OffsetDateTime/now"
   #"java\.time\.ZonedDateTime/now"
   #"java\.time\.ZoneId/systemDefault"
   #"java\.lang\.System/currentTimeMillis"
   #"java\.lang\.System/getProperty"
   #"java\.lang\.ProcessHandle/current"])

(def ^:private forbidden-call-patterns
  "Bare Clojure core function calls that perform I/O and must never
   appear in core namespaces. Matched as (fn-name to ensure they are
   calls, not parts of other symbols. Applied to stripped source."
  [#"\(\s*slurp\s"
   #"\(\s*spit\s"])

(def ^:private forbidden-static-methods-by-class
  "Forbidden static runtime accessors keyed by fully-qualified class name.
   These are checked both in fully-qualified form and in imported/simple form."
  {"java.util.UUID" ["randomUUID"]
   "java.time.Instant" ["now"]
   "java.time.LocalDate" ["now"]
   "java.time.LocalDateTime" ["now"]
   "java.time.OffsetDateTime" ["now"]
   "java.time.ZonedDateTime" ["now"]
   "java.time.ZoneId" ["systemDefault"]
   "java.lang.System" ["currentTimeMillis" "getProperty"]
   "java.lang.ProcessHandle" ["current"]})

(def ^:private default-static-class-aliases
  "Java classes available without an explicit :import that still expose
   forbidden runtime accessors in core namespaces."
  {"System" "java.lang.System"
   "ProcessHandle" "java.lang.ProcessHandle"})

(def ^:private allowed-fq-violations
  "Temporary BOU-15 allowlist for known remaining runtime-dependent core calls.
   Each entry should be removed as the corresponding namespace is migrated."
  [])

;; ---------------------------------------------------------------------------
;; Impurity patterns — (throw ...) and mutable process state in core bodies.
;; Core functions must be pure: return typed error values (the shell translates
;; them into HTTP responses) and hold no mutable state (registries live in the
;; shell). Applied to stripped source, so throws inside string literals — e.g.
;; a code-generator emitting a `(throw ...)` template — are ignored.
;; ---------------------------------------------------------------------------

(def ^:private symbol-tail
  "Negative lookahead marking the end of a Clojure symbol. A plain \\b fails
   after `!` (both `!` and the following space are non-word characters, so
   there is no word boundary between them), so match the delimiter explicitly."
  "(?![\\w!?*+'-])")

(def ^:private throw-pattern
  "A (throw ...) call in a core namespace body."
  (re-pattern (str "\\(\\s*throw" symbol-tail)))

(def ^:private mutable-state-patterns
  "Mutable-state constructs keyed by the label reported in the violation.
   Covers the atom/ref/var/volatile/agent families — all genuine mutable
   process state that belongs in the shell, not the functional core."
  (into {}
        (map (fn [sym]
               [sym (re-pattern (str "\\(\\s*" (java.util.regex.Pattern/quote sym) symbol-tail))]))
        ["defonce" "atom" "swap!" "reset!" "compare-and-set!"
         "volatile!" "vreset!" "vswap!"
         "ref" "ref-set" "alter" "commute" "dosync"
         "alter-var-root" "agent" "send" "send-off" "add-watch"]))

(defn read-config
  "Read the optional .wagoe/check-fcis.edn allowlist. Returns a map with
   :allow-throw and :allow-mutable-state sets (namespace-name string members)."
  []
  (let [f (io/file (System/getProperty "user.dir") ".wagoe" "check-fcis.edn")]
    (if (.exists f)
      (try
        (let [m (edn/read-string (slurp f))]
          {:allow-throw         (set (map str (:allow-throw m)))
           :allow-mutable-state (set (map str (:allow-mutable-state m)))})
        (catch Exception _
          {:allow-throw #{} :allow-mutable-state #{}}))
      {:allow-throw #{} :allow-mutable-state #{}})))

(defn- ns-meta-flag?
  "True when the namespace symbol in `ns-form` carries the metadata key `k`.
   Recognises the `^:wagoe/allow-throw` form on the ns symbol (as does
   check-ports); the attr-map form `(ns foo {:wagoe/allow-throw true} ...)`
   is not supported — use the reader-metadata form or the .boundary allowlist."
  [ns-form k]
  (boolean (k (meta (second ns-form)))))

;; Scanner limitations (all fail toward more manual review, never silent passes
;; of new code): the impurity scan is line-based over stripped source, so a head
;; symbol split from its `(` by a newline, and higher-order use like
;; `(apply swap! ...)`, are not flagged; a `#_`-discarded form is still flagged
;; (strip handles `;` comments and string/regex interiors, not the reader macro).
;; Use an escape hatch for the rare false positive.

(defn- scan-impurity
  "Scan stripped content for (throw ...) and mutable-state constructs.
   A namespace is exempt from the throw ban via ^:wagoe/allow-throw metadata
   or a .wagoe/check-fcis.edn :allow-throw entry, and from the mutable-state
   ban via ^:wagoe/allow-mutable-state or an :allow-mutable-state entry.
   Returns a seq of {:file :ns :req :line :kind} maps."
  [file content ns-form ns-name {:keys [allow-throw allow-mutable-state]}]
  (let [cleaned   (parsing/strip-comments-and-strings content)
        lines     (str/split-lines cleaned)
        throw-ok? (or (ns-meta-flag? ns-form :wagoe/allow-throw)
                      (contains? (or allow-throw #{}) ns-name))
        mut-ok?   (or (ns-meta-flag? ns-form :wagoe/allow-mutable-state)
                      (contains? (or allow-mutable-state #{}) ns-name))]
    (->> lines
         (map-indexed
          (fn [idx line]
            (concat
             (when (and (not throw-ok?) (re-find throw-pattern line))
               [{:file (str file) :ns ns-name :req "throw"
                 :line (inc idx) :kind :throw}])
             (when-not mut-ok?
               (keep (fn [[label pat]]
                       (when (re-find pat line)
                         {:file (str file) :ns ns-name :req label
                          :line (inc idx) :kind :mutable-state}))
                     mutable-state-patterns)))))
         (mapcat identity))))

(defn- forbidden-require?
  "Returns true if `ns-str` matches any forbidden require pattern."
  [ns-str]
  (some #(re-find % ns-str) forbidden-require-patterns))

;; ---------------------------------------------------------------------------
;; File scanning
;; ---------------------------------------------------------------------------

(defn- find-core-dirs
  "Recursively find all directories named 'core' under a root directory."
  [root]
  (->> (file-seq root)
       (filter #(and (.isDirectory %) (= "core" (.getName %))))))

(defn- core-clj-files-under
  "All .clj files under any core/ directory beneath `dir` (nil if dir absent)."
  [dir]
  (when (and dir (.exists dir))
    (->> (find-core-dirs dir)
         (mapcat file-seq)
         (filter #(and (.isFile %)
                       (str/ends-with? (.getName %) ".clj"))))))

(defn core-source-paths
  "Find all .clj files under any core/ directory that must be subject to
   FC/IS enforcement. Covers:
   - libs/*/src/wagoe/<lib>/core/ (monorepo lib layout, and non-standard libs
     like boundary/shared/ui/core/)
   - src/**/core/ (the application layout — a project scaffolded with
     `wagoe new` puts modules at src/wagoe/<module>/core/, with no libs/)
   - src/wagoe/test_support/core.clj (monorepo-level shared test helpers)

   Public so it can be exercised from tests. The 1-arity takes an explicit
   project root (a File or path string) for testing against fixtures."
  ([] (core-source-paths (System/getProperty "user.dir")))
  ([root-path]
   (let [root          (io/file root-path)
         libs          (io/file root "libs")
         libs-files    (when (.exists libs)
                         (->> (.listFiles libs)
                              (filter #(.isDirectory %))
                              (mapcat (fn [lib-dir]
                                        (core-clj-files-under (io/file lib-dir "src"))))))
         ;; Application layout: a generated project has its modules under
         ;; src/wagoe/<module>/core/ and no libs/ tree. Scan the project's own
         ;; src/ so `bb check:fcis` (e.g. the generated pre-commit hook) actually
         ;; inspects scaffolded core namespaces. Harmless in the monorepo, whose
         ;; root src/ has no core/ directories.
         app-files     (core-clj-files-under (io/file root "src"))
         ;; src/wagoe/test_support/core.clj is the monorepo-level shared
         ;; test helper namespace. It is a single file (wagoe.test-support.core),
         ;; not a directory of core sources — include it explicitly.
         test-support-file (io/file root "src" "wagoe" "test_support" "core.clj")
         test-support  (when (.exists test-support-file) [test-support-file])]
     (->> (concat libs-files app-files test-support)
          (distinct)))))

(defn- core-clj-files
  "Backwards-compatible alias for core-source-paths."
  []
  (core-source-paths))

(defn- extract-requires
  "Extract required namespace symbols from a (ns ...) form."
  [ns-form]
  (when ns-form
    (let [require-clause (->> ns-form
                              (filter #(and (sequential? %) (= :require (first %))))
                              first)]
      (when require-clause
        (->> (rest require-clause)
             (map #(cond
                     (symbol? %) %
                     (vector? %) (first %)
                     :else nil))
             (remove nil?))))))

(defn- extract-imports
  "Extract imported class names (fully qualified) from a (ns ...) form.
   Handles both vector and list import syntax:
     (:import [java.sql DriverManager Connection])
     (:import (java.sql DriverManager))"
  [ns-form]
  (when ns-form
    (let [import-clause (->> ns-form
                             (filter #(and (sequential? %) (= :import (first %))))
                             first)]
      (when import-clause
        (->> (rest import-clause)
             (mapcat (fn [spec]
                       (cond
                         ;; [java.sql DriverManager Connection] or (java.sql DriverManager)
                         (sequential? spec)
                         (let [pkg (str (first spec))]
                           (map #(str pkg "." %) (rest spec)))
                         ;; bare class symbol: java.sql.DriverManager
                         (symbol? spec) [(str spec)]
                         :else nil)))
             (remove nil?))))))

(defn- forbidden-import?
  "Returns true if a fully-qualified class name matches any forbidden import pattern."
  [class-str]
  (some #(re-find % class-str) forbidden-import-packages))

(defn- allowed-fq-violation?
  [file req]
  (some (fn [{file-pattern :file
              req-pattern  :req}]
          (and (re-find file-pattern file)
               (re-find req-pattern req)))
        allowed-fq-violations))

(defn- imported-static-class-aliases
  "Resolve simple class names available in the file to their fully-qualified
   names for forbidden static accessor checks."
  [imports]
  (merge default-static-class-aliases
         (->> imports
              (filter #(contains? forbidden-static-methods-by-class %))
              (map (fn [class-name]
                     [(last (str/split class-name #"\."))
                      class-name]))
              (into {}))))

(defn- scan-simple-static-calls
  "Scan stripped file content for forbidden runtime access via imported or
   implicitly available simple class names such as (Instant/now) or
   (System/currentTimeMillis)."
  [file content imports]
  (let [cleaned        (parsing/strip-comments-and-strings content)
        lines          (str/split-lines cleaned)
        class-aliases  (imported-static-class-aliases imports)]
    (->> lines
         (map-indexed
          (fn [idx line]
            (mapcat (fn [[alias fq-class]]
                      (keep (fn [method-name]
                              (when (re-find (re-pattern (str "\\(\\s*"
                                                              (java.util.regex.Pattern/quote alias)
                                                              "/"
                                                              (java.util.regex.Pattern/quote method-name)
                                                              "\\b"))
                                             line)
                                {:file   (str file)
                                 :line   (inc idx)
                                 :symbol (str fq-class "/" method-name)}))
                            (get forbidden-static-methods-by-class fq-class)))
                    class-aliases)))
         (mapcat identity))))

(defn- scan-fq-calls
  "Scan stripped file content for fully-qualified forbidden calls and
   bare I/O function calls (slurp, spit).
   Reports ALL violations per line (not just the first match).
   Returns a seq of {:file :line :symbol} maps."
  [file content]
  (let [cleaned (parsing/strip-comments-and-strings content)
        lines   (str/split-lines cleaned)
        all-patterns (concat forbidden-fq-patterns forbidden-call-patterns)]
    (->> lines
         (map-indexed
          (fn [idx line]
            (keep (fn [pat]
                    (when-let [m (re-find pat line)]
                      {:file   (str file)
                       :line   (inc idx)
                       :symbol (str/replace
                                (str/replace (str/trim m) #"^[(/\s]+" "")
                                #"/$" "")}))
                  all-patterns)))
         (mapcat identity))))

(defn check-file
  "Check a single core/ .clj file for forbidden requires, imports,
   fully-qualified forbidden calls, bare I/O calls, (throw ...) calls, and
   mutable state in the body.
   Returns a seq of violation maps {:file :ns :req :kind [:line]}, or empty seq
   if clean. Public so callers (e.g. the wagoe-mcp verify loop) can check an
   arbitrary core file outside the monorepo's `core-source-paths` discovery.
   The 1-arity reads the .wagoe/check-fcis.edn allowlist itself."
  ([file] (check-file file (read-config)))
  ([file config]
   (let [content  (slurp file)
         ns-form  (parsing/read-ns-form file)
         ns-name  (str (second ns-form))
         requires (extract-requires ns-form)
         imports  (extract-imports ns-form)
         require-violations (->> requires
                                 (filter #(forbidden-require? (str %)))
                                 (map (fn [req]
                                        {:file (str file)
                                         :ns   ns-name
                                         :req  (str req)
                                         :kind :require})))
         import-violations  (->> imports
                                 (filter forbidden-import?)
                                 (map (fn [cls]
                                        {:file (str file)
                                         :ns   ns-name
                                         :req  cls
                                         :kind :import})))
         fq-violations (->> (concat (scan-fq-calls file content)
                                    (scan-simple-static-calls file content imports))
                            (remove (fn [{:keys [symbol]}]
                                      (allowed-fq-violation? (str file) symbol)))
                            (map (fn [{:keys [line symbol]}]
                                   {:file (str file)
                                    :ns   ns-name
                                    :req  symbol
                                    :line line
                                    :kind :fq-call})))
         impurity-violations (scan-impurity file content ns-form ns-name config)]
     (concat require-violations import-violations fq-violations
             impurity-violations))))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn -main [& _args]
  (let [config     (read-config)
        files      (core-clj-files)
        violations (mapcat #(check-file % config) files)]
    (if (seq violations)
      (do
        (println (ansi/red "FC/IS violations found:"))
        (println)
        (doseq [{:keys [file ns req kind line]} violations]
          (case kind
            :fq-call
            (do (println (str "  VIOLATION: " file ":" line))
                (println (str "    namespace " ns " calls " (ansi/red req))))
            :throw
            (do (println (str "  VIOLATION: " file ":" line))
                (println (str "    namespace " ns " uses " (ansi/red "(throw ...)")
                              " — core must return typed error values")))
            :mutable-state
            (do (println (str "  VIOLATION: " file ":" line))
                (println (str "    namespace " ns " uses mutable state " (ansi/red req)
                              " — registries/state belong in the shell")))
            :import
            (do (println (str "  VIOLATION: " file))
                (println (str "    namespace " ns " imports " (ansi/red req))))
            :require
            (do (println (str "  VIOLATION: " file))
                (println (str "    namespace " ns " requires " (ansi/red req))))))
        (println)
        (println (str (count violations) " violation(s) found. Core namespaces must not import shell, I/O, logging, or DB code, throw, or hold mutable state."))
        (println (ansi/dim "Escape hatch: ^:wagoe/allow-throw / ^:wagoe/allow-mutable-state ns metadata, or .wagoe/check-fcis.edn allowlist."))
        (System/exit 1))
      (do
        (println (ansi/green "FC/IS check passed.") (str (count files) " core file(s) scanned, 0 violations."))
        (System/exit 0)))))
