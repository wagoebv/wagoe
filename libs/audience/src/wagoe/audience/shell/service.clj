(ns wagoe.audience.shell.service
  "Evaluation service for audience segments.

   Implements IAudienceResolver with a hybrid SQL + predicate pipeline:

     1. Check L1 DB cache (via get-cached-with-ttl when TTL is known).
     2. Load definition from in-process registry, fall back to repository.
     3. Compile definition into {:sql-clauses [...] :predicates [...]}.
     4. If :compose present, resolve composed segments recursively.
     5. Phase 1 — SQL: query user IDs via IUserDataSource.query-users-sql.
     6. Phase 2 — predicates: load-users for candidates, filter in-process.
     7. Build SegmentResult.
     8. Cache result when cache + TTL are configured.
     9. Return result.

   FC/IS boundary: this shell namespace is allowed to perform I/O."
  (:require [wagoe.audience.core.compiler :as compiler]
            [wagoe.audience.core.composition :as composition]
            [wagoe.audience.core.filter :as filter]
            [wagoe.audience.ports :as ports]
            [wagoe.audience.shell.registry :as registry]
            [clojure.tools.logging :as log])
  (:import [java.time Instant]))

;; =============================================================================
;; Internal helpers
;; =============================================================================

(defn- now-inst [] (Instant/now))

(defn- unwrap!
  "Raise the anomaly carried by an {:error {...}} map as a typed ex-info (so the
   HTTP error interceptor maps it to a response), otherwise return the value.
   The pure core validators return these anomalies; the shell turns them into
   the throw the boundary expects."
  [x]
  (if-let [err (:error x)]
    (throw (ex-info (:message err "Invalid audience definition") err))
    x))

(defn- load-definition
  "Load audience definition: registry first, then repository."
  [audience-id repository]
  (or (registry/get-audience audience-id)
      (when repository
        (ports/find-audience repository audience-id))))

(defn- ttl-minutes-for
  "Extract the TTL (in minutes) from a definition's :cache-config, or nil."
  [definition]
  (get-in definition [:cache-config :ttl-minutes]))

(defn- check-cache
  "Return a cached SegmentResult when a cache is available, else nil.
   TTL checking is delegated to the cache implementation (get-cached)."
  [cache-inst audience-id]
  (when cache-inst
    (ports/get-cached cache-inst audience-id)))

(defn- store-cache
  "Persist result to cache when cache + TTL are configured."
  [cache-inst audience-id definition result]
  (when (and cache-inst definition)
    (let [ttl (ttl-minutes-for definition)]
      (if ttl
        (ports/put-cached cache-inst audience-id result ttl)
        (log/debug "Skipping cache for audience — no ttl-minutes configured"
                   {:audience-id audience-id})))))

(defn- sql-clause-for-plan
  "Combine sql-clauses into a single HoneySQL :and clause, or nil if empty."
  [sql-clauses]
  (cond
    (empty? sql-clauses)  nil
    (= 1 (count sql-clauses)) (first sql-clauses)
    :else                 (into [:and] sql-clauses)))

(defn- run-sql-phase
  "Query candidate user IDs using IUserDataSource.query-users-sql.
   Returns a set of user IDs, or all-user-ids marker when no SQL clause."
  [user-ds sql-clauses]
  (let [clause (sql-clause-for-plan sql-clauses)]
    (if clause
      (set (ports/query-users-sql user-ds clause))
      ;; No SQL filter — load all users (empty filter = universe)
      (set (ports/query-users-sql user-ds nil)))))

(defn- run-predicate-phase
  "Filter candidate user IDs by loading full user records and applying predicates.
   Returns the final set of matching user IDs."
  [user-ds candidate-ids predicates]
  (if (empty? predicates)
    candidate-ids
    (let [users     (ports/load-users user-ds candidate-ids)
          combined  (apply every-pred predicates)]
      (into #{} (comp (filter combined) (map :id)) users))))

;; =============================================================================
;; IAudienceResolver implementation
;; =============================================================================

(defrecord AudienceService [repository cache user-data-source]
  ports/IAudienceResolver

  (resolve-audience [this audience-id]
    (ports/resolve-audience this audience-id {}))

  (resolve-audience [this audience-id opts]
    (log/debug "Resolving audience" {:audience-id audience-id :opts opts})
    (let [force-refresh? (:force-refresh? opts false)
          definition     (load-definition audience-id repository)]

      ;; Cache check (skip when force-refresh? is true)
      (if-let [cached (when-not force-refresh?
                        (check-cache cache audience-id))]
        (do
          (log/debug "Cache hit for audience" {:audience-id audience-id})
          cached)

        ;; Full evaluation
        (do
          (when (nil? definition)
            (throw (ex-info "Audience not found"
                            {:type :audience-not-found :audience-id audience-id})))

          (let [eval-date (or (:as-of opts) (java.time.LocalDate/now))
                ;; Validate filters up front (pure core check); raise a typed
                ;; error before compiling if any filter is unknown/unsupported.
                _ (unwrap! (filter/explain-filters (:filters definition)))
                {:keys [sql-clauses predicates]} (compiler/compile-segment definition {:now eval-date})]

            ;; If :compose present, resolve composition tree
            (if-let [compose (:compose definition)]
              ;; Composition path — resolve referenced segments via recursive call
              (let [lookup  (fn [id]
                              (let [result (ports/resolve-audience this id)]
                                {:user-ids (:user-ids result)}))
                    ;; Validate the composition tree (structure + refs) before
                    ;; evaluating it, so bad trees raise instead of failing safe.
                    _ (unwrap! (composition/explain-composition compose lookup))
                    user-ids (composition/resolve-and-compose compose lookup)
                    result   {:user-ids     user-ids
                              :count        (count user-ids)
                              :cached?      false
                              :evaluated-at (now-inst)}]
                (store-cache cache audience-id definition result)
                result)

              ;; Direct evaluation path
              (let [candidates  (run-sql-phase user-data-source sql-clauses)
                    final-ids   (run-predicate-phase user-data-source candidates predicates)
                    result      {:user-ids     final-ids
                                 :count        (count final-ids)
                                 :cached?      false
                                 :evaluated-at (now-inst)}]
                (store-cache cache audience-id definition result)
                result)))))))

  (member? [this audience-id user-id]
    (let [result (ports/resolve-audience this audience-id)]
      (contains? (:user-ids result) user-id))))

;; =============================================================================
;; Factory
;; =============================================================================

(defn create-audience-service
  "Create an AudienceService.

   Args:
     opts - map with:
       :repository       - IAudienceRepository (optional, for DB-stored definitions)
       :cache            - AudienceCache (optional)
       :user-data-source - IUserDataSource (required)

   Returns:
     AudienceService implementing IAudienceResolver"
  [{:keys [repository cache user-data-source]}]
  (->AudienceService repository cache user-data-source))
