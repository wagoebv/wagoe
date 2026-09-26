-- The one definition of workflow's tables. :wagoe/workflow-db-schema runs this
-- file too, for installations that boot without migrating (BOU-502).
CREATE TABLE IF NOT EXISTS workflow_instances (
  id             TEXT NOT NULL PRIMARY KEY,
  workflow_id    TEXT NOT NULL,
  entity_type    TEXT NOT NULL,
  entity_id      TEXT NOT NULL,
  current_state  TEXT NOT NULL,
  created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
  metadata       TEXT
);
--;;
CREATE INDEX IF NOT EXISTS idx_workflow_instances_entity
  ON workflow_instances (entity_type, entity_id);
--;;
CREATE INDEX IF NOT EXISTS idx_workflow_instances_workflow_id
  ON workflow_instances (workflow_id);
--;;
CREATE INDEX IF NOT EXISTS idx_workflow_instances_current_state
  ON workflow_instances (current_state);
--;;
CREATE TABLE IF NOT EXISTS workflow_audit (
  id           TEXT NOT NULL PRIMARY KEY,
  instance_id  TEXT NOT NULL REFERENCES workflow_instances(id),
  workflow_id  TEXT NOT NULL,
  entity_type  TEXT NOT NULL,
  entity_id    TEXT NOT NULL,
  transition   TEXT NOT NULL,
  from_state   TEXT NOT NULL,
  to_state     TEXT NOT NULL,
  actor_id     TEXT,
  actor_roles  TEXT,
  context      TEXT,
  occurred_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
--;;
CREATE INDEX IF NOT EXISTS idx_workflow_audit_instance_id
  ON workflow_audit (instance_id);
--;;
CREATE INDEX IF NOT EXISTS idx_workflow_audit_occurred_at
  ON workflow_audit (occurred_at);
--;;
CREATE INDEX IF NOT EXISTS idx_workflow_audit_actor_id
  ON workflow_audit (actor_id);
