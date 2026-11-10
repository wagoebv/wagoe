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
            [wagoe.tools.check-isolation :as check-isolation]
            [wagoe.tools.check-ports :as check-ports]
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
   ;; SQL as data. Building a query is pure, so purity lets it through; the
   ;; SQL rule (sql-namespace-patterns) refuses it unless :allow-sql exempts
   ;; the file. next.jdbc is not here: it runs queries.
   #"^honey\.sql"
   ;; Reading and rewriting Clojure source as data — the scaffolder's core.
   #"^rewrite-clj\."
   ;; Integrant's `ref`/`ref?` are data constructors, not a running system.
   #"^integrant\.core$"
   ;; The framework's own pure styling helpers, usable from a downstream app.
   #"^wagoe\.ui-style$"])

(def ^:private pure-layers
  "The segments naming a layer that holds pure code: core, the schemas it
   validates against, the ports describing its boundaries, and the error-code
   catalogue."
  #{"core" "schema" "ports" "error-codes"})

(defn- pure-layer?
  "Whether the first layer segment of `req` after its root is a pure one.
   `wagoe.x.shell.core` ends in `.core` but lives in the shell; a `shell`
   further down, as in `wagoe.shared.ui.core.shell`, is inside core."
  [req]
  (let [layer (->> (rest (str/split req #"\."))
                   (filter (conj pure-layers "shell"))
                   first)]
    (boolean (pure-layers layer))))

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
    (and (pure-layer? req)
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
   #"java\.lang\.System/getenv"
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
   ;; getenv reads the process environment, which is exactly the ambient input
   ;; a pure function must not have. wagoe.core.config.feature-flags had five
   ;; convenience arities calling it, in a namespace whose own docstring
   ;; promised "no side effects" (BOU-302).
   "java.lang.System" ["currentTimeMillis" "getProperty" "getenv"]
   "java.lang.ProcessHandle" ["current"]})

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

(defn- require-keys!
  "Throws when an allowlist entry lacks one of `ks`."
  [label entries ks]
  (doseq [e entries, k ks :when (str/blank? (str (get e k)))]
    (throw (ex-info (str "check-fcis " label " entry " (pr-str e) " has no " k ".")
                    {:entry e}))))

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
    (require-keys! ":allow-require" entries [:ns :require :why])
    (reduce (fn [acc {:keys [ns require]}]
              (update acc (str ns) (fnil conj #{}) (str require)))
            {} entries)))

(defn allowed-sql
  "The `:allow-sql` entries, checked: each needs a `:path` and a `:why`.

   Keyed by path, not namespace: platform-sql owns `wagoe.platform.*`
   namespaces, so a prefix would exempt all of platform with it. A path ending
   in / covers the directory."
  [m]
  (let [entries (:allow-sql m)]
    (require-keys! ":allow-sql" entries [:path :why])
    entries))

(def ^:private warning-allow-keys
  "The config key that exempts a namespace from each warning kind."
  {:print :allow-print, :clock :allow-clock})

(defn allowed-warnings
  "The `:allow-print` and `:allow-clock` entries, checked: each needs an `:ns`
   and a `:why`. Returns `{key #{ns-name}}`."
  [m]
  (into {} (for [k (vals warning-allow-keys)
                 :let [entries (get m k)]]
             (do (require-keys! (str k) entries [:ns :why])
                 [k (set (map (comp str :ns) entries))]))))

(defn read-config
  "Read the optional .wagoe/check-fcis.edn allowlist. Returns a map with
   :allow-throw and :allow-mutable-state sets (namespace-name string members),
   :allow-require ({ns #{require}}), :allow-sql ([{:path :why}]) and
   :allow-print and :allow-clock sets."
  []
  (let [f (io/file (System/getProperty "user.dir") ".wagoe" "check-fcis.edn")
        empty-config (merge (allowed-warnings {})
                            {:allow-throw #{} :allow-mutable-state #{} :allow-require {}
                             :allow-sql []})]
    (if (.exists f)
      (let [m (edn/read-string (slurp f))]
        (merge
         (allowed-warnings m)
         {:allow-throw         (set (map str (:allow-throw m)))
          :allow-mutable-state (set (map str (:allow-mutable-state m)))
          ;; Deliberately outside any try: a malformed allowlist used to be
          ;; swallowed and read as "no exemptions", which turns a typo into a
          ;; gate that reports violations nobody can silence — or, with the
          ;; inverted require list, one that passes for the wrong reason.
          :allow-require       (allowed-requires m)
          :allow-sql           (allowed-sql m)}))
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

(defn- core-names [& names]
  (into #{} (mapcat (juxt identity #(str "clojure.core/" %))) names))

(def ^:private error-heads
  "Forms whose function argument the throw and mutable-state errors read, so
   `(apply swap! …)` mutates just as surely as `(swap! …)` does. Kept to these
   two in 1.x: an injected callback named `send` or `alter` handed to `run!`
   passed before, and a minor must not fail it."
  #{"apply" "partial"})

(def ^:private higher-order-heads
  "Forms that take a function as their first argument, read by the print and
   clock warnings: `(run! println xs)` prints."
  (core-names "apply" "partial" "comp" "juxt" "run!" "map" "mapv" "mapcat"
              "map-indexed" "pmap" "keep" "keep-indexed" "filter" "filterv"
              "remove" "some" "every?" "reduce" "reduce-kv" "sort-by" "group-by"
              "iterate" "repeatedly"))

(def ^:private out-symbols
  "Calls that write to *out*, bare or `clojure.core/`-qualified."
  (core-names "print" "println" "prn" "pr" "printf" "newline"))

(def ^:private tap-symbols (core-names "tap>"))

(defn- pprint-symbols
  "`pprint` and `print-table` as `ns-form` can name them: qualified, through
   an :as alias, or referred. clojure.pprint is allowed in core for formatting
   to a string, but these two write to *out*."
  [ns-form]
  (let [names ["pprint" "print-table"]
        specs (->> ns-form
                   (filter #(and (sequential? %) (= :require (first %))))
                   (mapcat rest)
                   (filter #(and (vector? %) (= 'clojure.pprint (first %))))
                   (map #(into {} (map vec (partition 2 (rest %))))))]
    (set (concat (for [n names] (str "clojure.pprint/" n))
                 (for [{a :as} specs :when a, n names] (str a "/" n))
                 (for [{r :refer} specs :when (sequential? r)
                       n names :when (some #{(symbol n)} r)]
                   n)))))

(def ^:private repeatedly-heads
  "`(repeatedly n f)` takes its function second."
  (core-names "repeatedly"))

(defn- called
  "The `sym` in `targets` that `c` calls: `(sym …)` directly, `sym` handed to
   one of `heads` (apply, map, run! …), or `(repeatedly n sym)` when
   repeatedly is one of them."
  [{:keys [head arg1 arg2]} targets heads]
  (or (targets head)
      (and (heads head) arg1 (targets arg1))
      (and (heads head) (repeatedly-heads head) arg2 (targets arg2))))

(def ^:private threading-heads
  "Forms that call a bare symbol in a step: `(-> x println)` is
   `(println x)`. Listed rather than treating any reference as a use: a text
   scan cannot tell a local that shadows `print` from the real one, and a
   threading step is a call whatever the symbol means."
  (core-names "->" "->>" "some->" "some->>" "cond->" "cond->>" "doto"))

(def ^:private cond-threading-heads
  "Threading forms with a test before each step."
  (core-names "cond->" "cond->>"))

(defn- threaded
  "{:req :line} for each step of the threading form `c` that is a bare symbol
   in `targets`; `:req` is what `targets` returns for it."
  [stripped targets {:keys [head pos end pos-line]}]
  (when (and end (threading-heads head))
    (let [steps (drop 2 (parsing/form-children stripped pos end pos-line))]
      (for [{:keys [token line]} (if (cond-threading-heads head) (take-nth 2 (rest steps)) steps)
            :let [req (targets token)] :when req]
        {:req req :line line}))))

(defn- uses
  "{:req :line} for each place `c` calls a symbol in `targets`: `called`, or
   a threading step. The print and clock warnings read these."
  [stripped targets c]
  (concat (when-let [req (called c targets higher-order-heads)] [{:req req :line (:line c)}])
          (threaded stripped targets c)))

;; Clock and randomness: the shell reads them and passes the value in. The
;; accessors in forbidden-static-methods-by-class (Instant/now,
;; UUID/randomUUID, System/currentTimeMillis) were errors already and stay so;
;; these are warnings in 1.x, like print.

(def ^:private clock-fns
  (core-names "rand" "rand-int" "rand-nth" "shuffle" "random-sample" "random-uuid"))

(def ^:private clock-statics
  {"java.lang.System"     ["nanoTime"]
   "java.lang.Math"       ["random"]
   "java.lang.StrictMath" ["random"]
   "java.time.LocalTime"  ["now"]
   "java.time.OffsetTime" ["now"]
   "java.time.Year"       ["now"]
   "java.time.YearMonth"  ["now"]
   "java.time.MonthDay"   ["now"]
   "java.time.Clock"      ["systemUTC" "systemDefaultZone" "system"]
   "java.util.Calendar"   ["getInstance"]
   "java.util.concurrent.ThreadLocalRandom" ["current"]})

(def ^:private clock-constructors
  "Classes whose no-argument constructor reads the clock or a random seed."
  #{"java.util.Date" "java.util.GregorianCalendar" "java.util.Random"
    "java.security.SecureRandom"})

(defn- class-names
  "The names `fq` goes by in a file importing `imports`: itself, and its
   simple name when imported or in java.lang."
  [imports fq]
  (cond-> #{fq}
    (or (some #{fq} imports) (re-find #"^java\.lang\.[A-Z]" fq))
    (conj (last (str/split fq #"\.")))))

(defn- clock-symbols [imports]
  (into clock-fns
        (for [[fq methods] clock-statics, n (class-names imports fq), m methods]
          (str n "/" m))))

(defn- clock-constructor-names [imports]
  (set (mapcat #(class-names imports %) clock-constructors)))

(defn- clock-constructor
  "`(C.)` or `(new C)` with no argument, for C in `ctor-names`, as written;
   nil otherwise. Read from the unstripped `content`, whose offsets match the
   stripped text's, so `(Date. \"1995\")` keeps its argument."
  [content ctor-names {:keys [head arg1 pos end]}]
  (let [cls   (if (= "new" head) arg1 (when (str/ends-with? head ".") (subs head 0 (dec (count head)))))
        label (if (= "new" head) (str "new " cls) head)]
    (when (and end (ctor-names cls)
               (= label (str/replace (str/trim (subs content (inc pos) end)) #"\s+" " ")))
      label)))

(defn- static-targets
  "`{simple-name fq-name}` for the forbidden static calls a file importing
   `imports` can write without the package: `Instant/now`."
  [imports]
  (into {} (for [[fq ms] forbidden-static-methods-by-class
                 n (class-names imports fq) :when (not= n fq), m ms]
             [(str n "/" m) (str fq "/" m)])))

(defn- static-call-warnings
  "{:req :line} for each forbidden static call in `c` that the line regex of
   scan-simple-static-calls misses: `(\\n Instant/now)`, `(repeatedly 3
   UUID/randomUUID)`, `(-> x Instant/now)`. What the regex finds stays an
   error; these are warnings until 2.0, when the regex goes and they join it.
   Fully-qualified names need nothing here: scan-fq-calls finds them anywhere."
  [stripped targets {:keys [head pos end] :as c}]
  (if (and (targets head)
           ;; What the regex sees: the head on the paren's line, no comma before it.
           (-> (re-matcher #"\([ \t\x0B\f]*[^\s,]" stripped)
               (.region (int pos) (int (or end (count stripped))))
               (.lookingAt)))
    (threaded stripped targets c)
    (uses stripped targets c)))

(defn- scan-impurity
  "Scan stripped content for (throw ...), mutable-state constructs and print
   calls. A namespace is exempt from the throw ban via ^:wagoe/allow-throw
   metadata or a .wagoe/check-fcis.edn :allow-throw entry, and from the
   mutable-state ban via ^:wagoe/allow-mutable-state or an :allow-mutable-state
   entry. Returns a seq of {:file :ns :req :line :kind [:severity]} maps.

   Print is a warning in 1.x and an error in 2.0: the gate runs in generated
   projects too, and a minor must not fail them. Inside `with-out-str` the
   output is captured and returned, which is pure, so those do not count;
   `tap>` is not captured by it. Nothing inside a `(comment …)` block runs.

   Clock and randomness reads are `:clock` warnings on the same terms, and so
   is a forbidden static call only this scan finds (static-call-warnings).
   Both, and print, also count as a step of `->`, `doto` and the like.
   ^:wagoe/allow-print and ^:wagoe/allow-clock exempt a namespace from each;
   their config entries are applied in check-file, so stale ones can be found.

   Reads operator position rather than matching lines: `(\\n throw …)` is the
   same call as `(throw …)`, and the regex version scanned one line at a time
   and saw neither it nor `(apply swap! …)` (BOU-301)."
  [file content ns-form ns-name imports {:keys [allow-throw allow-mutable-state]}]
  (let [stripped  (parsing/strip-comments-and-strings content)
        calls     (parsing/call-forms stripped)
        clock     (clock-symbols imports)
        statics   (static-targets imports)
        ctors     (clock-constructor-names imports)
        throw-ok? (or (ns-meta-flag? ns-form :wagoe/allow-throw)
                      (contains? (or allow-throw #{}) ns-name))
        mut-ok?   (or (ns-meta-flag? ns-form :wagoe/allow-mutable-state)
                      (contains? (or allow-mutable-state #{}) ns-name))
        ranges    (fn [heads]
                    (for [c calls :when (heads (:head c))]
                      [(:pos c) (:end c Long/MAX_VALUE)]))
        inside?   (fn [rs {:keys [pos]}] (some (fn [[s e]] (< s pos e)) rs))
        comments  (ranges #{"comment" "clojure.core/comment"})
        captured  (concat comments (ranges #{"with-out-str" "clojure.core/with-out-str"}))
        finding   (fn [req kind line]
                    {:file (str file) :ns ns-name :line line :kind kind :req req})
        warning   (fn [kind {:keys [req line]}]
                    (assoc (finding req kind line) :severity :warning))]
    (concat
     (when-not throw-ok?
       (for [c calls :let [req (called c #{"throw"} error-heads)] :when req]
         (finding req :throw (:line c))))
     (when-not mut-ok?
       (for [c calls :let [req (called c mutable-state-symbols error-heads)] :when req]
         (finding req :mutable-state (:line c))))
     (when-not (ns-meta-flag? ns-form :wagoe/allow-print)
       (for [[targets skip] [[(into out-symbols (pprint-symbols ns-form)) captured]
                             [tap-symbols comments]]
             c calls :when (not (inside? skip c))
             u (uses stripped targets c)]
         (warning :print u)))
     (when-not (ns-meta-flag? ns-form :wagoe/allow-clock)
       (for [c calls :when (not (inside? comments c))
             u (concat (when-let [ctor (clock-constructor content ctors c)]
                         [{:req ctor :line (:line c)}])
                       (uses stripped clock c))]
         (warning :clock u)))
     (for [c calls :when (not (inside? comments c))
           u (static-call-warnings stripped statics c)]
       (warning :fq-call u)))))

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

(defn ports-source-paths
  "Every ports.clj, and every .clj under a ports/ directory, in libs/*/src and
   the project's own src/."
  []
  (->> (check-ports/source-roots)
       (mapcat file-seq)
       (filter #(and (.isFile %)
                     (str/ends-with? (.getName %) ".clj")
                     (or (= "ports.clj" (.getName %))
                         (= "ports" (.getName (.getParentFile %))))))))

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
  (into {} (for [fq (keys forbidden-static-methods-by-class)
                 n  (class-names imports fq) :when (not= n fq)]
             [n fq])))

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

;; ---------------------------------------------------------------------------
;; core/ and ports know no SQL. Building a HoneySQL map is pure, so the rules
;; above let it through, but it ties the functional core to one kind of store,
;; and a SQL type in a port signature rules out any other store behind it. An
;; opaque handle (`tx`, `conn`) may still pass through a port, untyped.
;; ---------------------------------------------------------------------------

(def ^:private sql-namespace-patterns
  "Namespaces that tie code to a SQL store."
  [#"^honey\.sql"
   #"^next\.jdbc"
   #"^wagoe\.platform\.database$"
   #"^wagoe\.platform\.ports\.database$"])

(def ^:private sql-type-pattern
  "Words in a port that type an argument or return as SQL."
  #"(?i)honey-?\.?sql|next\.jdbc|javax\.sql\.|java\.sql\.(?:Connection|Statement|PreparedStatement|CallableStatement|ResultSet|DriverManager)\b")

(defn- sql-require? [req]
  (some #(re-find % req) sql-namespace-patterns))

(def ^:private project-root
  (delay (str (str/replace (.getCanonicalPath (io/file (System/getProperty "user.dir"))) "\\" "/") "/")))

(defn- rel-path
  "`file` relative to the project root, with / separators."
  [file]
  (let [path (str/replace (.getCanonicalPath (io/file file)) "\\" "/")]
    (cond-> path (str/starts-with? path @project-root) (subs (count @project-root)))))

(defn- covers? [{:keys [path]} {rel :path}]
  (and rel (or (= rel path)
               (and (str/ends-with? path "/") (str/starts-with? rel path)))))

(defn- blank-ns-form
  "`content` with `ns-text` spaced out, newlines kept, so a scan of the body
   does not see the requires again."
  [content ns-text]
  (cond-> content
    ns-text (str/replace-first ns-text (str/replace ns-text #"[^\n]" " "))))

(defn- sql-type-hits
  "{:line :req} for each SQL type named in `text`, which starts on `line`."
  [text line]
  (keep-indexed (fn [idx l]
                  (when-let [m (re-find sql-type-pattern l)]
                    {:line (+ line idx) :req m}))
                (str/split-lines text)))

(defn- finding-base [file ns-name]
  {:file (str file) :path (rel-path file) :ns ns-name :severity :error})

(defn- sql-require-findings
  "One :sql-require error per SQL namespace in `requires`."
  [base requires]
  (for [req requires :when (sql-require? req)]
    (assoc base :req req :kind :sql-require)))

(defn sql-port-findings
  "SQL coupling in a ports file, before the allowlist.

   A SQL require, or a SQL type named in code (an arglist, mostly), is an
   error. In a docstring it is a warning: prose is fuzzy, and \"a transaction
   of the store\" is fine where \"a next.jdbc transaction\" is not."
  [file]
  (let [content (slurp file)
        ns-text (parsing/extract-ns-form-text content)
        ns-form (parsing/read-ns-form file)
        base    (finding-base file (str (second ns-form)))
        body    (blank-ns-form content ns-text)]
    (concat
     (sql-require-findings base (map str (extract-requires ns-form)))
     (for [cls (extract-imports ns-form) :when (re-find sql-type-pattern cls)]
       (assoc base :req cls :kind :sql-require))
     (for [hit (sql-type-hits (check-isolation/code-only body) 1)]
       (merge base hit {:kind :sql-signature}))
     (for [{:keys [text line regex?]} (parsing/string-literals content)
           :when (not regex?)
           hit   (sql-type-hits text line)]
       (merge base hit {:kind :sql-doc :severity :warning})))))

(def ^:private sql-kinds #{:sql-require :sql-signature :sql-doc})

(defn- sql-allowed? [config finding]
  (and (sql-kinds (:kind finding))
       (some #(covers? % finding) (:allow-sql config))))

(defn stale-sql-entries
  "The `:allow-sql` entries that cover none of `findings`. An exemption that
   exempts nothing is reported, so the burn-down cannot outlive the fix."
  [config findings]
  (remove (fn [entry] (some #(covers? entry %) findings))
          (:allow-sql config)))

(defn stale-warning-entries
  "`[key ns-name]` for each `:allow-print` or `:allow-clock` entry whose
   namespace has no such warning in `findings`."
  [config findings]
  (let [seen (set (map (juxt :kind :ns) findings))]
    (for [[kind k] warning-allow-keys, ns-name (get config k)
          :when (not (seen [kind ns-name]))]
      [k ns-name])))

(defn- allowed? [config finding]
  (or (sql-allowed? config finding)
      (contains? (get config (warning-allow-keys (:kind finding))) (:ns finding))))

(defn- check-file*
  "check-file before the :allow-sql, :allow-print and :allow-clock
   allowlists, which -main also needs to find stale entries."
  [file config]
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
                           (map (fn [{:keys [line symbol]}]
                                  {:file (str file)
                                   :ns   ns-name
                                   :req  symbol
                                   :line line
                                   :kind :fq-call})))
        impurity-violations (scan-impurity file content ns-form ns-name imports config)
        ;; Only what purity lets through: next.jdbc is I/O and stays a
        ;; :require, so :allow-sql cannot exempt it.
        sql-violations      (sql-require-findings
                             (finding-base file ns-name)
                             (remove #(forbidden-require? ns-name %) (map str requires)))]
    (->> (concat require-violations import-violations fq-violations
                 impurity-violations sql-violations)
         (remove #(and (= :fq-call (:kind %)) (allowed-fq-violation? (str file) (:req %)))))))

(defn check-file
  "Check a single core/ .clj file for forbidden requires, imports,
   fully-qualified forbidden calls, bare I/O calls, (throw ...) calls, mutable
   state in the body, and SQL requires; print, tap> and clock or random reads
   as warnings.
   Returns a seq of violation maps {:file :ns :req :kind [:line :severity]},
   :severity :error when absent, or empty seq
   if clean. Public so callers (e.g. the wagoe-mcp verify loop) can check an
   arbitrary core file outside the monorepo's `core-source-paths` discovery.
   The 1-arity reads the .wagoe/check-fcis.edn allowlist itself."
  ([file] (check-file file (read-config)))
  ([file config]
   (remove #(allowed? config %) (check-file* file config))))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn- print-finding [{:keys [file ns req kind line severity key]}]
  (println (str (if (= :warning severity) "  WARNING: " "  VIOLATION: ")
                file (when line (str ":" line))))
  (println
   (if (= :stale-allow kind)
     (str "    " key " entry " (ansi/red req) " exempts nothing — remove it")
     (str "    namespace " ns " "
          (case kind
            :fq-call       (if (= :warning severity)
                             (str "calls " (ansi/yellow req) " — an error from 2.0")
                             (str "calls " (ansi/red req)))
            :throw         (str "uses " (ansi/red "(throw ...)") " — core must return typed error values")
            :mutable-state (str "uses mutable state " (ansi/red req) " — registries/state belong in the shell")
            :print         (str "calls " (ansi/yellow req) " — printing is I/O; an error from 2.0")
            :clock         (str "calls " (ansi/yellow req) " — take the time or random value as an argument; an error from 2.0")
            :import        (str "imports " (ansi/red req))
            :require       (str "requires " (ansi/red req))
            :sql-require   (str "requires " (ansi/red req) " — core/ and ports know no SQL")
            (:sql-signature :sql-doc)
            (str "names " (ansi/red req) " — a port may pass a store handle, not type it as SQL"))))))

(defn -main [& _args]
  (let [config      (read-config)
        files       (core-clj-files)
        ports-files (ports-source-paths)
        raw         (concat (mapcat #(check-file* % config) files)
                            (mapcat sql-port-findings ports-files))
        stale       (for [[k req] (concat (map (fn [e] [:allow-sql (:path e)]) (stale-sql-entries config raw))
                                          (stale-warning-entries config raw))]
                      {:file ".wagoe/check-fcis.edn" :key k :req req :kind :stale-allow})
        {warnings :warning violations :error}
        (group-by #(:severity % :error)
                  (concat (remove #(allowed? config %) raw) stale))]
    (when (seq warnings)
      (println (ansi/yellow "FC/IS warnings:"))
      (run! print-finding warnings)
      (println))
    (if (seq violations)
      (do
        (println (ansi/red "FC/IS violations found:"))
        (println)
        (run! print-finding violations)
        (println)
        (println (str (count violations) " violation(s) found. Core namespaces must not import shell, I/O, logging, or DB code, throw, or hold mutable state; core and ports know no SQL."))
        (println (ansi/dim "Escape hatch: ^:wagoe/allow-throw / ^:wagoe/allow-mutable-state / ^:wagoe/allow-print / ^:wagoe/allow-clock ns metadata, or .wagoe/check-fcis.edn allowlist."))
        (System/exit 1))
      (do
        (println (ansi/green "FC/IS check passed.")
                 (str (count files) " core and " (count ports-files) " ports file(s) scanned, 0 violations."))
        (System/exit 0)))))
