(ns wagoe.geo.test-db
  "An H2 datasource with the geo_cache table, for tests."
  (:require [next.jdbc :as jdbc]))

(defn drop-cache-table! [ds]
  (jdbc/execute! ds ["DROP TABLE IF EXISTS geo_cache"]))

(defn fresh-cache-db!
  "Recreates geo_cache in the in-memory H2 database `db-name`; returns its datasource."
  [db-name]
  (let [ds (jdbc/get-datasource {:dbtype "h2:mem" :dbname db-name :DB_CLOSE_DELAY "-1"})]
    (drop-cache-table! ds)
    (jdbc/execute! ds ["CREATE TABLE geo_cache (
      address_hash      TEXT PRIMARY KEY,
      lat               NUMERIC(10, 7) NOT NULL,
      lng               NUMERIC(10, 7) NOT NULL,
      formatted_address TEXT,
      street            TEXT,
      house_number      TEXT,
      house_number_addition TEXT,
      postcode          TEXT,
      city              TEXT,
      country           TEXT,
      provider          TEXT NOT NULL,
      created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    )"])
    (jdbc/execute! ds ["CREATE INDEX idx_geo_cache_coords ON geo_cache(lat, lng)"])
    ds))
