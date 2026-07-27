(ns wagoe.search.schema
  "Malli schemas for the boundary-search library.

   All internal data uses kebab-case keywords.
   snake_case conversion happens only at DB/HTTP boundaries.")

(def Language
  "Supported PostgreSQL FTS language configurations."
  [:enum :simple :english :dutch :german :french :spanish :italian
   :portuguese :russian :swedish :norwegian :danish :finnish])

(def FieldWeight
  "FTS field weight (A=highest, D=lowest)."
  [:enum :a :b :c :d])

(def SearchField
  "A single indexed field configuration."
  [:map {:closed true}
   [:name keyword?]
   [:weight {:optional true} FieldWeight]])

(def SearchOptions
  "Optional search behaviour settings."
  [:map
   [:highlight?  {:optional true} boolean?]
   [:trigrams?   {:optional true} boolean?]
   [:max-results {:optional true} pos-int?]])

(def SearchDefinition
  "A search index definition created by defsearch."
  [:map {:closed false}
   [:id          keyword?]
   [:entity-type keyword?]
   [:language    {:optional true} Language]
   [:fields      [:vector SearchField]]
   ;; Optional list of field keywords that can be used as WHERE-clause filters.
   ;; Declares which fields are filterable; actual filter values are passed at
   ;; index time via :filter-values in opts and at query time via :filters in opts.
   [:filters     {:optional true} [:vector keyword?]]
   [:options     {:optional true} SearchOptions]])

(def SearchDocument
  "An indexed document stored in the search_documents table."
  [:map
   [:id          string?]
   [:index-id    keyword?]
   [:entity-type keyword?]
   [:entity-id   uuid?]
   [:language    {:optional true} string?]
   [:weight-a    {:optional true} [:maybe string?]]
   [:weight-b    {:optional true} [:maybe string?]]
   [:weight-c    {:optional true} [:maybe string?]]
   [:weight-d    {:optional true} [:maybe string?]]
   [:content-all {:optional true} string?]
   [:metadata    {:optional true} [:maybe map?]]
   ;; JSON-serialised filter key-value pairs (snake_case keys in storage).
   [:filters     {:optional true} [:maybe map?]]
   [:updated-at  inst?]])

(def SearchResult
  "A single search result row."
  [:map
   [:entity-type keyword?]
   [:entity-id   uuid?]
   [:rank        {:optional true} number?]
   [:snippet     {:optional true} [:maybe string?]]
   [:metadata    {:optional true} [:maybe map?]]])

(def SearchResponse
  "Full search response."
  [:map
   [:results  [:vector SearchResult]]
   [:total    int?]
   [:query    string?]
   [:took-ms  {:optional true} int?]])
