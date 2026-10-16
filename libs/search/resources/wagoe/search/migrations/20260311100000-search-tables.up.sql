-- search_documents: indexed content for full-text search
--
-- Supports two query strategies:
--   PostgreSQL: to_tsvector(language, ...) + plainto_tsquery + ts_rank + ts_headline
--   Fallback   : LOWER(content_all) LIKE ? (H2, SQLite, dev/test)
--
-- Field weight columns (A=highest, D=lowest) allow weighted ranking.
-- content_all is the concatenation of all weight columns for fallback LIKE search.
-- Key and indexed columns are VARCHAR, since MySQL cannot index TEXT (BOU-651).
CREATE TABLE IF NOT EXISTS search_documents (
  id          VARCHAR(255) NOT NULL PRIMARY KEY,
  index_id    VARCHAR(255) NOT NULL,
  entity_type VARCHAR(255) NOT NULL,
  entity_id   VARCHAR(255) NOT NULL,
  language    TEXT NOT NULL DEFAULT 'english',
  weight_a    TEXT NOT NULL DEFAULT '',
  weight_b    TEXT NOT NULL DEFAULT '',
  weight_c    TEXT NOT NULL DEFAULT '',
  weight_d    TEXT NOT NULL DEFAULT '',
  content_all TEXT NOT NULL DEFAULT '',
  metadata    TEXT,
  updated_at  VARCHAR(64) NOT NULL,
  UNIQUE (index_id, entity_id)
);
--;; 
CREATE INDEX IF NOT EXISTS idx_search_documents_index_id
  ON search_documents (index_id);
--;; 
CREATE INDEX IF NOT EXISTS idx_search_documents_entity
  ON search_documents (entity_type, entity_id);
--;; 
CREATE INDEX IF NOT EXISTS idx_search_documents_updated
  ON search_documents (updated_at);

-- PostgreSQL GIN index for full-text search (run this on PostgreSQL only):
--
-- CREATE INDEX IF NOT EXISTS idx_search_documents_fts
--   ON search_documents
--   USING GIN (
--     to_tsvector(language,
--       coalesce(weight_a,'') || ' ' ||
--       coalesce(weight_b,'') || ' ' ||
--       coalesce(weight_c,'') || ' ' ||
--       coalesce(weight_d,''))
--   );
--
-- pg_trgm index for trigram similarity suggestions (run this on PostgreSQL only):
--
-- CREATE EXTENSION IF NOT EXISTS pg_trgm;
-- CREATE INDEX IF NOT EXISTS idx_search_documents_trgm
--   ON search_documents
--   USING GIN (content_all gin_trgm_ops);
