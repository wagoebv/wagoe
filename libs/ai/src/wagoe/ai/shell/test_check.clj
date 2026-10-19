(ns wagoe.ai.shell.test-check
  "What gen-tests shows the model, and the checks its answer must pass before
   it is written (BOU-572): rc-4 wrote a namespace with invented protocol names
   that stopped the whole suite from loading. Since BOU-511 its tagged
   fixtures are checked against their schemas, and with --run it is run, in a
   separate JVM, before it is written."
  (:require [wagoe.ai.core.context :as ctx]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

;; =============================================================================
;; The API of a required namespace, read from the classpath
;; =============================================================================

(def ^:private max-vars
  "Vars listed per namespace. clojure.core-sized namespaces would crowd out
   the source, and the model knows those anyway."
  80)

(defn- protocol? [v]
  (let [x (when (bound? v) @v)]
    (and (map? x) (contains? x :on-interface) (contains? x :sigs))))

(defn- schema-form
  "The Malli form of `v` when it is a schema, else nil. Only vectors and
   compiled schemas count: a var holding `:string` is more likely a constant."
  [v]
  (when (or (and (vector? v) (keyword? (first v))) (m/schema? v))
    (try (m/form v) (catch Exception _ nil))))

(defn namespace-api
  "{:ns :protocols [{:name :methods [{:name :arglists}]}] :schemas [{:name :form}]
    :vars [{:name :arglists}]} for `ns-sym`, loaded, or {:ns :error} when it
   does not load."
  [ns-sym]
  (try
    (require ns-sym)
    (let [publics   (ns-publics ns-sym)
          protocols (->> publics
                         (filter (comp protocol? val))
                         (sort-by key)
                         (mapv (fn [[n v]]
                                 {:name    n
                                  :methods (->> (vals (:sigs @v))
                                                (sort-by :name)
                                                (mapv #(select-keys % [:name :arglists])))})))
          methods   (into #{} (comp (mapcat :methods) (map :name)) protocols)
          schemas   (->> publics
                         (keep (fn [[n v]]
                                 (when-let [form (and (bound? v) (schema-form @v))]
                                   {:name n :form form})))
                         (sort-by :name))
          schema?   (into #{} (map :name) schemas)
          vars      (->> publics
                         (remove (fn [[n v]] (or (protocol? v) (contains? methods n) (schema? n))))
                         (sort-by key)
                         (take max-vars)
                         (mapv (fn [[n v]] {:name n :arglists (:arglists (meta v))})))]
      {:ns ns-sym :protocols protocols :schemas (vec (take max-vars schemas)) :vars vars})
    (catch Throwable t
      {:ns ns-sym :error (or (ex-message t) (.getName (class t)))})))

(defn read-ns-form
  "The ns form at the top of `source`, or nil."
  [source]
  (try
    (binding [*read-eval* false]
      (let [form (read-string (str source))]
        (when (and (seq? form) (= 'ns (first form))) form)))
    (catch Exception _ nil)))

(defn required-namespaces
  "The namespaces `source` requires."
  [source]
  (some-> (read-ns-form source) ctx/ns-form-requires))

(defn source-file
  "A path clj-kondo can read for `ns-sym`'s source, or nil. A source inside a
   jar is copied out to a temporary file. Clojure's own namespaces are left
   out: kondo knows them, and clojure.core is large."
  [ns-sym]
  (let [base (-> (str ns-sym) (str/replace "-" "_") (str/replace "." "/"))]
    (when-let [url (and (not (str/starts-with? (str ns-sym) "clojure."))
                        (some #(io/resource (str base %)) [".clj" ".cljc"]))]
      (if (= "file" (.getProtocol url))
        (.getPath (io/file url))
        (let [f (java.io.File/createTempFile "wagoe-ai-ctx" (if (str/ends-with? (str url) ".cljc") ".cljc" ".clj"))]
          (.deleteOnExit f)
          (spit f (slurp url))
          (.getPath f))))))

;; =============================================================================
;; Checks
;; =============================================================================

(defn on-classpath? [ns-sym]
  (let [base (-> (str ns-sym) (str/replace "-" "_") (str/replace "." "/"))]
    (boolean (some #(io/resource (str base %)) [".clj" ".cljc"]))))

(defn missing-namespaces
  "One error per namespace `text` requires that is not on the classpath.
   clj-kondo cannot tell an invented namespace from one it has not seen."
  [text]
  (not-empty
   (vec (for [ns-sym (required-namespaces text)
              :when (not (on-classpath? ns-sym))]
          (str "Requires " ns-sym ", which is not on the classpath")))))

(def ^:private strict
  "Names the model invented are errors, not the warnings they are by default.
   Merged over the project's own config."
  "{:linters {:unresolved-var {:level :error} :unresolved-namespace {:level :error}}}")

(defn lint-errors
  "clj-kondo's warnings and errors for `text` as the file `filename`, run from
   `root` the way `bb check` runs it: the :clj-kondo alias and the project's
   .clj-kondo config, with unresolved vars and namespaces raised to errors. `context-files` are linted alongside so protocol and
   arity checks can see the definitions; their own findings are dropped. The
   cache is off so the result depends on those files, not on what an earlier
   lint happened to leave behind. Returns [line], or nil when clean."
  [text {:keys [root filename context-files]}]
  (let [{:keys [exit out err]}
        (apply sh/sh (concat ["clojure" "-M:clj-kondo" "--cache" "false" "--config" strict "--lint" "-"]
                             (distinct context-files)
                             ["--filename" filename :in text :dir (or root ".")]))
        ours (->> (str/split-lines (str out))
                  (filter #(and (str/starts-with? % (str filename ":"))
                                (re-find #": (?:warning|error): " %))))]
    (cond
      (seq ours)            (vec ours)
      (#{0 2 3} exit)       nil
      :else                 [(str "clj-kondo could not run (exit " exit "): "
                                  (str/trim (str err out)))])))

(def ^:private run-timeout-ms 180000)

(def child-env
  "Environment variables the child keeps: enough to find tools and select the
   profile, none that hold secrets."
  #{"PATH" "HOME" "LANG" "WAG_ENV"})

(defn- absolute-classpath
  "This process's classpath with each entry absolute: the child runs from
   `root`, where the CLI's relative entries would not resolve."
  []
  (->> (str/split (System/getProperty "java.class.path")
                  (re-pattern (java.util.regex.Pattern/quote java.io.File/pathSeparator)))
       (map #(.getAbsolutePath (io/file %)))
       (str/join java.io.File/pathSeparator)))

(defn run-errors
  "Load `text` and run its tests in a separate JVM on this process's
   classpath, the one the namespace check used. Returns [line], or nil when
   every tagged fixture matches its schema and every test passes.

   The child sees only `child-env` of this process's environment, so the
   provider's API key is not handed to code nobody has read (BOU-572 review).
   It is not a sandbox: the code can still reach files and the network."
  [text {:keys [root]}]
  (if-let [ns-sym (second (read-ns-form text))]
    (let [src (java.io.File/createTempFile "wagoe-ai-gen" ".clj")
          res (java.io.File/createTempFile "wagoe-ai-gen" ".edn")
          log (java.io.File/createTempFile "wagoe-ai-gen" ".log")]
      (try
        (spit src text)
        (let [java (str (System/getProperty "java.home") "/bin/java")
              pb   (ProcessBuilder. ^java.util.List
                    [java "-cp" (absolute-classpath)
                     "clojure.main" "-m" "wagoe.ai.shell.test-runner"
                     (.getPath src) (str ns-sym) (.getPath res)])
              _    (.retainAll (.keySet (.environment pb)) child-env)
              proc (-> pb
                       (.directory (io/file (or root ".")))
                       (.redirectErrorStream true)
                       (.redirectOutput log)
                       (.start))]
          (if-not (.waitFor proc run-timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
            (do (.destroyForcibly proc)
                [(str "Running it did not finish within " (quot run-timeout-ms 1000) "s")])
            (if-let [errors (some-> (slurp res) not-empty edn/read-string)]
              (not-empty errors)
              [(str "Running it failed before reporting (exit " (.exitValue proc) "): "
                    (str/trim (slurp log)))])))
        (finally
          (run! #(.delete ^java.io.File %) [src res log]))))
    ["It has no ns form"]))

;; =============================================================================
;; Fixtures checked without running anything
;; =============================================================================

(defn- read-forms
  "Every top-level form in `text`, read without evaluation, or nil when it
   cannot be read that way (an auto-resolved keyword with an alias, say)."
  [text]
  (try
    (binding [*read-eval* false]
      (let [rdr (java.io.PushbackReader. (java.io.StringReader. (str text)))]
        (loop [forms []]
          (let [form (read {:eof ::eof :read-cond :allow} rdr)]
            (if (= ::eof form) forms (recur (conj forms form)))))))
    (catch Exception _ nil)))

(defn- literal?
  "True for data a reader produces on its own: no calls, no references."
  [x]
  (cond
    (or (seq? x) (symbol? x)) false
    (coll? x)                 (every? literal? (if (map? x) (mapcat identity x) x))
    :else                     true))

(defn- tagged-fixtures
  "[{:name :schema :value}] for each top-level (def ^{:schema s} name value)
   in `forms`."
  [forms]
  (for [form forms
        :when (and (seq? form) (= 'def (first form)) (symbol? (second form)))
        :let [s (:schema (meta (second form)))]
        :when s]
    {:name (second form) :schema s :value (last form)}))

(defn- resolve-schema
  "The schema `sym` names in a file whose ns form is `ns-form`, or nil. The
   namespace is loaded: it is the project's own code, not the model's."
  [ns-form sym]
  (when-let [ns-sym (and (qualified-symbol? sym)
                         (let [a (symbol (namespace sym))]
                           (or (some (fn [[n al]] (when (= al a) n)) (ctx/ns-form-aliases ns-form))
                               (some #{a} (ctx/ns-form-requires ns-form)))))]
    (try (require ns-sym)
         (some-> (ns-resolve ns-sym (symbol (name sym))) deref)
         (catch Exception _ nil))))

(defn- fixture-result [ns-form {:keys [name schema value]}]
  (let [s (resolve-schema ns-form schema)]
    (cond
      (nil? s)
      [:errors (str "Fixture " name ": :schema " schema " does not resolve")]

      (not (literal? value))
      [:unchecked (str "Fixture " name " not checked: its value is not literal data (check it with --run)")]

      :else
      (when-let [problem (try (some-> (m/explain s value) me/humanize)
                              (catch Exception e (str "not a schema: " (ex-message e))))]
        [:errors (str "Fixture " name " does not match its :schema: " (pr-str problem))]))))

(defn fixture-check
  "The tagged fixtures in `text` checked against their schemas without
   evaluating anything: {:errors [line] :unchecked [line]}. A value that is
   not literal data cannot be checked that way, so it is reported, not failed."
  [text]
  (if-let [forms (read-forms text)]
    (reduce (fn [acc [k line]] (update acc k conj line))
            {:errors [] :unchecked []}
            (keep #(fixture-result (first forms) %) (tagged-fixtures forms)))
    {:errors [] :unchecked ["Fixtures not checked: the file cannot be read without evaluating it"]}))

(defn check-errors
  "Errors in generated `text`, or nil. It is read and linted, and its tagged
   fixtures checked statically. Only with `:run?`, and only when those pass,
   is it loaded and run, in a separate JVM (`run-errors`): running the
   model's code is opt-in."
  [text {:keys [run?] :as opts}]
  (or (not-empty (-> (vec (missing-namespaces text))
                     (into (lint-errors text opts))
                     (into (:errors (fixture-check text)))))
      (when run? (run-errors text opts))))
