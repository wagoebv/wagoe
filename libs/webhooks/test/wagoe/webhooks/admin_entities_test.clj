(ns wagoe.webhooks.admin-entities-test
  "The shipped admin entities are what the admin accepts, and name only
   columns the migration creates."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [wagoe.admin.schema :as admin-schema]
            [wagoe.webhooks.shell.persistence :as persistence]))

(def ^:private entities (edn/read-string (slurp (io/resource "wagoe/webhooks/admin.edn"))))

(defn- columns
  "Column names per table in the migration, kebab-cased."
  []
  (let [sql (slurp (io/resource persistence/migration-resource))]
    (into {}
          (for [[_ table body] (re-seq #"(?s)CREATE TABLE IF NOT EXISTS (\w+) \((.*?)\n\);" sql)]
            [(keyword table)
             (set (for [[_ col] (re-seq #"(?m)^\s+(\w+)\s" body)]
                    (keyword (str/replace col "_" "-"))))]))))

(deftest ^:unit the-admin-accepts-them
  (is (= [] (admin-schema/entity-config-errors {:entities entities}))))

(deftest ^:unit they-name-real-columns
  (let [cols (columns)]
    (doseq [[k {:keys [table-name list-fields search-fields readonly-fields fields]}] entities]
      (is (empty? (remove (get cols table-name)
                          (concat list-fields search-fields readonly-fields (keys fields))))
          (str k)))))
