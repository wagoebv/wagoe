(ns wagoe.audience.shell.adapters.user-sql
  "IUserDataSource over the application's users table.

   The audience service needs one to run at all — its init-key throws without a
   `:user-data-source` — and the protocol had no implementation anywhere, so the
   module could not boot and no application ever activated it (BOU-419).

   Queried by table name rather than through the user module: audience depends
   on no other Wagoe library, and reaching into `wagoe.user.shell.*` would be
   the cross-module coupling `bb check:ports` exists to prevent. `:table` is
   configurable for an application whose users live elsewhere."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [wagoe.audience.core.filter :as filter]
            [wagoe.audience.ports :as ports]
            [wagoe.audience.shell.persistence :as persistence])
  (:import [java.util UUID]))

(def ^:private opts {:builder-fn rs/as-unqualified-lower-maps})

(def default-field-mapping
  "Filter column names that differ from the framework's `users` table.

   `filter->sql` for `:last-active` emits `last_active_at`, a column no Wagoe
   users table has — it records `last_login` — so that documented filter failed
   with a missing-column error against the source this module ships (BOU-419
   review). Override with `:field-mapping` for a schema that spells it
   differently again."
  {:last_active_at :last_login})

(defn- storage-value
  "An `Instant` as this dialect stores timestamps.

   The framework keeps them as ISO-8601 `TEXT` on SQLite — its own adapter says
   so — and as a real timestamp type everywhere else. SQLite compares across
   storage classes by ordering integers before text, so binding a Timestamp
   there does not fail: `>=` matches every row and `<=` matches none (BOU-425)."
  [dialect ^java.time.Instant instant]
  (if (= :sqlite dialect)
    (str instant)
    (java.sql.Timestamp/from instant)))

(defn- comparison?
  "True for `[op column operand …]` carrying at least one Instant operand.

   Covers `[:>= :col inst]` and `[:between :col from to]` alike — a custom
   `filter->sql` may return either, and the narrow three-element shape this
   replaced left a `:between`'s instants unconverted (BOU-425 review)."
  [x]
  (and (vector? x)
       (keyword? (first x))
       (keyword? (second x))
       (some #(instance? java.time.Instant %) (drop 2 x))))

(defn- prepare-clause
  "Rename mapped columns and put timestamps in the form this driver compares.

   The core compiles a cutoff as an `Instant`: it is pure, so how that reaches
   the database is this layer's business.

   Text alone is not enough on SQLite. `Instant/toString` omits the fraction
   when it is zero, so a cutoff renders `…T00:00:00Z` while a value one
   millisecond later renders `…T00:00:00.001Z` — and `.` sorts before `Z`,
   putting the later instant first. Both sides of a comparison go through
   SQLite's `datetime()`, which is fixed width; comparison is then to the
   second, which is inside what a filter defined in whole days promises.

   An Instant outside a comparison — nested in a clause shape this does not
   recognise — still becomes the dialect's storage value rather than being left
   raw for the driver to guess at."
  [dialect mapping clause]
  (walk/prewalk
   (fn [x]
     (cond
       (comparison? x)
       (let [[op column & operands] x
             column (get mapping column column)]
         (if (= :sqlite dialect)
           (into [op [:datetime column]]
                 (map (fn [v] (if (instance? java.time.Instant v)
                                [:datetime (str v)]
                                v)))
                 operands)
           (into [op column]
                 (map (fn [v] (if (instance? java.time.Instant v)
                                (java.sql.Timestamp/from v)
                                v)))
                 operands)))

       (instance? java.time.Instant x) (storage-value dialect x)
       (keyword? x)                    (get mapping x x)
       :else                           x))
   clause))

(defn- ->uuid
  "Ids come back as UUID on PostgreSQL and as a String on some adapters."
  [v]
  (cond
    (uuid? v)   v
    (string? v) (try (UUID/fromString v) (catch IllegalArgumentException _ v))
    :else       v))

(defn- kebab-row
  "Snake_case columns to kebab-case keys, at the persistence boundary and
   nowhere else. A local walk rather than `wagoe.core.utils.case-conversion`
   because this library depends on no other Wagoe library, as its siblings do
   not either."
  [row]
  (reduce-kv (fn [m k v]
               (assoc m (keyword (str/replace (name k) \_ \-)) v))
             {}
             row))

(defrecord SqlUserDataSource [datasource table field-mapping dialect]
  ports/IUserDataSource

  (query-users-sql [_ honeysql-clause]
    ;; A nil clause is "every user", not "no users" — an empty filter is the
    ;; universe, and returning nothing for it silently empties every audience
    ;; whose filters are all predicate-phase (libs/audience/AGENTS.md).
    (let [q (cond-> {:select [:id] :from [table]}
              (some? honeysql-clause)
              (assoc :where (prepare-clause dialect field-mapping honeysql-clause)))]
      (mapv (comp ->uuid :id) (jdbc/execute! datasource (sql/format q) opts))))

  (load-users [_ user-ids]
    (if (empty? user-ids)
      []
      (->> (jdbc/execute! datasource
                          (sql/format {:select [:*]
                                       :from   [table]
                                       :where  [:in :id (mapv ->uuid user-ids)]})
                          opts)
           ;; Credentials stay out of the rows a predicate sees, whatever its
           ;; filter names as its field.
           (mapv (fn [row]
                   (into {:id (->uuid (:id row))}
                         (remove (comp filter/secret-field? key))
                         (dissoc (kebab-row row) :id))))))))

(defn create-sql-user-data-source
  "An IUserDataSource reading `table` (default `:users`) from `datasource`.

   `field-mapping` renames filter columns that this table spells differently;
   `default-field-mapping` covers the framework's own users table."
  ([datasource] (create-sql-user-data-source datasource :users nil))
  ([datasource table] (create-sql-user-data-source datasource table nil))
  ([datasource table field-mapping]
   (->SqlUserDataSource datasource
                        (or table :users)
                        (or field-mapping default-field-mapping)
                        ;; Detected once: it costs a connection, and the answer
                        ;; cannot change for the life of the source.
                        (persistence/dialect datasource))))
