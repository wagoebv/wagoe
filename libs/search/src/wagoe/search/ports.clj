(ns wagoe.search.ports
  "Port definitions for the wagoe-search library.

   ISearchStore    — low-level persistence (upsert, delete, search rows)
   ISearchEngine   — high-level orchestration (index-document!, search, suggest)
   ISearchTenantRemoval, ISearchStoreTenantDeletion — removal within a tenant")

;; =============================================================================
;; ISearchStore  — persistence
;; =============================================================================

(defprotocol ISearchStore
  "Low-level persistence protocol for search documents."

  (upsert-document! [this doc]
    "Insert or update a search document (upsert on index_id + entity_id).
     A doc whose :filters name a :tenant-id replaces only a row of that tenant
     or of none; over another tenant's it throws a :conflict, and a blank or
     collection :tenant-id a :validation-error (BOU-1121). A doc naming no
     tenant replaces only a row of none (BOU-1124). Tenant ids compare
     case-sensitively. A store of an app's own must keep this too.

     Args:
       doc - SearchDocument map

     Returns:
       doc")

  (delete-document! [this index-id entity-id]
    "Remove a search document.

     Args:
       index-id  - keyword
       entity-id - UUID

     Returns:
       nil")

  (search-documents [this index-id entity-type query opts]
    "Execute a full-text or fallback search.

     Args:
       index-id    - keyword
       entity-type - keyword
       query       - string (sanitized)
       opts        - map with :language :limit :offset :highlight?

     Returns:
       Vector of SearchResult maps")

  (count-results [this entity-type query opts]
    "Count matching search results.

     Args:
       entity-type - keyword
       query       - string
       opts        - map with :language :index-id

     Returns:
       integer")

  (suggest-documents [this index-id entity-type query opts]
    "Trigram similarity suggestions (PostgreSQL) or LIKE fallback (H2/SQLite).

     Args:
       index-id    - keyword
       entity-type - keyword
       query       - string
       opts        - map with :limit :threshold

     Returns:
       Vector of SearchResult maps")

  (count-documents [this index-id]
    "Count all indexed documents for a search index.

     Args:
       index-id - keyword

     Returns:
       integer"))

;; =============================================================================
;; ISearchEngine  — orchestration
;; =============================================================================

(defprotocol ISearchEngine
  "High-level search orchestration protocol."

  (index-document! [this index-id entity-id field-values opts]
    "Index a document for full-text search.

     Args:
       index-id     - keyword, a definition's :id
       entity-id    - UUID
       field-values - map of field-name -> string or seq value
       opts         - optional map with :metadata and :filter-values. On a
                      tenant-scoped index (`core.index/tenant-scoped?`)
                      :filter-values must hold a :tenant-id, or this throws a
                      :validation-error (BOU-1114). An entity id whose
                      document is another tenant's throws a :conflict
                      (BOU-1121).

     Returns:
       SearchDocument map")

  (remove-document! [this index-id entity-id]
    "Remove a document from a search index.

     Args:
       index-id  - keyword
       entity-id - UUID

     Returns:
       nil")

  (search [this index-id query opts]
    "Search for documents in a search index.

     Args:
       index-id - keyword
       query    - string (user search query)
       opts     - map: :limit :offset :highlight? :filters :all-tenants?
                  On a tenant-scoped index :filters must hold a :tenant-id
                  (one, or a set), or this throws a :validation-error;
                  `:all-tenants? true` reads every tenant (BOU-1114).

     Returns:
       SearchResponse map: {:results [...] :total int :query str :took-ms int}")

  (suggest [this index-id partial-query opts]
    "Return trigram-based query suggestions.

     Args:
       index-id      - keyword
       partial-query - string (partial user input)
       opts          - map: :limit :threshold :filters :all-tenants?, with
                       the tenant rule of `search`

     Returns:
       Vector of SearchResult maps")

  (list-indices [this]
    "List all registered search indices with document counts. A count spans
     every tenant, so show it to a platform admin only (BOU-992).

     Returns:
       Vector of maps {:id :entity-type :language :fields :doc-count}")

  (reindex! [this index-id documents]
    "Bulk reindex all documents for an index.

     Args:
       index-id  - keyword
       documents - seq of [entity-id field-values] pairs, or triples with
                   the opts of `index-document!`, which a tenant-scoped
                   index needs. A refused document stops the run; those
                   before it stay indexed

     Returns:
       {:indexed integer}"))

(defprotocol ISearchDefinitions
  "Its own protocol, as ISearchEngine is stable."
  (index-definition [this index-id]
    "The SearchDefinition this engine holds for `index-id`, or nil."))

(defprotocol ISearchTenantRemoval
  "Its own protocol, as ISearchEngine is stable (BOU-1114)."
  (remove-tenant-document! [this index-id entity-id tenant-id]
    "Remove a document from a search index only when it belongs to
     `tenant-id`, so one tenant's id cannot remove another's document.
     Returns nil."))

(defprotocol ISearchStoreTenantDeletion
  "Its own protocol, as ISearchStore is stable (BOU-1114)."
  (delete-tenant-document! [this index-id entity-id tenant-id]
    "Delete a search document only when its :tenant-id filter is `tenant-id`.
     Returns nil."))
