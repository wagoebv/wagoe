(ns wagoe.geo.test-db
  "An H2 datasource with the geo_cache table, for tests."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]))

(defn drop-cache-table! [ds]
  (jdbc/execute! ds ["DROP TABLE IF EXISTS geo_cache"]))

(defn- up-migrations
  "The module's .up.sql files, oldest first. The .edn ones only retype
   timestamps, which H2 needs no help with."
  []
  (->> (.listFiles (io/file (io/resource "wagoe/geo/migrations")))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".up.sql"))
       (sort-by #(.getName ^java.io.File %))))

(defn fresh-cache-db!
  "Recreates geo_cache in the in-memory H2 database `db-name` from the module's
   own migrations; returns its datasource."
  [db-name]
  (let [ds (jdbc/get-datasource {:dbtype "h2:mem" :dbname db-name :DB_CLOSE_DELAY "-1"})]
    (drop-cache-table! ds)
    (doseq [file      (up-migrations)
            statement (str/split (slurp file) #"--;;")
            :when     (not (str/blank? statement))]
      (jdbc/execute! ds [statement]))
    ds))
