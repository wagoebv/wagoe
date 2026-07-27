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
  (:require [wagoe.audience.ports :as ports]
            [cheshire.core :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
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

(defn- <-json
  "Deserialise a DB JSON value back to Clojure data.

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
;; Keyword <-> string helpers
;; =============================================================================

(defn- kw->str [k] (when k (name k)))
(defn- str->kw [s] (when s (keyword s)))

;; =============================================================================
;; Row <-> entity mapping
;; =============================================================================

(defn- definition->db
  "Convert an AudienceDefinition map to a DB row (snake_case, JSON-serialised)."
  [definition]
  {:audience_id  (kw->str (:id definition))
   :label        (:label definition)
   :description  (:description definition)
   :filters      (->json (:filters definition))
   :composition  (->json (:compose definition))
   :cache_config (->json (:cache-config definition))
   :tags         (->json (:tags definition))
   :source       (or (kw->str (:source definition)) "dynamic")})

(defn- db->definition
  "Convert a DB row map to an AudienceDefinition map (kebab-case)."
  [row]
  (when row
    (cond-> {:id          (str->kw (:audience_id row))
             :label       (:label row)
             :description (:description row)
             :filters     (or (<-json (:filters row)) [])
             :source      (str->kw (or (:source row) "dynamic"))}
      (:composition row)  (assoc :compose (<-json (:composition row)))
      (:cache_config row) (assoc :cache-config (<-json (:cache_config row)))
      (:tags row)         (assoc :tags         (<-json (:tags row)))
      (:member_count row) (assoc :member-count (:member_count row))
      (:cached_at row)    (assoc :cached-at    (:cached_at row)))))

;; =============================================================================
;; IAudienceRepository implementation
;; =============================================================================

(defrecord AudienceStore [datasource]
  ports/IAudienceRepository

  (save-audience [_ definition]
    (log/debug "Saving audience" {:audience-id (:id definition)})
    (let [row (definition->db definition)]
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
  (->AudienceStore datasource))

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
    (let [seg-uuid (find-segment-uuid datasource audience-id)]
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
                          :values      (mapv (fn [uid]
                                               {:audience_id seg-uuid
                                                :user_id     uid})
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
