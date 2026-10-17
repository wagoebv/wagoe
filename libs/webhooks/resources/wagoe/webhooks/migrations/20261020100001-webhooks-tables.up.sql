-- The one definition of the webhook tables. :wagoe.webhooks/db-schema runs this
-- file too, for installations that boot without migrating. Indexed columns are
-- VARCHAR, since MySQL cannot index TEXT.
CREATE TABLE IF NOT EXISTS webhook_subscriptions (
  id             VARCHAR(36) NOT NULL PRIMARY KEY,
  tenant_id      VARCHAR(255),
  event_pattern  VARCHAR(255) NOT NULL,
  url            VARCHAR(2048) NOT NULL,
  secret         TEXT NOT NULL,
  active         BOOLEAN NOT NULL DEFAULT TRUE,
  description    VARCHAR(1000),
  created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
--;;
CREATE INDEX IF NOT EXISTS idx_webhook_subscriptions_tenant
  ON webhook_subscriptions (tenant_id, active);
--;;
CREATE TABLE IF NOT EXISTS webhook_deliveries (
  id               VARCHAR(36) NOT NULL PRIMARY KEY,
  subscription_id  VARCHAR(36) NOT NULL,
  tenant_id        VARCHAR(255),
  event_id         VARCHAR(255) NOT NULL,
  topic            VARCHAR(255) NOT NULL,
  attempt          INT NOT NULL,
  status           VARCHAR(20) NOT NULL,
  status_code      INT,
  latency_ms       BIGINT,
  response         TEXT,
  error            TEXT,
  created_at       TIMESTAMP WITH TIME ZONE NOT NULL
);
--;;
CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_event
  ON webhook_deliveries (subscription_id, event_id);
--;;
CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_created
  ON webhook_deliveries (created_at);
