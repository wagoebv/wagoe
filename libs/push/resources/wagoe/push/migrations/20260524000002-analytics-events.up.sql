CREATE TABLE IF NOT EXISTS push_analytics_events (
    id                  UUID PRIMARY KEY,
    notification_id     VARCHAR(255) NOT NULL,
    device_token        VARCHAR(512) NOT NULL,
    platform            VARCHAR(10) NOT NULL,
    event_type          VARCHAR(20) NOT NULL,
    user_id             UUID,
    provider_message_id VARCHAR(255),
    error_message       TEXT,
    timestamp           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    tenant_id           UUID
);
--;;
CREATE INDEX IF NOT EXISTS idx_push_analytics_notification ON push_analytics_events (notification_id, event_type);
--;;
CREATE INDEX IF NOT EXISTS idx_push_analytics_time ON push_analytics_events (timestamp);
