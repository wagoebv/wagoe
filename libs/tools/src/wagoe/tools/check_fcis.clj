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

(def ^:private allowed-require-patterns
  "What a core namespace may require. Anything else is a violation.

   An allowlist rather than a denylist because the denylist could only ever
   name the I/O libraries somebody had thought of. It had eleven entries, and
   a core namespace shelling out through `babashka.process`, holding process
   state in a `core.async` channel, or talking to any datastore client not on
   the list passed clean (BOU-301). The set below is small because the answer
   for a functional core genuinely is small: values in, values out.

   To add one, ask whether it can perform I/O, read a clock, or hold state. If
   it can, it belongs in the shell. If a library is pure but missing here, add
   it with the reasoning — the list is meant to be read."
  [;; Pure data manipulation from Clojure itself. Note the absence of
   ;; clojure.java.io, clojure.java.shell and clojure.core.async.
   #"^clojure\.(string|set|walk|data|edn|zip|math|template)$"
   #"^clojure\.pprint$"                    ; formatting values as strings
   #"^clojure\.spec\.alpha$"
   ;; Schemas are the framework's validation vocabulary and are pure data.
   #"^malli\."
   ;; Hiccup is data-to-HTML — the UI core is built on it (ADR-006).
   #"^hiccup"
   ;; Encoding and hashing over in-memory values.
   #"^cheshire\.core$"
   #"^buddy\.core\."
   ;; SQL as data. Building a query is pure; running it is the shell's job,
   ;; and next.jdbc is deliberately not here.
   #"^honey\.sql"
   ;; Reading and rewriting Clojure source as data — the scaffolder's core.
   #"^rewrite-clj\."
   ;; Integrant's `ref`/`ref?` are data constructors, not a running system.
   #"^integrant\.core$"
   ;; The framework's own pure styling helpers, usable from a downstream app.
   #"^wagoe\.ui-style$"])

(def ^:private own-code-shapes
  "The shapes of a namespace that holds pure code: a core namespace, the
   schemas it validates against, the ports describing its boundaries, and the
   error-code catalogue."
  #"(\.core($|\.)|\.schema($|\.)|\.ports($|\.)|\.error-codes$)")

(defn- own-pure-namespace?
  "Whether `req` is pure code belonging to this project or to the framework.

   Two conditions, and the first is the one that matters: `req` shares its
   top-level segment with the namespace doing the requiring — or is a
   `wagoe.*` namespace, since a generated application's core may use the
   framework's own pure helpers.

   Written first as a bare `\\.core($|\\.)` — \"a module may require its own
   core\" — which is not what it says. `x.core` is the commonest naming
   convention in Clojure, so that pattern admitted `clj-time.core` (a clock),
   `amazonica.core` (AWS), `datomic.core`, `monger.core` and `langohr.core`
   into the functional core, measured. An allowlist has to be anchored to
   something; \"ends in .core\" anchors to nothing."
  [ns-name req]
  (let [root (first (str/split (str ns-name) #"\."))]
    (and (re-find own-code-shapes req)
         (or (str/starts-with? req (str root "."))
             (= req root)
             (str/starts-with? req "wagoe.")))))

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

(def ^:private mutable-state-symbols
  "Mutable-state constructs. Covers the atom/ref/var/volatile/agent families —
   all genuine mutable process state that belongs in the shell, not the
   functional core."
  #{"defonce" "atom" "swap!" "reset!" "compare-and-set!"
    "volatile!" "vreset!" "vswap!"
    "ref" "ref-set" "alter" "commute" "dosync"
    "alter-var-root" "agent" "send" "send-off" "add-watch"})

(def ^:private higher-order-heads
  "Forms that take a function as their first argument, so `(apply swap! …)`
   mutates just as surely as `(swap! …)` does. Matched on the argument as well
   as the head."
  #{"apply" "partial"})

(defn allowed-requires
  "`{ns-name #{required-ns …}}` from the `:allow-require` entries.

   Each entry names one namespace and one require, and must carry a `:why`:
   an exemption nobody can explain cannot be burnt down, and the same rule
   already guards check-ports' allowlist. Both other keys are namespace-wide;
   this one is deliberately per-require, because \"this core namespace may
   require clojure.test\" is a much smaller claim than \"this core namespace
   is exempt\"."
  [m]
  (let [entries (:allow-require m)]
    (doseq [{:keys [ns require why]} entries]
      (when (str/blank? (str ns))
        (throw (ex-info (str "check-fcis allowlist entry has no :ns: " (pr-str entries)) {})))
      (when (str/blank? (str require))
        (throw (ex-info (str "check-fcis allowlist entry for " ns " has no :require.") {:ns ns})))
      (when (str/blank? why)
        (throw (ex-info (str "check-fcis allowlist entry for " ns " has no :why.") {:ns ns}))))
    (reduce (fn [acc {:keys [ns require]}]
              (update acc (str ns) (fnil conj #{}) (str require)))
            {} entries)))

(defn read-config
  "Read the optional .wagoe/check-fcis.edn allowlist. Returns a map with
   :allow-throw and :allow-mutable-state sets (namespace-name string members)
   and :allow-require ({ns #{require}})."
  []
  (let [f (io/file (System/getProperty "user.dir") ".wagoe" "check-fcis.edn")
        empty-config {:allow-throw #{} :allow-mutable-state #{} :allow-require {}}]
    (if (.exists f)
      (let [m (edn/read-string (slurp f))]
        {:allow-throw         (set (map str (:allow-throw m)))
         :allow-mutable-state (set (map str (:allow-mutable-state m)))
         ;; Deliberately outside any try: a malformed allowlist used to be
         ;; swallowed and read as "no exemptions", which turns a typo into a
         ;; gate that reports violations nobody can silence — or, with the
         ;; inverted require list, one that passes for the wrong reason.
         :allow-require       (allowed-requires m)})
      empty-config)))

(defn- ns-meta-flag?
  "True when the namespace symbol in `ns-form` carries the metadata key `k`.
   Recognises the `^:wagoe/allow-throw` form on the ns symbol (as does
   check-ports); the attr-map form `(ns foo {:wagoe/allow-throw true} ...)`
   is not supported — use the reader-metadata form or the .boundary allowlist."
  [ns-form k]
  (boolean (k (meta (second ns-form)))))

;; Scanner limitations (all fail toward more manual review, never silent passes
;; of new code): a `#_`-discarded form is still flagged (strip handles `;`
;; comments and string/regex interiors, not the reader macro), and a mutating
;; function reached through a local binding or a var indirection is not.
;; Use an escape hatch for the rare false positive.

(defn- scan-impurity
  "Scan stripped content for (throw ...) and mutable-state constructs.
   A namespace is exempt from the throw ban via ^:wagoe/allow-throw metadata
   or a .wagoe/check-fcis.edn :allow-throw entry, and from the mutable-state
   ban via ^:wagoe/allow-mutable-state or an :allow-mutable-state entry.
   Returns a seq of {:file :ns :req :line :kind} maps.

   Reads operator position rather than matching lines: `(\\n throw …)` is the
   same call as `(throw …)`, and the regex version scanned one line at a time
   and saw neither it nor `(apply swap! …)` (BOU-301)."
  [file content ns-form ns-name {:keys [allow-throw allow-mutable-state]}]
  (let [calls     (parsing/call-forms (parsing/strip-comments-and-strings content))
        throw-ok? (or (ns-meta-flag? ns-form :wagoe/allow-throw)
                      (contains? (or allow-throw #{}) ns-name))
        mut-ok?   (or (ns-meta-flag? ns-form :wagoe/allow-mutable-state)
                      (contains? (or allow-mutable-state #{}) ns-name))
        called    (fn [{:keys [head arg1]} target?]
                    ;; `(swap! …)` directly, or handed to apply/partial.
                    (or (target? head)
                        (and (higher-order-heads head) arg1 (target? arg1))))]
    (concat
     (when-not throw-ok?
       (for [c calls :when (called c #{"throw"})]
         {:file (str file) :ns ns-name :req "throw" :line (:line c) :kind :throw}))
     (when-not mut-ok?
       (for [c     calls
             :let  [label (if (mutable-state-symbols (:head c)) (:head c) (:arg1 c))]
             :when (called c mutable-state-symbols)]
         {:file (str file) :ns ns-name :req label
          :line (:line c) :kind :mutable-state})))))

(defn- forbidden-require?
  "Whether the core namespace `ns-name` may not require `req`.

   Inverted since BOU-301: anything that is neither a named pure library nor
   this project's own pure code is a violation, so a library nobody
   anticipated fails closed rather than open."
  [ns-name req]
  (not (or (some #(re-find % req) allowed-require-patterns)
           (own-pure-namespace? ns-name req))))

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
     like wagoe/shared/ui/core/)
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
         exempt-reqs        (get (:allow-require config) ns-name #{})
         require-violations (->> requires
                                 (map str)
                                 (filter #(forbidden-require? ns-name %))
                                 (remove exempt-reqs)
                                 (map (fn [req]
                                        {:file (str file)
                                         :ns   ns-name
                                         :req  req
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
