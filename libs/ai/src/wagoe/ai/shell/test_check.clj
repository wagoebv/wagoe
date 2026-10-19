(ns wagoe.ai.shell.test-check
  "What gen-tests shows the model, and the checks its answer must pass before
   it is written (BOU-572): rc-4 wrote a namespace with invented protocol names
   that stopped the whole suite from loading. Since BOU-511 the answer is also
   run, in a separate JVM, before it is written."
  (:require [wagoe.ai.core.context :as ctx]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [malli.core :as m]))

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
    (try (m/form (m/schema v)) (catch Exception _ nil))))

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
                         (sort-by :name)
                         vec)
          schema?   (into #{} (map :name) schemas)
          vars      (->> publics
                         (remove (fn [[n v]] (or (protocol? v) (contains? methods n) (schema? n))))
                         (sort-by key)
                         (take max-vars)
                         (mapv (fn [[n v]] {:name n :arglists (:arglists (meta v))})))]
      {:ns ns-sym :protocols protocols :schemas schemas :vars vars})
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

(defn- on-classpath? [ns-sym]
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

(def ^:private runner
  "Run in the child JVM with the generated file and a result file as system
   properties. Fixtures tagged ^{:schema s} are checked first; a test that
   passes on invented data proves nothing."
  '(do
     (require 'clojure.string 'clojure.test 'malli.core 'malli.error)
     (let [file   (System/getProperty "wagoe.ai.test-file")
           out    (java.io.StringWriter.)
           result (try
                    (let [ns-sym (binding [*read-eval* false]
                                   (second (read-string (slurp file))))]
                      (load-file file)
                      (let [bad (vec (for [[n v] (sort-by key (ns-interns ns-sym))
                                           :let [s (:schema (meta v))]
                                           :when s
                                           :let [problem (try (some-> (malli.core/explain s @v)
                                                                      malli.error/humanize)
                                                              (catch Throwable e (str "not a schema: " (ex-message e))))]
                                           :when problem]
                                       (str "Fixture " n " does not match its :schema: " (pr-str problem))))]
                        (if (seq bad)
                          {:errors bad}
                          (let [{:keys [test fail error]}
                                (binding [clojure.test/*test-out* out]
                                  (clojure.test/run-tests ns-sym))]
                            (cond
                              (zero? test) {:errors ["The namespace defines no tests"]}
                              (pos? (+ fail error))
                              {:errors (into [(str "Running it: " fail " failures, " error " errors")]
                                             (->> (clojure.string/split-lines (str out))
                                                  (remove clojure.string/blank?)
                                                  (take 60)))}
                              :else {})))))
                    (catch Throwable e
                      {:errors [(str "It does not load: " (ex-message e)
                                     (some->> (ex-cause e) ex-message (str " — ")))]}))]
       (spit (System/getProperty "wagoe.ai.result-file") (pr-str result))
       (shutdown-agents)
       (System/exit 0))))

(def ^:private run-timeout-ms 180000)

(defn run-errors
  "Load `text` and run its tests in a separate JVM on this process's
   classpath, the one the namespace check used. Returns [line], or nil when
   every tagged fixture matches its schema and every test passes.

   The child gets an empty environment: the code is the model's, unread, and
   this process holds the provider's API key (BOU-572 review)."
  ([text] (run-errors text {}))
  ([text {:keys [root timeout-ms] :or {timeout-ms run-timeout-ms}}]
   (let [src (java.io.File/createTempFile "wagoe-ai-gen" ".clj")
         res (java.io.File/createTempFile "wagoe-ai-gen" ".edn")
         log (java.io.File/createTempFile "wagoe-ai-gen" ".log")]
     (try
       (spit src text)
       (let [java (str (System/getProperty "java.home") "/bin/java")
             pb   (ProcessBuilder. ^java.util.List
                                   [java "-cp" (System/getProperty "java.class.path")
                                    (str "-Dwagoe.ai.test-file=" (.getPath src))
                                    (str "-Dwagoe.ai.result-file=" (.getPath res))
                                    "clojure.main" "-e" (pr-str runner)])
             _    (.clear (.environment pb))
             proc (-> pb
                      (.directory (io/file (or root ".")))
                      (.redirectErrorStream true)
                      (.redirectOutput log)
                      (.start))]
         (if-not (.waitFor proc timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
           (do (.destroyForcibly proc)
               [(str "Running it did not finish within " (quot timeout-ms 1000) "s")])
           (let [result (some-> (slurp res) not-empty edn/read-string)]
             (if result
               (not-empty (:errors result))
               [(str "Running it failed before reporting (exit " (.exitValue proc) "): "
                     (str/trim (slurp log)))]))))
       (finally
         (run! #(.delete ^java.io.File %) [src res log]))))))

(defn check-errors
  "Errors in generated `text`, or nil. It is read and linted first; only a
   namespace that passes both is run, in a separate JVM (`run-errors`)."
  [text lint-opts]
  (or (not-empty (into (vec (missing-namespaces text)) (lint-errors text lint-opts)))
      (run-errors text lint-opts)))
