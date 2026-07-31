# wagoe-push

[![Status](https://img.shields.io/badge/status-alpha-orange)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-push.svg)](https://clojars.org/com.wagoe/wagoe-push)

> Multi-platform push notifications for the Wagoe framework — FCM (Firebase) and APNs (Apple) with device token management, job-based async delivery, and HMAC-secured analytics callbacks.

---

## Quick Start

```clojure
;; deps.edn
{:deps {com.wagoe/wagoe-push {:mvn/version "1.0.0-beta-2"}}}
```

```clojure
(require '[wagoe.push.shell.service :as push])

;; Define a notification type
(defpush order-shipped
  {:id           :order-shipped
   :title        {:en "Order Shipped" :nl "Bestelling Verzonden"}
   :body         {:en "Your order {{order-id}} is on its way!"}
   :channels     #{:fcm :apns}
   :priority     :high
   :ttl          3600
   :deep-link    "/orders/{{order-id}}"
   :retry        {:max-attempts 3 :backoff :exponential}})

;; Send via job queue
(push/send-push! push-service :order-shipped
  {:order-id "ORD-123"} {:user-id user-id :locale :nl})

;; Scheduled delivery
(push/schedule-push! push-service :appointment-reminder
  {:time "14:00"} {:user-id user-id} future-instant)

;; Broadcast to a platform
(push/broadcast! push-service :maintenance-alert
  {:message "Downtime at 2am"} {:platform :fcm})
```

---

## Integrant Configuration

Add to `resources/conf/{env}/config.edn` and require `wagoe.push.shell.module-wiring` at system start:

```edn
:wagoe.push/fcm-provider  {:provider         :fcm
                               :project-id       #env WAG_PUSH_FCM_PROJECT_ID
                               :credentials-path #env WAG_PUSH_FCM_CREDENTIALS_PATH}

:wagoe.push/apns-provider {:provider  :apns
                               :team-id   #env WAG_PUSH_APNS_TEAM_ID
                               :key-id    #env WAG_PUSH_APNS_KEY_ID
                               :key-path  #env WAG_PUSH_APNS_KEY_PATH
                               :bundle-id #env WAG_PUSH_APNS_BUNDLE_ID
                               :sandbox?  false}

:wagoe.push/device-store    {:db #ig/ref :wagoe/db}
:wagoe.push/analytics-store {:db #ig/ref :wagoe/db}

:wagoe.push/service {:device-store    #ig/ref :wagoe.push/device-store
                        :analytics-store #ig/ref :wagoe.push/analytics-store
                        :fcm-provider    #ig/ref :wagoe.push/fcm-provider
                        :apns-provider   #ig/ref :wagoe.push/apns-provider
                        :job-queue       #ig/ref :wagoe/jobs
                        :callback-secret #env WAG_PUSH_CALLBACK_SECRET}

:wagoe.push/job-handlers {:push-service #ig/ref :wagoe.push/service}

:wagoe.push/routes {:device-store    #ig/ref :wagoe.push/device-store
                       :analytics-store #ig/ref :wagoe.push/analytics-store
                       :callback-secret #env WAG_PUSH_CALLBACK_SECRET}
```

Use `:provider :mock` for FCM and APNs in dev/test environments.

---

## API

```clojure
(require '[wagoe.push.ports :as push-ports])

;; Send immediately via job queue
(push-ports/send-push! service :notification-id template-vars {:user-id uuid :locale :en})

;; Schedule for a future instant
(push-ports/schedule-push! service :notification-id template-vars {:user-id uuid} future-instant)

;; Broadcast to all active tokens for a platform
(push-ports/broadcast! service :notification-id template-vars {:platform :fcm})

;; Device token management (via IDeviceTokenStore)
(push-ports/register-device! device-store user-id {:token "..." :platform :fcm :app-id "com.example"})
(push-ports/unregister-device! device-store user-id token)
(push-ports/get-user-devices device-store user-id)
```

---

## DB Migration

Run once before using push notifications. When `wagoe-push` is on the classpath, `clojure -M:migrate up` auto-discovers these migrations:

```sql
-- 20260524000000-device-tokens.up.sql
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

-- 20260524000001-push-send-log.up.sql  (created for future detailed logging)
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

-- 20260524000002-analytics-events.up.sql  (used by persistence shell)
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
```

---

## Providers

| Provider | Key `:channels` | Credentials | Notes |
|----------|----------------|-------------|-------|
| Firebase Cloud Messaging | `:fcm` | Service account JSON (`WAG_PUSH_FCM_CREDENTIALS_JSON`) | Supports multicast, token validation |
| Apple Push Notification service | `:apns` | Key ID + Team ID + P8 private key | Separate sandbox/production hosts; set `:sandbox?` per env |
| Mock (dev/test) | `:mock` | None | In-memory; use `wagoe.push.shell.adapters.mock` |

---

## Tests

```bash
clojure -M:test:db/h2 :push
clojure -M:test:db/h2 :push --focus-meta :unit
clojure -M:test:db/h2 :push --focus-meta :contract
```

See [AGENTS.md](AGENTS.md) for full developer guide, gotchas, and REPL smoke checks.
