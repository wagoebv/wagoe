(ns wagoe.core.utils.migration-id
  "Ids for new app migrations.

   Apps and framework libraries share one `schema_migrations` id space, and
   framework ids end in an odd second (test/wagoe/migration_ids_test.clj). So
   an app id ends in an even one and never collides with a framework id."
  (:import (java.time LocalDateTime)
           (java.time.format DateTimeFormatter)))

(def ^:private id-format (DateTimeFormatter/ofPattern "yyyyMMddHHmmss"))

(defn id-of
  "The numeric id a migration file name starts with, or nil."
  [file-name]
  (second (re-find #"^(\d+)-" file-name)))

(defn next-id
  "First even-second yyyyMMddHHmmss id at or after `now`, a UTC LocalDateTime,
   not in `used`.

   Two migrations made in one second would share an id, and migratus then
   refuses the whole run, so step forward until the id is free. Stepping
   seconds rather than adding sub-second digits keeps ids 14 digits: a longer
   one would sort after every existing migration forever."
  [used ^LocalDateTime now]
  (loop [^LocalDateTime t (cond-> now (odd? (.getSecond now)) (.plusSeconds 1))]
    (let [id (.format t id-format)]
      (if (contains? used id)
        (recur (.plusSeconds t 2))
        id))))
