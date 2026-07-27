CREATE TABLE IF NOT EXISTS push_device_tokens (
    id            UUID PRIMARY KEY,
    user_id       UUID NOT NULL,
    tenant_id     UUID,
    token         VARCHAR(512) NOT NULL,
    platform      VARCHAR(10) NOT NULL,
    app_id        VARCHAR(255) NOT NULL,
    device_name   VARCHAR(255),
    os_version    VARCHAR(50),
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_push_device_token UNIQUE (token, app_id)
);
--;;
CREATE INDEX IF NOT EXISTS idx_push_devices_user ON push_device_tokens (user_id, active);
--;;
CREATE INDEX IF NOT EXISTS idx_push_devices_platform ON push_device_tokens (platform, active);
