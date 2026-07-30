CREATE TABLE IF NOT EXISTS push_send_log (
    id                  UUID PRIMARY KEY,
    notification_id     VARCHAR(255) NOT NULL,
    user_id             UUID,
    device_token_id     UUID,
    device_token        VARCHAR(512) NOT NULL,
    platform            VARCHAR(10) NOT NULL,
    title               VARCHAR(500),
    body                TEXT,
    priority            VARCHAR(10) NOT NULL DEFAULT 'normal',
    status              VARCHAR(20) NOT NULL,
    provider_message_id VARCHAR(255),
    error_message       TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at             TIMESTAMP,
    tenant_id           UUID
);
--;;
CREATE INDEX IF NOT EXISTS idx_push_log_notification ON push_send_log (notification_id, created_at);
--;;
CREATE INDEX IF NOT EXISTS idx_push_log_user ON push_send_log (user_id, created_at);
