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
        (throw (ex-info (str "Catalogue entry :clojars must be a symbol")
                        {:module (:name m) :clojars (:clojars m)}))))))
