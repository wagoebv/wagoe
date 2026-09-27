#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/check_tests.clj
;;
;; Detects placeholder and tautological test assertions that pass green but
;; provide no real coverage: (is true), (is (= true true)), predicates on
;; string literals like (is (some? "...")), and (is (not nil/false)).

(ns wagoe.tools.check-tests
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [edamame.core :as e]
            [clojure.set :as set]
            [clojure.string :as str]
            [wagoe.tools.ansi :as ansi]
            [wagoe.tools.parsing :as parsing]))

;; ---------------------------------------------------------------------------
;; Placeholder detection (BOU-365) — one scanner: the reader
;; ---------------------------------------------------------------------------
;;
;; Earlier versions ran seven shape regexes over stripped text next to a
;; form-level scan, and every review round broke the seam between them:
;; textual matches had to be re-judged for liveness, exemption and reader
;; branches, and the final failure — a reader conditional relocating a
;; site's position — was unfixable at that seam. Every shape the regexes
;; matched is trivially decidable on the parsed form, so the regexes are
;; gone and the reader decides everything once.

(defn- test-clj-files
  "Find all .clj files under libs/*/test/ and test/."
  []
  (let [root      (io/file (System/getProperty "user.dir"))
        libs-dir  (io/file root "libs")
        top-test  (io/file root "test")
        lib-tests (when (.exists libs-dir)
                    (->> (.listFiles libs-dir)
                         (filter #(.isDirectory %))
                         (mapcat (fn [lib-dir]
                                   (let [test-dir (io/file lib-dir "test")]
                                     (when (.exists test-dir)
                                       (file-seq test-dir)))))))
        top-tests (when (.exists top-test)
                    (file-seq top-test))]
    (->> (concat lib-tests top-tests)
         (filter #(and (.isFile %)
                       (str/ends-with? (.getName %) ".clj"))))))

(defn- offset->line-number
  "Convert a character offset into a 1-based line number. Used by the
   regex-based metadata and tag scanners below, which are line-oriented by
   nature and not part of the placeholder scan."
  [content offset]
  (inc (count (filter #(= \newline %) (subs content 0 (min offset (count content)))))))

(def assertion-helper-names
  "Helpers that assert internally, matched on the name after any alias — so
   `snapshot-io/check-snapshot!` and a re-aliased copy both count. The named
   list the ticket asks for: without it the snapshot suite's 11 real tests
   would be the gate's first 11 false positives, and a gate that opens with
   11 wrong findings is a gate people learn to ignore (BOU-365)."
  #{"check-snapshot!"})

(defn- parse-forms
  "`raw` as data, read by edamame — the reader, not a lexer imitating one.
   `#_` is elided before we ever look, quoted forms arrive as data, and `^…`
   lands in (meta …). Verified against every test file in this repository —
   380 files, zero parse failures. Unknown reader tags pass their value
   through."
  [raw features]
  (e/parse-string-all raw
                      {:all          true
                       :auto-resolve (fn [a] (if (= a :current) (symbol "this.ns") (symbol (str a))))
                       :readers      (fn [_tag] identity)
                       :features     features
                       :read-cond    :allow}))

(defn- features-for
  "The reader feature sets of the runtimes that actually execute `file`.

   Per surface, not a union: libs/tools and scripts/ run under Babashka only
   (AGENTS.md, test surfaces), whose reader enables *both* :clj and :bb —
   `#?(:clj A :bb B)` takes A there, and a nested `#?(:clj #?(:bb X))`
   reaches X. Everything else runs on the JVM only. Scanning a branch no
   runtime selects reported dead placeholders and failed CI on valid tests
   (BOU-365 review, round 9)."
  [file]
  (let [path (str/replace (str file) "\\" "/")]   ; File.toString is \ on Windows
    (if (or (str/includes? path "libs/tools/test")
            (str/includes? path "scripts/"))
      [#{:clj :bb}]
      [#{:clj}])))

(defn- test-aliases
  "The namespace names that mean clojure.test in `parsed` — clojure.test
   itself plus every alias the ns form gives it. A qualified `pred/is` from
   some other library is an ordinary function, not an assertion (BOU-365
   review, round 9)."
  [parsed]
  (let [ns-form (first (filter #(and (seq? %) (symbol? (first %))
                                     (= "ns" (name (first %))))
                               parsed))
        specs   (for [clause ns-form
                      :when (and (seq? clause) (= :require (first clause)))
                      spec  (rest clause)
                      :when (and (vector? spec) (= 'clojure.test (first spec)))]
                  (apply hash-map (rest spec)))
        aliases (into #{"clojure.test"}
                      (keep #(some-> (:as %) str) specs))
        renames (into {}
                      (for [spec specs
                            [from to] (:rename spec)]
                        [(str to) (str from)]))
        excluded (set (for [clause ns-form
                            :when (and (seq? clause) (= :refer-clojure (first clause)))
                            [k v] (partition 2 (rest clause))
                            :when (= :exclude k)
                            sym v]
                        (str sym)))]
    {:aliases aliases :renames renames :core-excluded excluded}))

(defn- resolve-op
  "The clojure.test operator `sym` denotes, restricted to `ops`, or nil.

   A bare `is` counts — the universal :refer style; a renamed one —
   `:refer [is] :rename {is check}` — counts as its original; a qualified
   one counts only when its namespace part is clojure.test or an alias of
   it. Anything else, including `pred/is` from another library, is an
   ordinary function (BOU-365 review, rounds 9–10)."
  [{:keys [aliases renames]} ops sym]
  (when (symbol? sym)
    (let [n (name sym)
          n (get renames n n)]
      (when (and (contains? ops n)
                 (or (nil? (namespace sym))
                     (contains? aliases (namespace sym))))
        n))))

(defn- core-op?
  "Whether `sym` is the clojure.core operator named `n` — unqualified, or
   spelled clojure.core/n. `(domain/some? v)` and `(domain/= x x)` are
   somebody else's functions with their own semantics (round 10), and an
   unqualified name the ns excludes via `:refer-clojure :exclude` is a local
   definition, not core (round 11). Lexical shadowing — `(let [= f] …)` —
   is out of a static scanner's reach; the escape hatch covers that corner."
  [{:keys [core-excluded]} sym n]
  (and (symbol? sym)
       (= n (name sym))
       (or (= "clojure.core" (namespace sym))
           (and (nil? (namespace sym))
                (not (contains? core-excluded n))))))

(defn- unevaluated-head?
  "Whether `head` is the real quote or comment operator — the reader's bare
   `quote`, or core `comment`. `(foo/comment …)` is an ordinary qualified
   call whose arguments run (BOU-365 review, round 11)."
  [env head]
  (boolean (or (core-op? env head "quote")
               (core-op? env head "comment"))))

(defn- deftest-forms
  "Every deftest form in `forms` — bare or spelled through a clojure.test
   alias — at any depth, not descending into `quote` or `comment`: a draft
   in a comment form and a deftest-shaped list in quoted data are never
   defined."
  [forms env]
  (let [out (volatile! [])]
    (letfn [(walk [x]
              (when (coll? x)
                (let [head (when (seq? x) (first x))]
                  (cond
                    (unevaluated-head? env head)
                    nil
                    (resolve-op env #{"deftest"} head)
                    (do (vswap! out conj x) (run! walk (rest x)))
                    :else (run! walk (seq x))))))]
      (run! walk forms))
    @out))

(defn- exempt?
  "Whether the deftest's *name symbol* — the second element, nothing later —
   carries the exact top-level `:wagoe/allow-placeholder` metadata key.
   Edamame merges `^…` into (meta …); a keyword in a value position, a
   misspelling, or a mention in the body is not an exemption."
  [deftest-form]
  (let [nm (second deftest-form)]
    (boolean (and (symbol? nm) (:wagoe/allow-placeholder (meta nm))))))

(defn- evaluated-heads
  "Names of every operator position reachable at runtime under `form` —
   real `quote`/`comment` subtrees excluded."
  [form env]
  (let [out (volatile! #{})]
    (letfn [(walk [x]
              (when (coll? x)
                (let [head (when (seq? x) (first x))]
                  (when (symbol? head)
                    (if (unevaluated-head? env head)
                      nil
                      (do (vswap! out conj (name head))
                          (run! walk (rest x)))))
                  (when-not (and (seq? x) (symbol? (first x)))
                    (run! walk (seq x))))))]
      (walk form))
    @out))

(defn- assertion-sites
  "Every evaluated clojure.test `is`/`are` form under `form` — bare or via a
   clojure.test alias. `(pred/is true)` from some other namespace is an
   ordinary call and is not collected."
  [form env]
  (let [out (volatile! [])]
    (letfn [(walk [x]
              (when (coll? x)
                (let [head (when (seq? x) (first x))]
                  (cond
                    (unevaluated-head? env head) nil
                    :else (do (when-let [op (resolve-op env #{"is" "are"} head)]
                                (vswap! out conj {:site x :op op}))
                              (run! walk (seq x)))))))]
      (walk form))
    @out))

(defn- testing-forms
  "Every evaluated clojure.test `testing` form under `form`, nested ones too."
  [form env]
  (let [out (volatile! [])]
    (letfn [(walk [x]
              (when (coll? x)
                (let [head (when (seq? x) (first x))]
                  (when-not (unevaluated-head? env head)
                    (when (resolve-op env #{"testing"} head)
                      (vswap! out conj x))
                    (run! walk (seq x))))))]
      (walk form))
    @out))

(defn- head-syms
  "Every symbol in operator position reachable at runtime under `form`."
  [form env]
  (let [out (volatile! #{})]
    (letfn [(walk [x]
              (when (coll? x)
                (let [head (when (seq? x) (first x))]
                  (if (unevaluated-head? env head)
                    nil
                    (do (when (symbol? head) (vswap! out conj head))
                        (run! walk (seq x)))))))]
      (walk form))
    @out))

(defn- external-assert-call?
  "Whether `form` calls an `assert-*` or `expect-*` helper from another
   namespace — test support libraries' convention for a helper that asserts."
  [form env]
  (boolean (some #(and (namespace %) (re-find #"^(assert|expect)-" (name %)))
                 (head-syms form env))))

(defn- helper-definitions
  "name -> {:body form :macro? bool} for every helper `parsed` defines: defn,
   defn-, defmacro, def of a fn, and let/letfn bindings."
  [parsed env]
  (let [out (volatile! {})]
    (letfn [(walk [x]
              (when (coll? x)
                (let [head (when (seq? x) (first x))
                      nm   (when (symbol? (second x)) (name (second x)))]
                  (cond
                    (and nm (or (core-op? env head "defn") (core-op? env head "defn-")))
                    (vswap! out assoc nm {:body x})

                    (and nm (core-op? env head "defmacro"))
                    (vswap! out assoc nm {:body x :macro? true})

                    (and nm (core-op? env head "def") (seq? (last x))
                         (core-op? env (first (last x)) "fn"))
                    (vswap! out assoc nm {:body (last x)})

                    (and (core-op? env head "letfn") (vector? (second x)))
                    (doseq [f (second x) :when (and (seq? f) (symbol? (first f)))]
                      (vswap! out assoc (name (first f)) {:body f}))

                    (and (core-op? env head "let") (vector? (second x)))
                    (doseq [[k v] (partition 2 (second x)) :when (symbol? k)]
                      (vswap! out assoc (name k) {:body v})))
                  (run! walk (seq x)))))]
      (run! walk parsed))
    @out))

(defn- asserts-directly?
  [{:keys [body macro?]} env]
  (or (seq (assertion-sites body env))
      (external-assert-call? body env)
      ;; A macro's assertion is inside a syntax quote, which reads as data.
      (and macro? (some #(and (symbol? %) (#{"is" "are"} (name %))) (tree-seq coll? seq body)))))

(defn- asserting-fns
  "Names of the helpers `parsed` defines that assert, directly or through
   another helper, so a `testing` that calls one counts as asserting."
  [parsed env]
  (let [defs  (helper-definitions parsed env)
        start (into assertion-helper-names
                    (keep (fn [[nm d]] (when (asserts-directly? d env) nm)) defs))]
    (loop [known start]
      (let [more (into known
                       (keep (fn [[nm {:keys [body]}]]
                               (when (seq (set/intersection (evaluated-heads body env) known)) nm)))
                       defs)]
        (if (= more known) known (recur more))))))

(defn- asserted-expr
  "The expression a single `is`/`are` actually asserts: the second element of
   an `is`, the template of an `are`. Only this counts — a nested shape in a
   message or under `false?` is someone's data, not a vacuous assertion."
  [op site]
  (case op
    "is"  (second site)
    "are" (first (drop 2 site))
    nil))

(defn- pure-literal?
  "No function call anywhere inside — the only rows for which substituting
   one expression twice cannot yield two different values."
  [x]
  (if (coll? x)
    (and (not (seq? x)) (every? pure-literal? (seq x)))
    true))

(defn- identical-arg-equality?
  "`(= x x)`: core equality, three elements, identical non-collection args.
   `(= (rand) (rand))` is excluded — equal as forms, different as values —
   and `(= ##NaN ##NaN)` needs no case: it is already unequal."
  [env asserted]
  (boolean
   (and (seq? asserted)
        (core-op? env (first asserted) "=")
        (= 3 (count asserted))
        (not (coll? (second asserted)))
        (= (second asserted) (nth asserted 2)))))

(defn- tautological?
  "Whether the site can only ever compare a value with itself.

   For `is`, the asserted expression alone decides. For `are`, the template
   is instantiated per row — `(are [x] (= x x) (next-value))` expands to
   `(= (next-value) (next-value))`, two evaluations that may differ — so the
   template shape condemns the site only when every row is a pure literal
   (BOU-365 review, round 10)."
  [env op site asserted]
  (and (identical-arg-equality? env asserted)
       (or (not= "are" op)
           (every? pure-literal? (drop 3 site)))))

(defn- placeholder-shape
  "Why the asserted expression can only succeed, or nil when it can fail.

   Skip-sentinel assertions like (is (not (redis-available?))) are the
   accepted exception, and fall out structurally: only the *literals* nil
   and false under `not` are flagged, never a call. (instance? Exception e)
   is flagged because inside `catch Exception` it is always true."
  [env asserted]
  (cond
    (true? asserted) "asserts the literal true"

    (and (seq? asserted) (symbol? (first asserted)))
    (let [op   (first asserted)
          args (rest asserted)
          [a b] args]
      (cond
        (and (or (core-op? env op "some?") (core-op? env op "string?"))
             (= 1 (count args)) (string? a))
        (str "(" (name op) " <string literal>) is always true")

        (and (core-op? env op "not") (= 1 (count args)) (contains? #{nil false} a))
        "(not nil/false) is always true"

        (and (core-op? env op "instance?") (= 2 (count args))
             (symbol? a) (= "Exception" (name a)) (symbol? b))
        "(instance? Exception e) is always true inside catch Exception"

        :else nil))

    :else nil))

(defn- within?
  "Edamame's :end-col is exclusive — one past the closing delimiter — so the
   upper bound is strict: a form starting exactly at an exempt form's
   :end-col is its neighbour, not its content (BOU-365 review, round 10)."
  [{:keys [row col end-row end-col]} [r c]]
  (and (or (> r row) (and (= r row) (>= c col)))
       (or (< r end-row) (and (= r end-row) (< c end-col)))))

(defn- branch-findings
  "All findings for one reader feature set: assertion-free deftests, and
   evaluated sites whose asserted expression is a tautology or a shape that
   can only succeed. Sites inside an exempted deftest are skipped."
  [file raw features]
  (let [parsed  (parse-forms raw features)
        env     (test-aliases parsed)
        dtests  (deftest-forms parsed env)
        exempt  (for [f dtests :when (exempt? f)]
                  (select-keys (meta f) [:row :col :end-row :end-col]))
        exempt-site? (fn [site]
                       (let [m (meta site)]
                         (boolean (some #(within? % [(:row m) (:col m)]) exempt))))]
    (concat
     (for [form dtests
           :when (not (exempt? form))
           :when (and (empty? (assertion-sites form env))
                      (empty? (set/intersection (evaluated-heads form env)
                                                assertion-helper-names)))]
       {:file (str file) :line (or (:row (meta form)) 0)
        :content "deftest without any assertion (is/are/known helper)"})
     ;; A `testing` next to one that asserts passes the rule above, so an
     ;; empty one needs its own (BOU-562).
     (let [helpers (asserting-fns parsed env)]
       (for [form dtests
             :when (not (exempt? form))
             block (testing-forms form env)
             :when (not (:wagoe/allow-placeholder (meta block)))
             :when (and (empty? (assertion-sites block env))
                        (not (external-assert-call? block env))
                        (empty? (set/intersection (evaluated-heads block env) helpers)))]
         {:file (str file) :line (or (:row (meta block)) 0)
          :content "testing block without any assertion (is/are/known helper)"}))
     (for [top parsed
           {:keys [site op]} (assertion-sites top env)
           :when (not (exempt-site? site))
           ;; The same hatch, one assertion wide: `^:wagoe/allow-placeholder
           ;; (t/is true "Snapshot matches")` marks a deliberate reporting
           ;; sentinel — snapshot_io.clj emits four — in the file, where a
           ;; reviewer sees it.
           :when (not (:wagoe/allow-placeholder (meta site)))
           :let [asserted (asserted-expr op site)
                 shape    (placeholder-shape env asserted)
                 finding  (cond
                            (tautological? env op site asserted)
                            (str "tautology: " (pr-str asserted))
                            shape
                            (str "placeholder: " (pr-str asserted) " — " shape))]
           :when finding]
       {:file (str file) :line (or (:row (meta site)) 0)
        :content finding}))))

(defn scan-content-structural
  "Every placeholder finding in `raw`, unioned over the runtime feature
   branches. Public so a test can prove the gate still detects one
   (BOU-250); `-main` exits the process, so it cannot serve as the seam."
  [file raw]
  (distinct
   (mapcat #(branch-findings file raw %) (features-for file))))

(defn exempted-extents
  "{:row :col :end-row :end-col} of every metadata-exempted deftest, across
   all feature branches. Informational; exemption is applied per branch
   inside branch-findings."
  [raw]
  (distinct
   (for [features [#{:clj} #{:clj :bb}]
         :let [parsed (parse-forms raw features)]
         form (deftest-forms parsed (test-aliases parsed))
         :when (exempt? form)]
     (select-keys (meta form) [:row :col :end-row :end-col]))))

(defn- scan-file
  "Scan a file on disk for placeholder assertions. One row per (file, line):
   two findings for one assertion reads as two problems."
  [file]
  (->> (scan-content-structural file (slurp file))
       (reduce (fn [{:keys [seen acc]} {:keys [file line] :as f}]
                 (if (seen [file line])
                   {:seen seen :acc acc}
                   {:seen (conj seen [file line]) :acc (conj acc f)}))
               {:seen #{} :acc []})
       :acc))

;; ---------------------------------------------------------------------------
;; Misplaced deftest metadata (BOU-184)
;; ---------------------------------------------------------------------------

(def ^:private misplaced-meta-pattern
  "A deftest whose metadata sits AFTER the test name — e.g.
     (deftest foo
       ^:unit
       ...)
   The reader attaches that metadata to the following body form (the testing
   block), not the test var, so `--focus-meta` silently skips the test. The
   correct form is (deftest ^:unit foo ...). Matched on stripped source so
   commented-out or string occurrences are ignored. `\\^[:{]` covers both the
   `^:keyword` and `^{...}` metadata forms."
  #"(?s)\(\s*deftest\s+([^\s()^]+)\s+\^[:{]")

(defn scan-content-meta
  "Return match maps for deftest forms in `raw` with metadata placed after the
   name, reporting `file` as the location.

   Public seam for the firing test (BOU-250) — `check-deftest-metadata` exits
   the process, so it cannot be called from a test."
  [file raw]
  (let [cleaned (parsing/strip-comments-and-strings raw)
        matcher (re-matcher misplaced-meta-pattern cleaned)]
    (loop [matches []]
      (if (.find matcher)
        (recur (conj matches {:file (str file)
                              :line (offset->line-number raw (.start matcher))
                              :name (.group matcher 1)}))
        matches))))

(defn- scan-file-meta
  "Scan a file on disk for misplaced deftest metadata."
  [file]
  (scan-content-meta file (slurp file)))

;; ---------------------------------------------------------------------------
;; Entry points
;; ---------------------------------------------------------------------------

(defn -main [& _args]
  (let [files   (test-clj-files)
        matches (mapcat scan-file files)]
    (if (seq matches)
      (do
        (println (ansi/red "Placeholder test assertions found:"))
        (println)
        (doseq [{:keys [file line content]} matches]
          (println (str "  " file ":" line ": " content)))
        (println)
        (println (str (count matches) " placeholder(s) found. Replace with meaningful assertions."))
        (System/exit 1))
      (do
        (println (ansi/green "No placeholder tests found.") (str (count files) " test file(s) scanned."))
        (System/exit 0)))))

(defn check-deftest-metadata
  "Flag deftest forms whose metadata is placed after the name (attaching to the
   body form, so `--focus-meta` skips them). Correct form: (deftest ^:meta name).
   BOU-184."
  [& _args]
  (let [files   (test-clj-files)
        matches (mapcat scan-file-meta files)]
    (if (seq matches)
      (do
        (println (ansi/red "Misplaced deftest metadata found (attaches to the body, not the test var):"))
        (println)
        (doseq [{:keys [file line name]} matches]
          (println (str "  " file ":" line ": (deftest " name " …) — move the ^:meta before the name")))
        (println)
        (println (str (count matches) " misplaced. Write (deftest ^:meta name …) so --focus-meta selects the test."))
        (System/exit 1))
      (do
        (println (ansi/green "No misplaced deftest metadata.") (str (count files) " test file(s) scanned."))
        (System/exit 0)))))

;; ---------------------------------------------------------------------------
;; Pyramid tag gate (BOU-166): every deftest carries exactly one of
;; ^:unit / ^:integration / ^:contract. Cross-cutting tags (^:security, ^:e2e,
;; …) may coexist. Not-yet-backfilled test files are exempted via
;; .wagoe/check-test-tags.edn until they are tagged.
;; ---------------------------------------------------------------------------

(def ^:private pyramid-tags
  "The mutually-exclusive test-pyramid metadata keywords."
  #{"unit" "integration" "contract"})

(def ^:private deftest-meta-pattern
  "Matches a top-level (deftest <meta ...> name), capturing the metadata region
   (group 1) and the test name (group 2). The anchor `^\\(deftest` is deliberate:
   real registered tests are top-level (column 0); an indented (deftest …) is
   inside a (comment …), let, or docstring and is not a live test.

   The metadata region accepts both keyword shorthand (`^:unit`) and map form
   (`^{:kaocha.testable/meta {:unit true}}`, one level of nesting), so a deftest
   using the map form is still scanned — it counts zero *keyword* pyramid tags
   and is therefore flagged to use the `^:keyword` shorthand the gate and
   `--focus-meta` rely on."
  #"(?m)^\(deftest((?:\s+(?:\^:[a-zA-Z][\w?*!+<>='-]*|\^\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}))*)\s+([a-zA-Z][^\s()]*)")

(defn read-tags-config
  "Read the optional .wagoe/check-test-tags.edn allowlist. Returns a set of
   repo-relative path prefixes; any test file under one is exempt from the
   pyramid-tag requirement (gradual backfill)."
  []
  (let [f (io/file (System/getProperty "user.dir") ".wagoe" "check-test-tags.edn")]
    (if (.exists f)
      (try (set (map str (:allow-untagged (edn/read-string (slurp f)))))
           (catch Exception _ #{}))
      #{})))

(defn- relative-path
  "File path relative to the repo root (user.dir), for stable prefix matching."
  [file]
  (let [root (str (System/getProperty "user.dir") "/")
        p    (str file)]
    (if (str/starts-with? p root) (subs p (count root)) p)))

(defn- exempt-file? [allow file]
  (let [rel (relative-path file)]
    (some #(str/starts-with? rel %) allow)))

(defn scan-content-tags
  "Return {:file :line :name :count} for each deftest in `raw` whose pyramid-tag
   count is not exactly 1, reporting `file` as the location.

   Public seam for the firing test (BOU-250) — `check-test-tags` exits the
   process, so it cannot be called from a test."
  [file raw]
  (let [cleaned (parsing/strip-comments-and-strings raw)
        matcher (re-matcher deftest-meta-pattern cleaned)]
    (loop [violations []]
      (if (.find matcher)
        (let [meta-region (.group matcher 1)
              n (count (filter pyramid-tags (re-seq #"(?<=\^:)[a-zA-Z][\w?*!+<>='-]*" meta-region)))]
          (recur (if (= 1 n)
                   violations
                   (conj violations {:file  (str file)
                                     :line  (offset->line-number raw (.start matcher))
                                     :name  (.group matcher 2)
                                     :count n}))))
        violations))))

(defn- scan-file-tags
  "Scan a file on disk for deftests without exactly one pyramid tag."
  [file]
  (scan-content-tags file (slurp file)))

(defn check-test-tags
  "Enforce exactly one pyramid tag (^:unit / ^:integration / ^:contract) per
   deftest. Files listed in .wagoe/check-test-tags.edn :allow-untagged are
   skipped (gradual backfill, BOU-166)."
  [& _args]
  (let [allow    (read-tags-config)
        files    (remove #(exempt-file? allow %) (test-clj-files))
        matches  (mapcat scan-file-tags files)]
    (if (seq matches)
      (do
        (println (ansi/red "Deftests missing exactly one pyramid tag (^:unit/^:integration/^:contract):"))
        (println)
        (doseq [{:keys [file line name count]} matches]
          (println (str "  " file ":" line ": (deftest " name " …) has " count " pyramid tag(s)")))
        (println)
        (println (str (clojure.core/count matches)
                      " deftest(s) need exactly one pyramid tag. Add ^:unit, ^:integration, or ^:contract."))
        (System/exit 1))
      (do
        (println (ansi/green "All deftests carry exactly one pyramid tag.")
                 (str (clojure.core/count files) " enforced test file(s) scanned."))
        (System/exit 0)))))
