-- Add filters column to search_documents for filter-based search support
-- The column stores filter key/value pairs as a JSON string.
-- Example: {"tenant_id":"abc","status":"active"}
ALTER TABLE search_documents ADD COLUMN filters TEXT;
