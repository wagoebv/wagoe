(ns wagoe.audience.ports
  "Port definitions for the wagoe-audience library.

   Four protocols cover the full lifecycle:

   IAudienceResolver    — evaluate audiences and check membership
   IAudienceRepository  — persist and retrieve audience definitions
   IAudienceCache       — cache evaluated audience results
   IUserDataSource      — query users for filter evaluation")

;; =============================================================================
;; IAudienceResolver  — evaluation
;; =============================================================================

(defprotocol IAudienceResolver
  "Protocol for evaluating audience membership."

  (resolve-audience [this audience-id] [this audience-id opts]
    "Evaluate an audience and return the full result set.

     Args:
       audience-id - keyword identifying the audience definition
       opts        - optional map with :force-refresh? :as-of

     Returns:
       SegmentResult map: {:user-ids #{...} :count n :cached? bool :evaluated-at inst}")

  (member? [this audience-id user-id]
    "Quick membership check for a single user.

     Args:
       audience-id - keyword
       user-id     - UUID

     Returns:
       boolean"))

;; =============================================================================
;; IAudienceRepository  — persistence
;; =============================================================================

(defprotocol IAudienceRepository
  "Protocol for persisting and retrieving audience definitions."

  (save-audience [this definition]
    "Persist an audience definition (insert or update).

     Args:
       definition - AudienceDefinition map

     Returns:
       saved definition map")

  (find-audience [this audience-id]
    "Retrieve an audience definition by id.

     Args:
       audience-id - keyword or UUID

     Returns:
       AudienceDefinition map, or nil if not found")

  (list-audiences [this] [this filters]
    "List all stored audience definitions, optionally filtered.

     Args:
       filters - optional map with :tags :label-contains

     Returns:
       Vector of AudienceDefinition maps")

  (delete-audience [this audience-id]
    "Delete an audience definition by id.

     Args:
       audience-id - keyword or UUID

     Returns:
       nil"))

;; =============================================================================
;; IAudienceCache  — result caching
;; =============================================================================

(defprotocol IAudienceCache
  "Protocol for caching evaluated audience results."

  (get-cached [this audience-id]
    "Retrieve a cached SegmentResult for an audience.

     Args:
       audience-id - keyword

     Returns:
       SegmentResult map, or nil if not cached / expired")

  (put-cached [this audience-id result ttl-minutes]
    "Store a SegmentResult in cache with a TTL.

     Args:
       audience-id  - keyword
       result       - SegmentResult map
       ttl-minutes  - integer, cache lifetime in minutes

     Returns:
       result")

  (invalidate [this audience-id]
    "Remove a single cached audience result.

     Args:
       audience-id - keyword

     Returns:
       nil")

  (invalidate-all [this]
    "Remove all cached audience results.

     Returns:
       nil"))

;; =============================================================================
;; IUserDataSource  — user querying
;; =============================================================================

(defprotocol IUserDataSource
  "Protocol for querying user data during filter evaluation."

  (query-users-sql [this honeysql-clause]
    "Execute a HoneySQL query against the users table.

     Args:
       honeysql-clause - HoneySQL map (select/where/join etc.)

     Returns:
       Vector of user IDs (UUIDs)")

  (load-users [this user-ids]
    "Load full user records by a set of UUIDs.

     Args:
       user-ids - collection of UUID

     Returns:
       Vector of user maps (kebab-case keys)"))
