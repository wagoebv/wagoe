(ns wagoe.cli.catalogue
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]))

(def ^:private catalogue-path "wagoe/cli/modules-catalogue.edn")

(def ^:private catalogue-data
  (delay
    (let [r (io/resource catalogue-path)]
      (when-not r
        (throw (ex-info "modules-catalogue.edn not found on classpath"
                        {:path catalogue-path})))
      (edn/read-string (slurp r)))))

(defn load-catalogue
  "Load the bundled modules-catalogue.edn. Throws if not found."
  []
  @catalogue-data)

(defn read-clj
  "`s` read with the Clojure reader, so bb.edn's `#()` and `#\"\"` parse."
  [s]
  (binding [*read-eval* false] (read-string s)))

(defn wagoe-lib-name
  "Short name (\"core\") of Wagoe deps entry `lib`, or nil. A local checkout
   may name them `wagoe/core {:local/root ..}` (BOU-662)."
  [lib coord]
  (when (symbol? lib)
    (case (namespace lib)
      "com.wagoe" (second (re-matches #"wagoe-(.+)" (name lib)))
      "wagoe"     (when (:local/root coord) (name lib))
      nil)))

(defn wagoe-deps
  "Short names of the Wagoe libs in the :deps of file `f`; empty when it is
   missing or does not read."
  [f]
  (let [f (io/file f)]
    (if (.exists f)
      (try
        (set (keep (fn [[lib coord]] (wagoe-lib-name lib coord))
                   (:deps (read-clj (slurp f)))))
        (catch Exception _ #{}))
      #{})))

(defn find-module
  "Find a module by name string. Returns the module map or nil."
  [module-name]
  (first (filter #(= module-name (:name %)) (:modules (load-catalogue)))))

(defn optional-modules
  "Return all modules with :category :optional."
  []
  (filter #(= :optional (:category %)) (:modules (load-catalogue))))

(defn core-modules
  "Return all modules with :category :core."
  []
  (filter #(= :core (:category %)) (:modules (load-catalogue))))

(defn validate-catalogue!
  "Validate all entries have required fields. Throws on first violation."
  []
  (let [required [:name :description :category :version :clojars
                  :config-snippet :test-config-snippet :add-command :docs-url]]
    (doseq [m (:modules (load-catalogue))
            field required]
      (when-not (contains? m field)
        (throw (ex-info (str "Catalogue entry missing field: " field)
                        {:module (:name m) :field field}))))
    (doseq [m (:modules (load-catalogue))]
      (when-not (symbol? (:clojars m))
        (throw (ex-info "Catalogue entry :clojars must be a symbol"
                        {:module (:name m) :clojars (:clojars m)}))))))
