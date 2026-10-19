-- Data exports produced by a background job, and the archive each left in
-- file storage. :wagoe.storage/db-schema runs this file too, for
-- installations that boot without migrating.
CREATE TABLE IF NOT EXISTS storage_export_jobs (
  id            VARCHAR(36) NOT NULL PRIMARY KEY,
  subject_type  VARCHAR(16) NOT NULL,
  subject_id    VARCHAR(255) NOT NULL,
  requested_by  VARCHAR(255) NOT NULL,
  status        VARCHAR(16) NOT NULL,
  file_key      VARCHAR(512),
  error         VARCHAR(1024),
  created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
  finished_at   TIMESTAMP WITH TIME ZONE,
  expires_at    TIMESTAMP WITH TIME ZONE
);
--;;
CREATE INDEX IF NOT EXISTS idx_storage_export_jobs_subject ON storage_export_jobs (subject_type, subject_id, status);
--;;
CREATE INDEX IF NOT EXISTS idx_storage_export_jobs_created_at ON storage_export_jobs (created_at);
