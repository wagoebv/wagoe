(ns wagoe.ai.shell.test-check
  "What gen-tests shows the model, and the checks its answer must pass before
   it is written (BOU-572): rc-4 wrote a namespace with invented protocol names
   that stopped the whole suite from loading."
  (:require [wagoe.ai.core.context :as ctx]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

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

(defn namespace-api
  "{:ns :protocols [{:name :methods [{:name :arglists}]}] :vars [{:name :arglists}]}
   for `ns-sym`, loaded, or {:ns :error} when it does not load."
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
          vars      (->> publics
                         (remove (fn [[n v]] (or (protocol? v) (contains? methods n))))
                         (sort-by key)
                         (take max-vars)
                         (mapv (fn [[n v]] {:name n :arglists (:arglists (meta v))})))]
      {:ns ns-sym :protocols protocols :vars vars})
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

(defn- messages [^Throwable t]
  (->> (iterate ex-cause t)
       (take-while some?)
       (keep ex-message)
       distinct
       (str/join " — ")))

(defn- ns-name-of [text]
  (some-> (re-find #"\(ns\s+(?:\^\S+\s+)*([^\s()\[\]{}]+)" (str text)) second symbol))

(defn compile-errors
  "Load `text` as it will be loaded by the test runner. Returns [message] when
   it fails, else nil. A namespace the load created is removed again."
  [text]
  (let [ns-sym  (ns-name-of text)
        existed (some-> ns-sym find-ns)]
    (try
      (load-string text)
      nil
      (catch Throwable t [(str "Compile: " (messages t))])
      (finally
        (when (and ns-sym (not existed))
          (remove-ns ns-sym))))))

(defn lint-errors
  "clj-kondo's warnings and errors for `text` as the file `filename`, run from
   `root` the way `bb check` runs it: the :clj-kondo alias and the project's
   .clj-kondo config. `context-files` are linted alongside so protocol and
   arity checks can see the definitions; their own findings are dropped. The
   cache is off so the result depends on those files, not on what an earlier
   lint happened to leave behind. Returns [line], or nil when clean."
  [text {:keys [root filename context-files]}]
  (let [{:keys [exit out err]}
        (apply sh/sh (concat ["clojure" "-M:clj-kondo" "--cache" "false" "--lint" "-"]
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

(defn check-errors
  "Compile and lint errors for generated `text`, or nil when it passes both."
  [text lint-opts]
  (not-empty (into (vec (compile-errors text)) (lint-errors text lint-opts))))
