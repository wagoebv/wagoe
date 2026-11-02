(ns wagoe.audience.ports
  "Port definitions for the wagoe-audience library.

   IAudienceResolver    — evaluate audiences and check membership
   IAudienceRepository  — persist and retrieve audience definitions
   IAudienceCache       — cache evaluated audience results
   IUserDataSource      — load users for filter evaluation
   IUserQuery           — find the users matching a set of filters")

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

  (create-audience [this definition]
    "Persist a new audience definition.

     Returns:
       saved definition map

     Throws:
       ex-info {:type :audience-exists} when its id is taken")

  (update-audience [this definition]
    "Replace a stored audience definition.

     Returns:
       saved definition map

     Throws:
       ex-info {:type :audience-not-found} when its id is not stored")

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

  (^{:deprecated "1.2" :superseded-by "wagoe.audience.ports/query-user-ids"}
   query-users-sql [this clause]
    "Deprecated: implement IUserQuery instead; removed in 2.0 (BOU-875).

     Ids of the users matching `clause`, a where clause the core compiled from
     the filters. nil means every user.

     Returns:
       Vector of user IDs (UUIDs)")

  (load-users [this user-ids]
    "Load full user records by a set of UUIDs.

     Args:
       user-ids - collection of UUID

     Returns:
       Vector of user maps (kebab-case keys)"))

;; =============================================================================
;; IUserQuery  — candidate selection
;; =============================================================================

(defprotocol IUserQuery
  "Find users by filter, in whatever query language the store speaks.
   Replaces IUserDataSource/query-users-sql; the service uses it when the
   source implements it (BOU-875)."

  (query-user-ids [this filters]
    "Ids of the users matching every filter.

     Args:
       filters - AudienceFilter maps as a definition holds them, date filters
                 carrying :now: those `filter->sql` can express. The rest run
                 as predicates over load-users. Empty means every user.

     Returns:
       Vector of user IDs (UUIDs)"))
