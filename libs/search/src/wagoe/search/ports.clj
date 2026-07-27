(ns wagoe.search.ports
  "Port definitions for the wagoe-search library.

   Three protocols cover the full lifecycle:

   ISearchStore    — low-level persistence (upsert, delete, search rows)
   ISearchEngine   — high-level orchestration (index-document!, search, suggest)
   ISearchRegistry — in-process registry of defsearch definitions")

;; =============================================================================
;; ISearchStore  — persistence
;; =============================================================================

(defprotocol ISearchStore
  "Low-level persistence protocol for search documents."

  (upsert-document! [this doc]
    "Insert or update a search document (upsert on index_id + entity_id).

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
       index-id     - keyword (from defsearch :id)
       entity-id    - UUID
       field-values - map of field-name -> string or seq value
       opts         - optional map with :metadata

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
       opts     - map: :limit :offset :highlight?

     Returns:
       SearchResponse map: {:results [...] :total int :query str :took-ms int}")

  (suggest [this index-id partial-query opts]
    "Return trigram-based query suggestions.

     Args:
       index-id      - keyword
       partial-query - string (partial user input)
       opts          - map: :limit :threshold

     Returns:
       Vector of SearchResult maps")

  (list-indices [this]
    "List all registered search indices with document counts.

     Returns:
       Vector of maps {:id :entity-type :language :fields :doc-count}")

  (reindex! [this index-id documents]
    "Bulk reindex all documents for an index.

     Args:
       index-id  - keyword
       documents - seq of [entity-id field-values] pairs

     Returns:
       {:indexed integer}"))
