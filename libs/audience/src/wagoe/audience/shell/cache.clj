(ns wagoe.audience.shell.cache
  "DB-backed cache for evaluated audience results.

   L1 — audience_segments.cached_at + audience_memberships table.
         A result is fresh if cached_at is within TTL minutes.
         put-cached writes memberships and stamps cached_at + member_count.
         get-cached reads memberships when stamp is fresh, else nil.
         invalidate / invalidate-all clear cached_at and memberships.

   L2 — wagoe-cache (Redis / in-memory) can be layered in later.
         The wagoe-cache param is accepted but not yet wired."
  (:require [wagoe.audience.ports :as ports]
            [wagoe.audience.shell.persistence :as persistence]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.time Instant]
           [java.time.temporal ChronoUnit]))

;; =============================================================================
;; Internal DB helpers
;; =============================================================================

(defn- kw->str [k] (when k (name k)))

(defn- stamp-segment!
  "Update cached_at and member_count for a segment row."
  [datasource audience-id member-count]
  (jdbc/execute-one!
   datasource
   (sql/format {:update :audience_segments
                :set    {:cached_at    [:raw "CURRENT_TIMESTAMP"]
                         :member_count member-count}
                :where  [:= :audience_id (kw->str audience-id)]})
   {:builder-fn rs/as-unqualified-lower-maps}))

(defn- clear-stamp!
  "Set cached_at = NULL and member_count = 0 for a segment."
  [datasource audience-id]
  (jdbc/execute-one!
   datasource
   (sql/format {:update :audience_segments
                :set    {:cached_at    nil
                         :member_count 0}
                :where  [:= :audience_id (kw->str audience-id)]})
   {:builder-fn rs/as-unqualified-lower-maps}))

(defn- clear-all-stamps!
  "Set cached_at = NULL and member_count = 0 for all segments."
  [datasource]
  (jdbc/execute!
   datasource
   (sql/format {:update :audience_segments
                :set    {:cached_at    nil
                         :member_count 0}})
   {:builder-fn rs/as-unqualified-lower-maps}))

(defn- load-segment-cache-row
  "Return the raw cache-relevant columns for a segment, or nil.
   Includes cache_config so get-cached can derive the TTL."
  [datasource audience-id]
  (jdbc/execute-one!
   datasource
   (sql/format {:select [:cached_at :member_count :cache_config]
                :from   [:audience_segments]
                :where  [:= :audience_id (kw->str audience-id)]})
   {:builder-fn rs/as-unqualified-lower-maps}))

(defn- fresh?
  "Return true if cached-at instant is within ttl-minutes from now."
  [^java.sql.Timestamp cached-at ttl-minutes]
  (when cached-at
    (let [cached-inst  (.toInstant cached-at)
          expiry-inst  (.plus cached-inst ttl-minutes ChronoUnit/MINUTES)
          now          (Instant/now)]
      (.isBefore now expiry-inst))))

;; =============================================================================
;; IAudienceCache implementation — L1 only (DB-backed)
;; =============================================================================

(defrecord AudienceCache [datasource wagoe-cache]
  ports/IAudienceCache

  (put-cached [_ audience-id result ttl-minutes]
    (when-not ttl-minutes
      (throw (ex-info "put-cached requires non-nil ttl-minutes"
                      {:type :validation-error :audience-id audience-id})))
    (log/debug "Caching audience result"
               {:audience-id audience-id :count (:count result) :ttl-minutes ttl-minutes})
    (let [user-ids (:user-ids result)]
      ;; Write memberships
      (persistence/clear-memberships! datasource audience-id)
      (persistence/save-memberships! datasource audience-id user-ids)
      ;; Stamp the segment row
      (stamp-segment! datasource audience-id (count user-ids))
      ;; Store TTL in cache_config so get-cached can read it back
      (jdbc/execute-one!
       datasource
       (sql/format {:update :audience_segments
                    :set    {:cache_config (json/generate-string {:ttl-minutes ttl-minutes})}
                    :where  [:= :audience_id (kw->str audience-id)]})
       {:builder-fn rs/as-unqualified-lower-maps}))
    result)

  (get-cached [_ audience-id]
    (log/debug "Checking cache for audience" {:audience-id audience-id})
    (let [row (load-segment-cache-row datasource audience-id)]
      (when row
        (let [cached-at   (:cached_at row)
              cache-cfg   (when-let [v (:cache_config row)]
                            (cond
                              (map? v)    v
                              (string? v) (json/parse-string v true)
                              :else       nil))
              ttl-minutes (get cache-cfg :ttl-minutes)]
          (when (and cached-at ttl-minutes
                     (fresh? cached-at ttl-minutes))
            (let [user-ids (set (persistence/get-memberships datasource audience-id))]
              {:user-ids     user-ids
               :count        (count user-ids)
               :cached?      true
               :evaluated-at (.toInstant ^java.sql.Timestamp cached-at)}))))))

  (invalidate [_ audience-id]
    (log/debug "Invalidating cache for audience" {:audience-id audience-id})
    (clear-stamp! datasource audience-id)
    (persistence/clear-memberships! datasource audience-id)
    nil)

  (invalidate-all [_]
    (log/debug "Invalidating all audience caches")
    (clear-all-stamps! datasource)
    ;; memberships are cleared by the service; here we leave them (stamp=nil
    ;; signals stale). For correctness we also delete memberships.
    (jdbc/execute!
     datasource
     (sql/format {:delete-from :audience_memberships})
     {:builder-fn rs/as-unqualified-lower-maps})
    nil))

;; =============================================================================
;; TTL-aware helper used by the service
;; =============================================================================

(defn get-cached-with-ttl
  "Check the DB cache for audience-id using explicit ttl-minutes.

   Returns a SegmentResult map (with :cached? true) if fresh, else nil.

   Args:
     cache       - AudienceCache instance
     audience-id - keyword
     ttl-minutes - integer (0 = always stale)"
  [^AudienceCache cache audience-id ttl-minutes]
  (let [datasource (:datasource cache)
        row        (load-segment-cache-row datasource audience-id)]
    (when row
      (let [cached-at (:cached_at row)]
        (when (and cached-at (fresh? cached-at ttl-minutes))
          (let [user-ids (set (persistence/get-memberships datasource audience-id))]
            {:user-ids     user-ids
             :count        (count user-ids)
             :cached?      true
             :evaluated-at (.toInstant ^java.sql.Timestamp cached-at)}))))))

;; =============================================================================
;; Factory
;; =============================================================================

(defn create-audience-cache
  "Create a DB-backed AudienceCache.

   Args:
     datasource      - javax.sql.DataSource
     wagoe-cache  - optional wagoe-cache instance for L2 (ignored for now)

   Returns:
     AudienceCache implementing IAudienceCache"
  ([datasource]
   (create-audience-cache datasource nil))
  ([datasource wagoe-cache]
   (->AudienceCache datasource wagoe-cache)))
