(ns wagoe.audience.shell.persistence
  "Database persistence for audience definitions and memberships.

   Implements IAudienceRepository using next.jdbc + HoneySQL.

   Column layout for audience_segments:
     audience_id  VARCHAR — keyword name (e.g. \"premium-users\")
     filters      TEXT/JSONB — serialised as JSON
     tags         TEXT/JSONB — serialised as JSON
     composition  TEXT/JSONB — optional, serialised as JSON
     cache_config TEXT/JSONB — optional, serialised as JSON

   JSON columns use cheshire for serialisation.  On PostgreSQL the
   columns are JSONB; on H2 (tests) they are stored as TEXT — both
   are handled by `->json` below."
  (:require [wagoe.core.utils.text :as text]
            [wagoe.audience.ports :as ports]
            [cheshire.core :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]))

;; =============================================================================
;; JSON helpers
;; =============================================================================

(defn- ->json
  "Serialise a Clojure value to a JSON string (nil-safe)."
  [value]
  (when (some? value)
    (json/generate-string value)))

(defn <-json
  "Deserialise a DB JSON value back to Clojure data.

   Public because it is the only decoder that knows about all three shapes a
   JSON column arrives in, and `cache.clj` had a fourth, narrower copy that
   handled maps and strings and returned nil for a PGobject — so on PostgreSQL
   every cached audience read its TTL as nil, missed the cache and recomputed
   its membership on every resolve (BOU-419 review).

   Handles:
   - nil                              → nil
   - already-parsed map / vector      → as-is
   - String                           → parsed with keyword keys
   - org.postgresql.util.PGobject     → .getValue then parsed"
  [value]
  (cond
    (nil? value)                         nil
    (map? value)                         value
    (vector? value)                      value
    (string? value)                      (json/parse-string value true)
    (= "org.postgresql.util.PGobject"
       (.getName (class value)))         (some-> (.getValue value)
                                                 (json/parse-string true))
    :else
    (do (log/warn "Unexpected JSON column type — cannot deserialize"
                  {:type (str (type value))})
        (throw (ex-info "Cannot deserialize JSON column value"
                        {:type (str (type value))})))))

;; =============================================================================
;; Schema
;; =============================================================================
;;
;; The module had no schema component, so a booted application had the audience
;; service and no `audience_segments` table: it started cleanly and threw on the
;; first write (BOU-419). The DDL existed twice — a PostgreSQL migration and an
;; H2 copy in the test fixture — and neither ran at boot.

(defn dialect
  "The adapter behind `datasource`, from its product name."
  [datasource]
  (with-open [conn (jdbc/get-connection datasource)]
    (let [product (text/lower-case (str (.getDatabaseProductName (.getMetaData conn))))]
      (cond
        (str/includes? product "h2")         :h2
        (str/includes? product "postgresql") :postgresql
        (str/includes? product "sqlite")     :sqlite
        (str/includes? product "mysql")      :mysql
        (str/includes? product "mariadb")    :mysql
        :else                                :unknown))))

(def ^:private column-types
  "How each adapter spells the three types this schema needs.

   The primary key carries no `DEFAULT`: only two of the four adapters have a
   UUID generator to put there, and `save-audience` supplies the id anyway.
   That leaves the column types as the whole of the difference (BOU-419 review).

   `:timestamp` carries a zone where the adapter has one. A bare TIMESTAMP
   stores wall-clock time and no zone, so what it means depends on the reader —
   MySQL's TIMESTAMP already normalises to UTC, and SQLite has no zone concept
   (BOU-431)."
  {:h2         {:uuid "UUID"     :json "TEXT"  :timestamp "TIMESTAMP WITH TIME ZONE"}
   :postgresql {:uuid "UUID"     :json "JSONB" :timestamp "TIMESTAMP WITH TIME ZONE"}
   :sqlite     {:uuid "TEXT"     :json "TEXT"  :timestamp "TIMESTAMP"}
   :mysql      {:uuid "CHAR(36)" :json "TEXT"  :timestamp "TIMESTAMP"}})

(defn audience-ddl
  "DDL for the audience tables, for one adapter.

   Written once here rather than as a PostgreSQL migration plus a hand-copied
   H2 variant in a test fixture, which is how those two drifted."
  [dialect]
  (let [{:keys [uuid json timestamp]} (or (get column-types dialect)
                                (throw (ex-info
                                        (str "No audience schema for database adapter "
                                             (pr-str dialect) ". Supported: "
                                             (str/join ", " (sort (map name (keys column-types)))) ".")
                                        {:type :configuration-error :dialect dialect})))
        uuid-type uuid
        json-type json
        ts-type   timestamp]
    (cond->
     [(str "CREATE TABLE IF NOT EXISTS audience_segments (
             id            " uuid-type " PRIMARY KEY,
             audience_id   VARCHAR(255) NOT NULL UNIQUE,
             label         VARCHAR(255) NOT NULL,
             description   TEXT,
             filters       " json-type " NOT NULL,
             composition   " json-type ",
             cache_config  " json-type ",
             tags          " json-type ",
             member_count  INTEGER DEFAULT 0,
             cached_at     " ts-type ",
             source        VARCHAR(50) DEFAULT 'dynamic',
             created_at    " ts-type " DEFAULT CURRENT_TIMESTAMP,
             updated_at    " ts-type " DEFAULT CURRENT_TIMESTAMP)")
     ;; MySQL has no CREATE INDEX IF NOT EXISTS, so the index is declared
     ;; inside the table, where CREATE TABLE IF NOT EXISTS makes it idempotent.
     ;; Emitting the standalone form there failed the boot after the tables had
     ;; already been created (BOU-419 review).
     (str "CREATE TABLE IF NOT EXISTS audience_memberships (
             audience_id   " uuid-type " NOT NULL,
             user_id       " uuid-type " NOT NULL,
             entered_at    " ts-type " DEFAULT CURRENT_TIMESTAMP,
             PRIMARY KEY (audience_id, user_id)"
          (if (= :mysql dialect)
            ",
             KEY idx_audience_memberships_user (user_id),
             FOREIGN KEY (audience_id) REFERENCES audience_segments(id) ON DELETE CASCADE)"
            ",
             FOREIGN KEY (audience_id) REFERENCES audience_segments(id) ON DELETE CASCADE)"))]

      (not= :mysql dialect)
      (conj "CREATE INDEX IF NOT EXISTS idx_audience_memberships_user
               ON audience_memberships(user_id)"))))

(defn initialize-audience-schema!
  "Create the audience tables if absent. Idempotent."
  [datasource]
  (log/info "Initializing audience schema")
  (doseq [statement (audience-ddl (dialect datasource))]
    (jdbc/execute! datasource [statement])))

;; =============================================================================
;; Keyword <-> string helpers
;; =============================================================================

(defn- kw->str [k] (when k (name k)))

(defn json-param
  "A JSON string as `dialect` accepts it in an INSERT or UPDATE.

   PostgreSQL declares these columns `JSONB` and refuses a string parameter —
   \"column is of type jsonb but expression is of type character varying\". The
   other three adapters store TEXT and take it as is. A cast rather than a
   `PGobject` so this needs no PostgreSQL class on the classpath, the way
   `<-json` above already avoids one on the read side.

   Audience had never run against PostgreSQL: its only fixture was H2, whose
   columns were TEXT, and the JSONB migration ran nowhere (BOU-419 review)."
  [dialect v]
  (if (and (= :postgresql dialect) (some? v))
    [:cast v :jsonb]
    v))

(defn uuid-value
  "A UUID as `dialect` stores it.

   H2 and PostgreSQL have a real UUID column and reject a string parameter —
   \"column is of type uuid but expression is of type character varying\".
   SQLite (TEXT) and MySQL (CHAR(36)) have no UUID type to convert to."
  [dialect u]
  (if (contains? #{:h2 :postgresql} dialect) u (str u)))
(defn- str->kw [s] (when s (keyword s)))

;; =============================================================================
;; Row <-> entity mapping
;; =============================================================================

(defn- definition->db
  "Convert an AudienceDefinition map to a DB row (snake_case, JSON-serialised).

   `:id` is the surrogate primary key and is generated here rather than by a
   column default: two of the four supported adapters have no UUID generator to
   default to. `save-audience` deletes and re-inserts, so this key changed on
   every save under the old default too — `find-segment-uuid` resolves it from
   `audience_id`, which is the identifier callers use."
  [dialect definition]
  (let [json* (partial json-param dialect)]
    {:id           (uuid-value dialect (random-uuid))
     :audience_id  (kw->str (:id definition))
     :label        (:label definition)
     :description  (:description definition)
     :filters      (json* (->json (:filters definition)))
     :composition  (json* (->json (:compose definition)))
     :cache_config (json* (->json (:cache-config definition)))
     :tags         (json* (->json (:tags definition)))
     :source       (or (kw->str (:source definition)) "dynamic")}))

(def ^:private filter-keyword-keys
  "Filter keys the compiler dispatches on, which must come back as keywords.

   `filter->sql` is a multimethod on `:type`, `sql-op` reads `:op`, and `:field`
   becomes a HoneySQL column. JSON has no keywords, so a stored definition
   reloaded as strings compiled to something else entirely: a `:demographics`
   filter that produced `[[:= :plan \"premium\"]]` in memory came back matching
   no type at all and degraded to a constant predicate, so every persisted
   audience resolved to the wrong set (BOU-419 review)."
  [:type :op :field])

(defn- restore-filter-keywords
  [filt]
  (if (map? filt)
    (reduce (fn [m k]
              (if (string? (get m k)) (update m k keyword) m))
            filt
            filter-keyword-keys)
    filt))

(defn- restore-composition-refs
  "Keywordize every `:ref` in a composition tree.

   A composition names other audiences by keyword, and the resolver looks them
   up in a keyword-keyed registry. Through JSON `{:ref :premium}` returns as
   `{:ref \"premium\"}`, which matches no registered audience and no stored one
   — so a composed audience answered :audience-not-found for a segment that was
   right there (BOU-419 review). Recursive, because compositions nest."
  [node]
  (cond
    (map? node)        (reduce-kv (fn [m k v]
                                    (assoc m k (if (and (= :ref k) (string? v))
                                                 (keyword v)
                                                 (restore-composition-refs v))))
                                  {}
                                  node)
    (sequential? node) (mapv restore-composition-refs node)
    :else              node))

(defn- db->definition
  "Convert a DB row map to an AudienceDefinition map (kebab-case)."
  [row]
  (when row
    (cond-> {:id          (str->kw (:audience_id row))
             :label       (:label row)
             :description (:description row)
             :filters     (mapv restore-filter-keywords (or (<-json (:filters row)) []))
             :source      (str->kw (or (:source row) "dynamic"))}
      (:composition row)  (assoc :compose (restore-composition-refs
                                           (<-json (:composition row))))
      (:cache_config row) (assoc :cache-config (<-json (:cache_config row)))
      (:tags row)         (assoc :tags         (<-json (:tags row)))
      (:member_count row) (assoc :member-count (:member_count row))
      (:cached_at row)    (assoc :cached-at    (:cached_at row)))))

;; =============================================================================
;; IAudienceRepository implementation
;; =============================================================================

(defrecord AudienceStore [datasource dialect]
  ports/IAudienceRepository

  (save-audience [_ definition]
    (log/debug "Saving audience" {:audience-id (:id definition)})
    (let [row (definition->db dialect definition)]
      (jdbc/with-transaction [tx datasource]
        ;; Delete-then-insert for H2/PostgreSQL compatibility (simple upsert)
        (jdbc/execute-one!
         tx
         (sql/format {:delete-from :audience_segments
                      :where       [:= :audience_id (:audience_id row)]})
         {:builder-fn rs/as-unqualified-lower-maps})
        (jdbc/execute-one!
         tx
         (sql/format {:insert-into :audience_segments
                      :values      [row]})
         {:builder-fn rs/as-unqualified-lower-maps})))
    definition)

  (find-audience [_ audience-id]
    (log/debug "Finding audience" {:audience-id audience-id})
    (let [row (jdbc/execute-one!
               datasource
               (sql/format {:select [:*]
                            :from   [:audience_segments]
                            :where  [:= :audience_id (kw->str audience-id)]})
               {:builder-fn rs/as-unqualified-lower-maps})]
      (db->definition row)))

  (list-audiences [_]
    (log/debug "Listing all audiences")
    (let [rows (jdbc/execute!
                datasource
                (sql/format {:select   [:*]
                             :from     [:audience_segments]
                             :order-by [[:created_at :asc]]})
                {:builder-fn rs/as-unqualified-lower-maps})]
      (mapv db->definition rows)))

  (list-audiences [this _filters]
    ;; Delegate to arity-1 for now; filter support can be added later
    (ports/list-audiences this))

  (delete-audience [_ audience-id]
    (log/debug "Deleting audience" {:audience-id audience-id})
    (jdbc/execute-one!
     datasource
     (sql/format {:delete-from :audience_segments
                  :where       [:= :audience_id (kw->str audience-id)]})
     {:builder-fn rs/as-unqualified-lower-maps})
    nil))

;; =============================================================================
;; Factory
;; =============================================================================

(defn create-audience-store
  "Create an AudienceStore backed by a JDBC datasource.

   Args:
     datasource - javax.sql.DataSource (HikariCP pool or plain)

   Returns:
     AudienceStore implementing IAudienceRepository"
  [datasource]
  ;; Detected once, at construction: it costs a connection, and the answer
  ;; cannot change for the life of the store.
  (->AudienceStore datasource (dialect datasource)))

;; =============================================================================
;; Membership helpers (not on the port protocol)
;; =============================================================================

(defn- find-segment-uuid
  "Look up the UUID primary key for an audience segment by its keyword id."
  [datasource audience-id]
  (let [row (jdbc/execute-one!
             datasource
             (sql/format {:select [:id]
                          :from   [:audience_segments]
                          :where  [:= :audience_id (kw->str audience-id)]})
             {:builder-fn rs/as-unqualified-lower-maps})]
    (:id row)))

(def ^:private membership-insert-chunk-size
  "Rows per multi-row INSERT statement when saving memberships."
  500)

(defn save-memberships!
  "Batch-insert user UUIDs into audience_memberships for an audience.

   Idempotent: one SELECT fetches the audience's existing user-ids, the
   already-present ids are removed in memory, and only the missing ones are
   inserted via chunked multi-row INSERTs — all inside a single transaction.
   This keeps the SQL portable across H2 and PostgreSQL (no ON CONFLICT)
   and avoids per-user round-trips.

   Args:
     datasource  - javax.sql.DataSource
     audience-id - keyword (e.g. :premium-users)
     user-ids    - collection of java.util.UUID

   Returns:
     nil"
  [datasource audience-id user-ids]
  (when (seq user-ids)
    (let [d        (dialect datasource)
          seg-uuid (find-segment-uuid datasource audience-id)]
      (when-not seg-uuid
        (throw (ex-info "Cannot save memberships: audience segment not found in DB"
                        {:type :audience-not-found :audience-id audience-id})))
      (jdbc/with-transaction [tx datasource]
        (let [existing (into #{}
                             (map (fn [row]
                                    (let [uid (:user_id row)]
                                      (if (instance? UUID uid)
                                        uid
                                        (UUID/fromString (str uid))))))
                             (jdbc/execute!
                              tx
                              (sql/format {:select [:user_id]
                                           :from   [:audience_memberships]
                                           :where  [:= :audience_id seg-uuid]})
                              {:builder-fn rs/as-unqualified-lower-maps}))
              ;; `set` dedupes the input (callers may pass any collection —
              ;; often already a set) without `distinct`, which chokes on
              ;; sets under Clojure 1.12.
              missing  (remove existing (set user-ids))]
          (doseq [chunk (partition-all membership-insert-chunk-size missing)]
            (jdbc/execute-one!
             tx
             (sql/format {:insert-into :audience_memberships
                          ;; Through `uuid-value`, because a CHAR(36) column
                          ;; gets a Java-serialised UUID object otherwise —
                          ;; MySQL rejected it as an "incorrect string value"
                          ;; of \xAC\xED… (BOU-419 review).
                          :values      (mapv (fn [uid]
                                               {:audience_id seg-uuid
                                                :user_id     (uuid-value d uid)})
                                             chunk)})
             {:builder-fn rs/as-unqualified-lower-maps})))))))

(defn get-memberships
  "Return all user UUIDs that are members of the given audience.

   Args:
     datasource  - javax.sql.DataSource
     audience-id - keyword

   Returns:
     Vector of java.util.UUID, or [] if the segment does not exist"
  [datasource audience-id]
  (let [seg-uuid (find-segment-uuid datasource audience-id)]
    (if (nil? seg-uuid)
      []
      (let [rows (jdbc/execute!
                  datasource
                  (sql/format {:select [:user_id]
                               :from   [:audience_memberships]
                               :where  [:= :audience_id seg-uuid]})
                  {:builder-fn rs/as-unqualified-lower-maps})]
        (mapv (fn [row]
                (let [uid (:user_id row)]
                  (if (instance? UUID uid)
                    uid
                    (UUID/fromString (str uid)))))
              rows)))))

(defn clear-memberships!
  "Delete all membership records for the given audience.

   Args:
     datasource  - javax.sql.DataSource
     audience-id - keyword

   Returns:
     nil"
  [datasource audience-id]
  (when-let [seg-uuid (find-segment-uuid datasource audience-id)]
    (jdbc/execute-one!
     datasource
     (sql/format {:delete-from :audience_memberships
                  :where       [:= :audience_id seg-uuid]})
     {:builder-fn rs/as-unqualified-lower-maps}))
  nil)
