-- The upload index: who stored which file, and when. Data export and upload
-- retention read it, since a storage backend cannot say who owns a file.
-- :wagoe.storage/db-schema runs this file too, for installations that boot
-- without migrating.
CREATE TABLE IF NOT EXISTS storage_uploads (
  file_key      VARCHAR(512) NOT NULL PRIMARY KEY,
  user_id       VARCHAR(255),
  tenant_id     VARCHAR(255),
  size_bytes    BIGINT NOT NULL,
  content_type  VARCHAR(255),
  stored_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
--;;
CREATE INDEX IF NOT EXISTS idx_storage_uploads_user ON storage_uploads (user_id);
--;;
CREATE INDEX IF NOT EXISTS idx_storage_uploads_tenant ON storage_uploads (tenant_id);
--;;
CREATE INDEX IF NOT EXISTS idx_storage_uploads_stored_at ON storage_uploads (stored_at);
