#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/check_deps.clj
;;
;; Dependency direction linting: verifies that the wagoe libs a library uses
;; equal the ones its deps.edn declares, both ways, and that no circular
;; dependency exists between Wagoe libraries.

(ns wagoe.tools.check-deps
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [wagoe.tools.ansi :as ansi]
            [wagoe.tools.parsing :as parsing]))

;; ---------------------------------------------------------------------------
;; Parsing helpers
;; ---------------------------------------------------------------------------

(defn- lib-dirs
  "Return a seq of [lib-name, lib-dir] pairs for all libraries under libs/."
  []
  (let [root (io/file (System/getProperty "user.dir"))
        libs (io/file root "libs")]
    (when (.exists libs)
      (->> (.listFiles libs)
           (filter #(.isDirectory %))
           (map (fn [d] [(.getName d) d]))
           (sort-by first)))))

(defn- parse-deps-edn
  "Parse a deps.edn and extract declared Wagoe library dependencies.
   Returns a set of library name strings (empty set if file is missing)."
  [deps-file]
  (if (.exists deps-file)
    (let [deps (try (edn/read-string (slurp deps-file))
                    ;; Unreadable is reported by `unreadable-deps-files` as its
                    ;; own violation. Throwing here surfaced as a bare
                    ;; "EOF while reading" with no file named, because babashka
                    ;; prints the root cause.
                    (catch Exception _ nil))
          all-deps (merge (:deps deps) {})]
      (->> (vals all-deps)
           (filter map?)
           (keep :local/root)
           (map (fn [root-path]
                  ;; Extract lib name from paths like "../core" or "../../libs/core"
                  (last (str/split root-path #"/"))))
           (set)))
    #{}))

(defn- source-files
  "Find all .clj files under a library's src/ directory."
  [lib-dir]
  (let [src-dir (io/file lib-dir "src")]
    (when (.exists src-dir)
      (->> (file-seq src-dir)
           (filter #(and (.isFile %) (str/ends-with? (.getName %) ".clj")))))))

(defn library-entries
  "[lib-name lib-dir] for every library under libs/.

   Public wrapper over `lib-dirs` so a test can assert against the real tree —
   the guard that every namespace this repository requires is covered by the
   artifact map has to run on the repository, not a fixture."
  []
  (lib-dirs))

(defn- extract-required-ns
  "Required namespaces of a ns form, as strings: every :require clause, and
   prefix lists expanded. A missed require is now a false unused dep."
  [ns-form]
  (for [clause (filter #(and (sequential? %) (= :require (first %))) ns-form)
        spec   (rest clause)
        n      (cond (symbol? spec) [spec]
                     (vector? spec) [(first spec)]
                     (list? spec)   (for [s (rest spec)
                                          :let [s (if (vector? s) (first s) s)]
                                          :when (symbol? s)]
                                      (symbol (str (first spec) "." s))))]
    (str n)))

(defn ns->wagoe-lib
  "Given a namespace string like 'wagoe.user.core.foo', extract the library
   name 'user'. Returns nil for non-boundary namespaces.
   Maps wagoe.shared.* to 'shared-ui' (those namespaces live in libs/shared-ui/src/)."
  [ns-str]
  (let [parts (str/split ns-str #"\.")]
    (when (and (>= (count parts) 2) (= "wagoe" (first parts)))
      (case (second parts)
        ;; namespace segment differs from the lib dir name
        "shared" "shared-ui"      ; wagoe.shared.* lives in libs/shared-ui/
        "cli"    "wagoe-cli"      ; wagoe.cli.*  lives in libs/wagoe-cli/
        "mcp"    "wagoe-mcp"      ; wagoe.mcp.*  lives in libs/wagoe-mcp/
        (second parts)))))

;; ---------------------------------------------------------------------------
;; Graph building
;; ---------------------------------------------------------------------------

(defn- build-declared-graph
  "Build adjacency map from deps.edn: {lib-name -> #{dep-lib-names}}."
  [lib-entries]
  (into {}
        (map (fn [[lib-name lib-dir]]
               [lib-name (parse-deps-edn (io/file lib-dir "deps.edn"))])
             lib-entries)))

(def ^:private soft-ref-heads
  "Calls that load a namespace named by a quoted symbol at run time."
  #{"requiring-resolve" "require" "resolve"})

(defn- blank-comment-forms
  "`stripped` with every `(comment ...)` form blanked, newlines kept. Expects
   `strip-comments-and-strings` output, so no paren sits in a string."
  [^String stripped]
  (let [sb (StringBuilder. stripped)
        n  (count stripped)
        m  (re-matcher #"\(\s*comment[\s)]" stripped)]
    (while (.find m)
      (let [start (.start m)
            end   (loop [i start depth 0]
                    (if (>= i n)
                      n
                      (let [depth (case (.charAt stripped i) \( (inc depth) \) (dec depth) depth)]
                        (if (zero? depth) (inc i) (recur (inc i) depth)))))]
        (doseq [i (range start end) :when (not= \newline (.charAt stripped i))]
          (.setCharAt sb i \space))))
    (str sb)))

(defn- soft-refs
  "Quoted wagoe symbols passed to `soft-ref-heads` in `content`, as {:ns :line}.
   Read from code only: strings, comments and `(comment ...)` blocks are
   blanked first, so a docstring, a scaffolder template or a REPL snippet
   naming one does not count."
  [content]
  (for [{:keys [head arg1 line]} (parsing/call-forms
                                  (blank-comment-forms (parsing/strip-comments-and-strings content)))
        :when (and (soft-ref-heads head) arg1 (str/starts-with? arg1 "'wagoe."))]
    {:ns (first (str/split (subs arg1 1) #"/")) :line line}))

(defn- relative-path [f]
  (str/replace-first (.getPath (io/file f)) (str (System/getProperty "user.dir") "/") ""))

(defn- wagoe-refs
  "Every reference from a library's src/ to another Wagoe library, as
   {:lib :dep :from :ns :file :line :soft?}. Static ones come from the ns form;
   soft ones from `requiring-resolve` and friends, and only count when they
   name a library under libs/ (`wagoe.test-support` lives in the app)."
  [lib-names [lib-name lib-dir]]
  (for [f     (source-files lib-dir)
        :let  [content (slurp f)
               ns-form (try (some-> (parsing/extract-ns-form-text content) read-string)
                            (catch Exception _ nil))]
        ref   (concat (for [n (extract-required-ns ns-form)] {:ns n :soft? false})
                      (map #(assoc % :soft? true) (soft-refs content)))
        :let  [dep (ns->wagoe-lib (:ns ref))]
        :when (and dep (not= dep lib-name)
                   (or (not (:soft? ref)) (contains? lib-names dep)))]
    (assoc ref :lib lib-name :dep dep :from (some-> ns-form second str) :file (relative-path f))))

(defn all-wagoe-refs
  "`wagoe-refs` for every library. Public so `-main` can scan once and hand the
   result to both the graph and `dependency-mismatches`."
  [lib-entries]
  (let [lib-names (set (map first lib-entries))]
    (mapcat #(wagoe-refs lib-names %) lib-entries)))

(defn- refs->graph
  "{lib -> #{dep}} for `refs`, with every library present."
  [lib-entries refs]
  (merge (zipmap (map first lib-entries) (repeat #{}))
         (update-vals (group-by :lib refs) #(set (map :dep %)))))

(defn- build-actual-graph
  "Build adjacency map from source :requires: {lib-name -> #{dep-lib-names}}.
   Static requires only; a soft ref is how a cycle is avoided, not one."
  [lib-entries refs]
  (refs->graph lib-entries (remove :soft? refs)))

;; ---------------------------------------------------------------------------
;; Cycle detection (DFS)
;; ---------------------------------------------------------------------------

(defn find-all-cycles
  "DFS cycle detection on a graph. Returns all distinct cycles found as a
   seq of vectors (each vector is a cycle path), or empty seq if acyclic.
   Cycles are deduplicated by their set of directed edges so that two cycles
   over the same nodes but with different edge patterns are both reported.

   Public so a test can feed it a graph that must trip it. `-main` exits the
   process, so it cannot be used to prove this gate still detects anything."
  [graph]
  (let [visited (atom #{})
        path    (atom [])
        on-path (atom #{})
        cycles  (atom [])
        seen-edge-sets (atom #{})]
    (letfn [(dfs [node]
              (when-not (@visited node)
                (swap! path conj node)
                (swap! on-path conj node)
                (doseq [neighbor (get graph node #{})]
                  (if (@on-path neighbor)
                    (let [cycle-path (conj (vec (drop-while #(not= % neighbor) @path)) neighbor)
                          edge-set (set (map vector (butlast cycle-path) (rest cycle-path)))]
                      (when-not (contains? @seen-edge-sets edge-set)
                        (swap! seen-edge-sets conj edge-set)
                        (swap! cycles conj cycle-path)))
                    (when-not (@visited neighbor)
                      (dfs neighbor))))
                (swap! path pop)
                (swap! on-path disj node)
                (swap! visited conj node)))]
      (doseq [node (keys graph)]
        (dfs node))
      @cycles)))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn check-core-independence
  "Verify that the 'core' library has zero wagoe library dependencies.

   Public for the same reason as `find-all-cycles`."
  [declared-graph actual-graph]
  (let [declared-deps (get declared-graph "core" #{})
        actual-deps   (get actual-graph "core" #{})]
    (concat
     (map (fn [d] {:type :core-declared-dep :dep d}) declared-deps)
     (map (fn [d] {:type :core-actual-dep :dep d}) actual-deps))))

;; ---------------------------------------------------------------------------
;; Third-party dependency declaration
;; ---------------------------------------------------------------------------
;;
;; `check:poms` verifies that published POMs carry their inter-Wagoe deps
;; (BOU-202), and the graph above covers wagoe -> wagoe. Neither looks at
;; third-party requires, so a library could use tools.cli, cheshire or anything
;; else without declaring it and both gates stayed green. That is how
;; wagoe-ai shipped a POM without tools.cli and every `bb ai` subcommand died in
;; a generated project (BOU-272), and how platform and user came to resolve
;; tools.cli only through migratus, at 1.0.219 rather than the pinned 1.4.256
;; (BOU-273).

(def ^:private ns-prefix->artifact
  "Namespace prefix -> the artifact that provides it.

   Several of these families are split across artifacts, and one entry per
   family gets the gate wrong: `buddy.sign.jwt` comes from buddy/buddy-sign,
   not buddy/buddy-core, so mapping all of `buddy` to buddy-core meant removing
   buddy-sign from a deps.edn went unreported — the coarser gap was already
   allowlisted and absorbed it. Same for `integrant.repl` (integrant/repl, a
   separate artifact from integrant/integrant) and for reitit, which this
   repository consumes as four modules rather than the bundle.

   Longest prefix wins, so `buddy.core` is checked before `buddy`. Entries are
   added only for namespaces a Wagoe library actually requires: a prefix absent
   here is not checked, which makes this map the gate's coverage."
  {"aero"                   'aero/aero
   "buddy.core"             'buddy/buddy-core
   "buddy.hashers"          'buddy/buddy-hashers
   "buddy.sign"             'buddy/buddy-sign
   "cheshire"               'cheshire/cheshire
   "cognitect.transit" 'com.cognitect/transit-clj
   "clj-http"               'clj-http/clj-http
   "clj-kondo"              'clj-kondo/clj-kondo
   "hiccup"                 'hiccup/hiccup
   ;; hiccup 2.x moved the API here, and "hiccup" is not a prefix of
   ;; "hiccup2.core" — the family looked covered while every hiccup2 user went
   ;; unchecked.
   "hiccup2"                'hiccup/hiccup
   "honey.sql"              'com.github.seancorfield/honeysql
   "integrant.core"         'integrant/integrant
   "integrant.repl"         'integrant/repl
   "malli"                  'metosin/malli
   "migratus"               'migratus/migratus
   "muuntaja"               'metosin/muuntaja
   "next.jdbc"              'com.github.seancorfield/next.jdbc
   "reitit.coercion.malli"  'metosin/reitit-malli
   "reitit.core"            'metosin/reitit-core
   "reitit.middleware"      'metosin/reitit-core
   "reitit.ring.middleware" 'metosin/reitit-middleware
   "reitit.ring"            'metosin/reitit-ring
   "reitit.swagger-ui"      'metosin/reitit-swagger-ui
   "reitit.openapi"         'fi.metosin/reitit-openapi
   "rewrite-clj"            'rewrite-clj/rewrite-clj
   "ring.adapter.jetty"     'ring/ring-jetty-adapter
   "ring.core"              'ring/ring-core
   "ring.middleware"        'ring/ring-core
   "ring.util"              'ring/ring-core
   "ring.websocket"         'ring/ring-core
   "sentry-clj"             'io.sentry/sentry-clj
   "taoensso.nippy"         'com.taoensso/nippy
   "one-time"               'one-time/one-time
   "dk.ative.docjure"       'dk.ative/docjure
   "clojure.tools.cli"      'org.clojure/tools.cli
   "edamame.core"           'borkdude/edamame
   "clojure.tools.logging"  'org.clojure/tools.logging})

(defn provider-of
  "The artifact providing `ns-str`, or nil when no prefix matches.

   Longest match wins. `some` over the map picked an arbitrary one where
   prefixes overlap — hash-map order is not insertion order past eight entries
   — so `reitit.ring.middleware.muuntaja` could have resolved to reitit-ring or
   reitit-middleware depending on nothing the caller controls.

   Public so a test can drive the overlapping cases directly."
  [ns-str]
  (->> ns-prefix->artifact
       (filter (fn [[prefix _]]
                 (or (= ns-str prefix)
                     (str/starts-with? ns-str (str prefix ".")))))
       (sort-by (comp - count first))
       first
       second))

(def ^:private clojure-stdlib-namespaces
  "Namespaces shipped inside the org.clojure/clojure jar.

   An exact set, not a `clojure.` prefix rule. Most of what lives under that
   prefix is contrib, published as its own artifact: clojure.data is stdlib
   while clojure.data.csv is org.clojure/data.csv, and clojure.java.io is
   stdlib while clojure.java.jdbc is org.clojure/java.jdbc. Exempting the
   prefix would have let a library require either contrib namespace and ship a
   POM without it — the defect this gate exists to catch, reintroduced through
   the exemption.

   Regenerate with:
     unzip -l \"$(find ~/.m2 -name 'clojure-1.12*.jar' | grep -v sources | head -1)\" \\
       | grep -oE 'clojure/[a-zA-Z_/-]+[.]clj$' \\
       | sed 's|^clojure/||;s|[.]clj$||' | tr '/' '.' | sed 's|_|-|g;s|^|clojure.|' | sort -u"
  #{"clojure.core" "clojure.core-deftype" "clojure.core-print"
    "clojure.core-proxy" "clojure.core.protocols" "clojure.core.reducers"
    "clojure.core.server" "clojure.data" "clojure.datafy"
    "clojure.edn" "clojure.genclass" "clojure.gvec"
    "clojure.inspector" "clojure.instant" "clojure.java.basis"
    "clojure.java.basis.impl" "clojure.java.browse" "clojure.java.browse-ui"
    "clojure.java.io" "clojure.java.javadoc" "clojure.java.process"
    "clojure.java.shell" "clojure.main" "clojure.math"
    "clojure.parallel" "clojure.pprint" "clojure.pprint.cl-format"
    "clojure.pprint.column-writer" "clojure.pprint.dispatch" "clojure.pprint.pprint-base"
    "clojure.pprint.pretty-writer" "clojure.pprint.print-table" "clojure.pprint.utilities"
    "clojure.reflect" "clojure.reflect.java" "clojure.repl"
    "clojure.repl.deps" "clojure.set" "clojure.stacktrace"
    "clojure.string" "clojure.template" "clojure.test"
    "clojure.test.junit" "clojure.test.tap" "clojure.tools.deps.interop"
    "clojure.uuid" "clojure.walk" "clojure.xml"
    "clojure.zip"})

(def ^:private babashka-runtime-libs
  "Libraries that run under Babashka, which bundles the babashka.* namespaces.

   Scoped per library, not globally. `bb` supplies these only to code it runs:
   libs/tools is pure Babashka (every entrypoint carries a bb shebang), so
   declaring babashka/fs there would be wrong rather than merely redundant. For
   any other library the same require is a real Maven dependency, and a global
   exemption would have let it ship a POM without one — the blind spot this gate
   exists to close.

   A library added here needs the same justification: `bb` runs it."
  #{"tools"})

(def ^:private runtime-provided-prefixes
  "Namespace prefixes the Babashka runtime supplies, for the libraries above.

   clj-yaml is bundled by bb like babashka.* is — it is declared in no deps.edn
   and resolves at runtime. `check-branch-protection` parses workflow YAML with
   it."
  #{"babashka" "clj-yaml"})

(defn- third-party-ns?
  "Whether `ns-str` is a namespace some artifact has to provide.

   Excludes Wagoe's own namespaces, the Clojure standard library, and — for
   Babashka-run libraries only — the namespaces `bb` bundles. Everything else is something an artifact has to
   provide — including the rest of `clojure.*`, which is contrib: tools.cli,
   tools.logging, data.csv and core.async are each their own dependency, and
   treating the prefix as standard library is what this ticket is about."
  [lib-name ns-str]
  (and (not (str/starts-with? ns-str "wagoe."))
       (not (contains? clojure-stdlib-namespaces ns-str))
       (not (and (contains? babashka-runtime-libs lib-name)
                 (some #(or (= ns-str %) (str/starts-with? ns-str (str % ".")))
                       runtime-provided-prefixes)))))

(defn unmapped-third-party-namespaces
  "Third-party namespaces required by a library that `ns-prefix->artifact` does
   not cover, as [{:lib :ns}].

   Reported as a failure rather than ignored. The map is the gate's coverage,
   and a namespace missing from it is checked by nothing — `provider-of`
   returns nil and the require passes silently, which is the same
   looks-covered-but-is-not failure the gate exists to catch. Anything genuinely
   not from an artifact belongs in `runtime-provided-prefixes`, where the reason
   is written down.

   Public so a test can drive it; `-main` exits the process."
  [lib-entries]
  (distinct
   (for [[lib-name lib-dir] lib-entries
         f      (source-files lib-dir)
         ns-str (extract-required-ns (parsing/read-ns-form f))
         :when  (and (third-party-ns? lib-name ns-str) (nil? (provider-of ns-str)))]
     {:lib lib-name :ns ns-str})))

(defn declared-artifacts
  "Artifact coordinates declared in a library's deps.edn, as strings, or
   `:unreadable` when the file exists but does not parse.

   `:unreadable` rather than an empty set or a thrown exception. An empty set
   would make every namespace in that library look undeclared — a wall of
   violations pointing at the wrong thing. A throw was worse in practice:
   babashka prints the root cause, so `EOF while reading` reached the terminal
   with no indication of which file. It is reported as its own violation
   instead.

   Public so a test can drive it; `-main` exits the process."
  [lib-dir]
  (let [deps-file (io/file lib-dir "deps.edn")]
    (if (.exists deps-file)
      (try
        (set (map str (keys (:deps (edn/read-string (slurp deps-file))))))
        (catch Exception _ :unreadable))
      #{})))

(defn third-party-gaps
  "[{:lib :ns :artifact}] for third-party namespaces a library requires
   without declaring the artifact that provides them.

   Only *direct* declaration counts. Resolving through a transitive works until
   the dependency in between drops it, and it picks up whatever version that
   dependency pins — which is how platform got tools.cli 1.0.219 while the
   monorepo pinned 1.4.256.

   Public so a test can drive it; `-main` exits the process."
  [lib-entries]
  (for [[lib-name lib-dir] lib-entries
        ;; Read eagerly, and let a malformed deps.edn throw. This used to be
        ;; inside the lazy `for`, so an unreadable file surfaced nowhere: the
        ;; exception was never realised and the gate printed "0 violations" and
        ;; exited 0. A checker that passes when it cannot read its input is
        ;; worse than no checker.
        :let [declared (declared-artifacts lib-dir)]
        :when (set? declared)
        f    (source-files lib-dir)
        ns-str (extract-required-ns (parsing/read-ns-form f))
        :let [artifact (provider-of ns-str)]
        :when (and artifact (not (contains? declared (str artifact))))]
    {:lib lib-name :ns ns-str :artifact artifact}))

(defn unreadable-deps-files
  "Libraries whose deps.edn exists but does not parse.

   Checked separately because a checker that cannot read its input must say so
   rather than draw a conclusion from it — the first version of this gate
   swallowed the read error inside a lazy sequence and printed
   \"0 violations\"."
  [lib-entries]
  (for [[lib-name lib-dir] lib-entries
        :when (= :unreadable (declared-artifacts lib-dir))]
    {:lib lib-name :file (.getPath (io/file lib-dir "deps.edn"))}))

;; ---------------------------------------------------------------------------
;; Declared wagoe deps = used wagoe deps (BOU-870)
;; ---------------------------------------------------------------------------
;;
;; Both directions. A lib that requires another without declaring it ships a
;; POM that omits it; one that declares a lib it never uses drags it into every
;; app. Use is a static require, a soft ref (requiring-resolve), or a resource
;; the lib's deps.edn names under :wagoe/resource-deps.

(def config-file ".wagoe/check-deps.edn")

(defn- require-why! [where entries]
  (doseq [e entries]
    (when (or (str/blank? (some-> (:lib e) str)) (str/blank? (some-> (:dep e) str)))
      (throw (ex-info (str where " entry needs :lib and :dep: " (pr-str e))
                      {:type :configuration-error :entry e})))
    (when (str/blank? (some-> (:why e) str))
      (throw (ex-info (str where " entry " (:lib e) " -> " (:dep e) " has no :why.")
                      {:type :configuration-error :entry e})))))

(defn read-config
  "The `.wagoe/check-deps.edn` allowlists, {:allow-soft [..] :allow-unused [..]}.
   Throws on an entry without a :why: an exemption nobody can explain cannot be
   burnt down."
  ([] (read-config (io/file (System/getProperty "user.dir") config-file)))
  ([file]
   (let [cfg (if (.exists (io/file file)) (edn/read-string (slurp file)) {})]
     (require-why! (str file " :allow-soft") (:allow-soft cfg))
     (require-why! (str file " :allow-unused") (:allow-unused cfg))
     cfg)))

(defn resource-deps
  "`:wagoe/resource-deps` of a library's deps.edn as [{:lib :dep :resource :why}]:
   deps it uses for a classpath resource rather than a namespace. Throws on an
   entry without :resource or :why."
  [[lib-name lib-dir]]
  (let [deps-file (io/file lib-dir "deps.edn")
        m (when (.exists deps-file)
            (try (:wagoe/resource-deps (edn/read-string (slurp deps-file)))
                 (catch Exception _ nil)))
        entries (for [[dep {:keys [resource why]}] m]
                  {:lib lib-name :dep (str dep) :resource resource :why why})]
    (require-why! (str deps-file " :wagoe/resource-deps") entries)
    (doseq [e entries :when (str/blank? (:resource e))]
      (throw (ex-info (str deps-file " :wagoe/resource-deps " (:dep e) " has no :resource.")
                      {:type :configuration-error :entry e})))
    entries))

(defn dependency-mismatches
  "Where a library's declared wagoe deps differ from the ones it uses.

   :undeclared-dep       a static require of an undeclared lib
   :undeclared-soft-dep  a soft ref to an undeclared lib, not in :allow-soft
   :unused-dep           a declared lib nothing uses, not in :allow-unused
   :stale-allowance      an :allow-soft / :allow-unused / resource-deps entry
                         that exempts nothing

   Only direct declaration counts, and only the main :deps: test aliases may
   legitimately pull in more. A library whose deps.edn does not parse is left
   to :unreadable-deps rather than blamed for every require."
  ([lib-entries config] (dependency-mismatches lib-entries config (all-wagoe-refs lib-entries)))
  ([lib-entries {:keys [allow-soft allow-unused]} refs]
   (let [unreadable (set (map :lib (unreadable-deps-files lib-entries)))
         allow-soft   (map #(update-vals % str) allow-soft)
         allow-unused (map #(update-vals % str) allow-unused)
         declared  (build-declared-graph lib-entries)
         dirs      (into {} lib-entries)
         resources (mapcat resource-deps lib-entries)
         pair      (juxt :lib :dep)
         declared? (fn [[lib dep]] (contains? (get declared lib) dep))
         static    (set (map pair (remove :soft? refs)))
         used      (set (map pair (concat refs resources)))
         undeclared-soft (->> refs (filter :soft?) (remove (comp declared? pair)))
         soft-pairs      (set (map pair undeclared-soft))
         unused    (set (for [[lib deps] declared, dep deps
                              :when (not (used [lib dep]))]
                          [lib dep]))
         allowed-soft   (set (map pair allow-soft))
         allowed-unused (set (map pair allow-unused))
         stale (fn [k reason e] (assoc (select-keys e [:lib :dep]) :type :stale-allowance
                                       :key k :reason reason))]
     (remove
      (comp unreadable :lib)
      (concat
       (for [r (remove :soft? refs) :when (not (declared? (pair r)))]
         (assoc (select-keys r [:lib :dep :from :ns :file]) :type :undeclared-dep))
       (for [r undeclared-soft :when (not (allowed-soft (pair r)))]
         (assoc (select-keys r [:lib :dep :from :ns :file :line]) :type :undeclared-soft-dep))
       (for [[lib dep] (sort unused) :when (not (allowed-unused [lib dep]))]
         {:type :unused-dep :lib lib :dep dep})
       (for [e allow-soft :when (not (soft-pairs (pair e)))]
         (stale :allow-soft "no undeclared soft ref matches it" e))
       (for [e allow-unused :when (not (unused (pair e)))]
         (stale :allow-unused "the dep is used or not declared" e))
       (for [e resources
             :let [reason (cond
                            (not (declared? (pair e))) "the dep is not declared in :deps"
                            (static (pair e))          "the dep is required, so the entry is not needed"
                            (not (some-> (dirs (:dep e)) (io/file "resources" (:resource e)) .exists))
                            (str (:resource e) " is not in " (:dep e) "/resources"))]
             :when reason]
         (stale :wagoe/resource-deps reason e)))))))

(def ^:private allowed-third-party-gaps
  "Pre-existing [lib artifact] pairs where a library requires a namespace
   without declaring the artifact that provides it.

   Empty as of BOU-276: all 54 are declared. Kept rather than deleted because it
   is the honest place for a gap that genuinely cannot be closed yet — the
   alternative is loosening `third-party-gaps` or the artifact map, which
   weakens the gate everywhere instead of recording one exception. An entry here
   is a to-do, not an exemption, and needs the reason with it."
  #{})

(defn- allowed-third-party-gap?
  [{:keys [lib artifact]}]
  (contains? allowed-third-party-gaps [lib (str artifact)]))

;; ---------------------------------------------------------------------------
;; Known cycle allowlist
;; ---------------------------------------------------------------------------

(def ^:private allowed-cycle-edges
  "Pre-existing directed source-level require edges that are acknowledged
   but not yet resolved. A cycle is allowlisted when every directed edge
   in its path is covered by this set.
   Remove entries as cross-references are broken; adding new entries requires an ADR."
  ;; No source-level cycles remain (BOU-171/192/193/194/198 dissolved them all).
  #{})

(defn- allowed-cycle?
  "Returns true if every directed edge in the cycle path is covered by the
   allowlist. A cycle involving any new edge will fail."
  [cycle-path]
  (let [edges (map vector (butlast cycle-path) (rest cycle-path))]
    (every? #(contains? allowed-cycle-edges %) edges)))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn describe-violation
  "One line for a hard failure. Public so a test can assert what it names."
  [v]
  (case (:type v)
    :core-declared-dep
    (str "core/deps.edn declares dependency on " (ansi/red (:dep v)))
    :core-actual-dep
    (str "core source requires " (ansi/red (:dep v)) " (core must be dependency-free)")
    :declared-cycle
    (str "circular dependency in deps.edn: " (ansi/red (str/join " -> " (:path v))))
    :actual-cycle
    (str "circular dependency in source requires: " (ansi/red (str/join " -> " (:path v))))
    :undeclared-dep
    (str (:lib v) " requires " (ansi/red (:dep v)) " but does not declare it: "
         (:from v) " requires " (:ns v) " (" (:file v) ")")
    :undeclared-soft-dep
    (str (:lib v) " resolves " (ansi/red (:dep v)) " at run time but does not declare it: "
         (:ns v) " at " (:file v) ":" (:line v)
         " — declare it, or add it to " config-file " :allow-soft with a :why")
    :unused-dep
    (str (:lib v) " declares " (ansi/red (:dep v)) " but nothing in its src/ uses it"
         " — remove it from " (:lib v) "/deps.edn :deps, or name the resource under"
         " :wagoe/resource-deps")
    :stale-allowance
    (str (:key v) " entry " (:lib v) " -> " (ansi/red (:dep v)) " is stale: " (:reason v))
    :undeclared-third-party
    (str (:lib v) " uses " (ansi/red (str (:artifact v)))
         " but does not declare it — the published POM would omit it")
    :unreadable-deps
    (str "cannot parse " (ansi/red (:file v)) " — dependency checks for " (:lib v) " cannot run")
    :unmapped-ns
    (str (:lib v) " requires " (ansi/red (:ns v))
         " which maps to no artifact — add it to ns-prefix->artifact,"
         " or add the library to babashka-runtime-libs if bb runs it")))

(defn -main [& _args]
  (let [entries        (lib-dirs)
        declared-graph (build-declared-graph entries)
        refs           (all-wagoe-refs entries)
        actual-graph   (build-actual-graph entries refs)
        core-issues        (check-core-independence declared-graph actual-graph)
        mismatches         (dependency-mismatches entries (read-config) refs)
        declared-cycles    (find-all-cycles declared-graph)
        actual-cycles      (find-all-cycles actual-graph)
        allowed-actual     (filter allowed-cycle? actual-cycles)
        new-actual         (remove allowed-cycle? actual-cycles)
        tp-gaps             (distinct (map #(select-keys % [:lib :artifact])
                                           (third-party-gaps entries)))
        allowed-tp          (filter allowed-third-party-gap? tp-gaps)
        new-tp              (remove allowed-third-party-gap? tp-gaps)
        unreadable          (unreadable-deps-files entries)
        unmapped            (unmapped-third-party-namespaces entries)
        hard-failures  (concat core-issues
                               (map (fn [p] {:type :declared-cycle :path p}) declared-cycles)
                               (map (fn [p] {:type :actual-cycle :path p}) new-actual)
                               mismatches
                               (map #(assoc % :type :undeclared-third-party) new-tp)
                               (map #(assoc % :type :unreadable-deps) unreadable)
                               (map #(assoc % :type :unmapped-ns) unmapped))]
    (when (seq allowed-tp)
      (println (ansi/yellow "Third-party dependencies used but not declared (allowlisted):"))
      (doseq [v (sort-by (juxt :lib (comp str :artifact)) allowed-tp)]
        (println (str "  " (:lib v) " uses " (:artifact v) " (resolved transitively)")))
      (println (str (count allowed-tp) " allowlisted. Each is a version the library does not control."))
      (println))
    ;; Print allowlisted actual cycles as informational
    (when (seq allowed-actual)
      (println (ansi/yellow "Known source-level cycle(s) (allowlisted):"))
      (doseq [c allowed-actual]
        (println (str "  " (str/join " -> " c))))
      (println))
    ;; Hard failures block CI
    (if (seq hard-failures)
      (do
        (println (ansi/red "Dependency direction violations found:"))
        (println)
        (doseq [v hard-failures]
          (println (str "  VIOLATION: " (describe-violation v))))
        (println)
        (println (str (count hard-failures) " violation(s) found."))
        (System/exit 1))
      (do
        (println (ansi/green "Dependency check passed.")
                 (str (count entries) " libraries scanned, 0 hard violations."))
        (System/exit 0)))))
