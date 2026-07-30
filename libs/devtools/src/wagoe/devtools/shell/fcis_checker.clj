(ns wagoe.devtools.shell.fcis-checker
  "Post-reset namespace scanner for FC/IS violations.
   Only detects BND-601: core namespace importing shell namespace via :require.
   BND-602 (core uses I/O) is detected statically by bb check:fcis."
  (:require [wagoe.devtools.core.error-formatter :as formatter]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private framework-prefixes
  #{"wagoe.platform." "wagoe.observability." "wagoe.devtools." "wagoe.core."})

(defn core-ns?
  "Is this a user-level core namespace? (wagoe.MODULE.core.*)"
  [ns-str]
  (and (str/includes? ns-str ".core.")
       (str/starts-with? ns-str "wagoe.")
       (not (some #(str/starts-with? ns-str %) framework-prefixes))))

(defn shell-ns?
  "Is this a user-level shell namespace? (wagoe.MODULE.shell.*)"
  [ns-str]
  (and (str/includes? ns-str ".shell.")
       (str/starts-with? ns-str "wagoe.")
       (not (some #(str/starts-with? ns-str %) framework-prefixes))))

(defn- extract-module
  "Extract the module name from a namespace string."
  [ns-str]
  (let [parts (str/split ns-str #"\.")]
    (when (>= (count parts) 3)
      (nth parts 1))))

(defn- ns-declared-requires
  "Parse the ns form from source to find ALL :require'd namespaces.
   This catches bare requires like (:require [foo.bar]) that are invisible
   to runtime introspection (ns-aliases, ns-refers, ns-map) when no vars
   are aliased, referred, or dereferenced."
  [ns-obj]
  (try
    (let [ns-sym  (ns-name ns-obj)
          path    (-> (str ns-sym)
                      (str/replace "." "/")
                      (str/replace "-" "_")
                      (str ".clj"))
          resource (io/resource path)]
      (when resource
        (with-open [rdr (io/reader resource)]
          (let [ns-form (binding [*read-eval* false]
                          (read (java.io.PushbackReader. rdr)))]
            (->> ns-form
                 (filter #(and (sequential? %) (= :require (first %))))
                 (mapcat rest)
                 (map #(if (symbol? %) % (first %)))
                 (map str))))))
    (catch Exception _ nil)))

(defn find-violations
  "Scan loaded namespaces for FC/IS violations.
   Returns a vector of {:source-ns :requires-ns :module} maps."
  []
  (let [loaded-nses (all-ns)]
    (->> loaded-nses
         (filter #(core-ns? (str (ns-name %))))
         (mapcat (fn [ns-obj]
                   (let [ns-str       (str (ns-name ns-obj))
                         all-requires (ns-declared-requires ns-obj)]
                     (->> all-requires
                          (filter shell-ns?)
                          (map (fn [shell-ns]
                                 {:source-ns   ns-str
                                  :requires-ns shell-ns
                                  :module      (extract-module ns-str)}))))))
         vec)))

(defn check-fcis-violations!
  "Scan loaded namespaces and print warnings for FC/IS violations.
   Called after (go) and (reset) in user.clj."
  []
  (let [violations (find-violations)]
    (when (seq violations)
      (println)
      (doseq [v violations]
        (println (formatter/format-fcis-violation v)))
      (println (str "\n" (count violations) " FC/IS violation(s) found. "
                    "Run bb check:fcis for full static analysis.\n")))))
