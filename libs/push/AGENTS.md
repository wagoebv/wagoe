# boundary-push — Push Notification Library

Multi-platform push notification delivery for FCM (Firebase) and APNs (Apple). Device token management, job-based async delivery, HMAC-secured analytics callbacks. Uses `defpush` macro for notification definitions.

## Key Namespaces

| Namespace | Layer | Purpose |
|-----------|-------|---------|
| `boundary.push.core.notification` | Core | `defpush` macro, registry, template rendering |
| `boundary.push.core.delivery` | Core | Payload building (FCM/APNs), error classification, retry calc |
| `boundary.push.core.device` | Core | Token validation, platform detection, staleness |
| `boundary.push.core.analytics` | Core | Rate calculations |
| `boundary.push.ports` | Ports | IPushService, IFCMProvider, IAPNsProvider, IDeviceTokenStore, IPushAnalyticsStore |
| `boundary.push.schema` | Schema | Malli schemas for all domain types |
| `boundary.push.shell.service` | Shell | IPushService impl, HMAC generation/verification |
| `boundary.push.shell.persistence` | Shell | next.jdbc/HoneySQL stores for devices + analytics |
| `boundary.push.shell.adapters.mock` | Shell | Mock FCM + APNs (dev/test) |
| `boundary.push.shell.adapters.fcm` | Shell | Google FCM v1 API adapter |
| `boundary.push.shell.adapters.apns` | Shell | Apple APNs HTTP/2 adapter |
| `boundary.push.shell.handlers` | Shell | Ring handlers for device CRUD + callback |
| `boundary.push.shell.jobs` | Shell | Job handlers for async delivery |
| `boundary.push.shell.module-wiring` | Shell | Integrant lifecycle |

## Protocol: IPushService

```clojure
(send-push! [this notification-id data opts])
;; opts: {:user-id uuid, :locale kw}  — enqueues :push/send job, returns job-id

(schedule-push! [this notification-id data opts scheduled-at])
;; same as send-push! but job carries :scheduled-at instant

(broadcast! [this notification-id data opts])
;; opts: {:platform kw, :app-id str}  — enqueues :push/broadcast job, returns job-id
```

## Protocol: IFCMProvider

```clojure
(fcm-send! [this payload])           ;; Single send, returns {:success? :message-id :device-token :platform :error}
(fcm-send-multicast! [this payload tokens])  ;; Concurrent per-token sends via sendAsync
(fcm-validate-token [this token])    ;; Dry-run: sends with validate_only=true data field
```

**FCM multicast is not a true batch API.** `fcm-send-multicast!` fires one `HttpClient/sendAsync` request per token concurrently and collects futures. Each per-token payload is built from the base payload with the token replaced.

## Protocol: IAPNsProvider

```clojure
(apns-send! [this payload device-token])    ;; Single send
(apns-send-batch! [this payload tokens])    ;; Concurrent per-token sendAsync
```

APNs uses ES256 JWT authentication (team-id + key-id + P8 private key). JWT is minted fresh per call. HTTP/2 client required — the APNs HTTP/2 client is built with `HttpClient$Version/HTTP_2`. No dry-run validation equivalent; token validity discovered at send time.

## Protocol: IDeviceTokenStore

```clojure
(register-device! [this user-id device-info])
;; device-info: {:token str :platform kw :app-id str :device-name str :os-version str}
;; Upsert: INSERT, catch SQLIntegrityConstraintViolationException, UPDATE active+last-used-at

(unregister-device! [this user-id device-token])
;; Hard DELETE by user-id + token

(get-user-devices [this user-id])
;; Returns all rows where active=true for user

(get-devices-by-platform [this platform opts])
;; opts: {:limit n :offset n} — limit capped at 1000, used for broadcast pagination

(mark-token-invalid! [this device-token])
;; Sets active=false by token (cross-user — token is globally unique)

(cleanup-stale-tokens! [this max-age-days])
;; DELETE WHERE active=false AND last_used_at < cutoff
```

## Protocol: IPushAnalyticsStore

```clojure
(record-send! [this event])
;; event: {:id uuid :notification-id kw :device-token str :platform kw
;;         :event-type kw :user-id uuid :provider-message-id str :error str :timestamp inst}
;; Inserts into push_analytics_events. write-once — no updates.

(record-delivery! [this event])  ;; delegates to record-send! with :event-type :delivered
(record-open! [this event])      ;; delegates to record-send! with :event-type :opened

(get-push-stats [this notification-id opts])
;; GROUP BY event_type COUNT(*) → {:sent n :delivered n :opened n :failed n :notification-id kw}

(cleanup-old-events! [this retention-days])
;; DELETE WHERE timestamp < cutoff
```

## Notification Definition

```clojure
(defpush order-shipped
  {:id           :order-shipped
   :title        {:en "Order Shipped" :nl "Bestelling Verzonden"}
   :body         {:en "Your order {{order-id}} is on its way!"}
   :channels     #{:fcm :apns}
   :priority     :high
   :ttl          3600
   :deep-link    "/orders/{{order-id}}"
   :silent?      false
   :collapse-key :order-status
   :retry        {:max-attempts 3 :backoff :exponential}})
```

`defpush` registers into a global `defonce` atom. Use `clear-registry!` in tests to reset state.

## Sending

```clojure
;; Direct (via job queue)
(push/send-push! push-service :order-shipped
  {:order-id "ORD-123"} {:user-id user-id :locale :nl})

;; Scheduled
(push/schedule-push! push-service :appointment-reminder
  {:time "14:00"} {:user-id user-id} future-instant)

;; Broadcast
(push/broadcast! push-service :maintenance-alert
  {:message "Downtime at 2am"} {:platform :fcm})
```

All three methods enqueue jobs and return a job UUID immediately — delivery is async.

## Job Handlers

Two job types registered under `:boundary.push/job-handlers`:

**`:push/send`** args: `{:notification-id kw :data map :user-id uuid :locale kw}`
1. Look up push-def from registry (throws `:not-found` if missing)
2. `get-user-devices` → filter `:active? true` → group by platform
3. `build-notification` (locale fallback → template render)
4. `deliver-to-platform!` per platform group
5. `record-send!` per result; `mark-token-invalid!` when `:token-invalid?` is true

**`:push/broadcast`** args: `{:notification-id kw :data map :platform kw :app-id str :locale kw}`
1. Paginates `get-devices-by-platform` with page-size 500
2. Loops until fewer than 500 devices returned (end of table)
3. Same delivery + analytics recording per page

## HTTP Routes

Registered via `:boundary.push/routes` Integrant key. All device routes require authenticated request (`:identity :user-id` from middleware).

| Method | Path | Handler |
|--------|------|---------|
| `POST` | `/api/push/devices` | Register device token |
| `GET` | `/api/push/devices` | List user's active devices |
| `DELETE` | `/api/push/devices/:token` | Unregister device token |
| `POST` | `/api/push/callback` | Delivery/open analytics callback (HMAC-secured) |
| `GET` | `/api/push/stats/:notification-id` | Aggregate stats with delivery/open rates |

## HMAC Callback Flow

Mobile app receives push payload including `callback-token` (HMAC of the `provider-message-id`). On delivery or open, mobile POSTs to `/api/push/callback`:

```clojure
;; Server generates callback-token at send time:
(service/generate-callback-token callback-secret provider-message-id)
;; → HmacSHA256(callback-secret, provider-message-id) as hex string

;; Callback handler verifies:
(service/verify-callback-token callback-secret provider-message-id callback-token)
;; → MessageDigest/isEqual (constant-time comparison)
```

Callback payload schema:

```clojure
{:device-token        str
 :provider-message-id str
 :event-type          #{:delivered :opened}
 :callback-token      str     ; HMAC hex
 :notification-id     kw
 :platform            #{:fcm :apns}
 :timestamp           inst?}  ; optional, defaults to now
```

## Error Classification

| Category | Action |
|----------|--------|
| `:retryable` | Re-enqueue with backoff |
| `:token-invalid` | Deactivate token, don't retry |
| `:rate-limited` | Re-enqueue with longer backoff |
| `:permanent` | Log, don't retry |

FCM error codes mapped: `UNREGISTERED`/`INVALID_ARGUMENT` → `:token-invalid`, `QUOTA_EXCEEDED` → `:rate-limited`, `UNAVAILABLE`/`INTERNAL` → `:retryable`, `PERMISSION_DENIED`/`SENDER_ID_MISMATCH` → `:permanent`.

APNs: `BadDeviceToken`/`Unregistered` → `:token-invalid`, `TooManyRequests` → `:rate-limited`, `ServiceUnavailable` → `:retryable`, `BadCertificate`/`Forbidden` → `:permanent`.

Both adapters also set `:token-invalid? true` on the result map for `:token-invalid` errors. Job handler reads this flag directly to call `mark-token-invalid!` without re-classifying.

## DB Tables

Three migrations under `libs/push/resources/boundary/push/migrations/`:

**`push_device_tokens`** (migration `20260524000000`)
- `(token, app_id)` UNIQUE constraint drives upsert behaviour
- Indexed on `(user_id, active)` and `(platform, active)`

**`push_send_log`** (migration `20260524000001`)
- Snapshot of each outbound attempt: title, body, priority, status, sent_at
- Not written by current persistence shell (analytics events used instead); table is effectively dead weight — scheduled for removal or repurposing

**`push_analytics_events`** (migration `20260524000002`)
- Event-sourced: `:sent` written at delivery time; `:delivered`/`:opened` from callbacks
- Indexed on `(notification_id, event_type)` for stats queries and `(timestamp)` for cleanup

## Integrant Wiring

```clojure
;; Providers
:boundary.push/fcm-provider  {:provider :fcm   ; or :mock
                               :project-id "my-project"
                               :credentials-path "/path/to/service-account.json"}

:boundary.push/apns-provider {:provider :apns  ; or :mock
                               :team-id  "TEAM123"
                               :key-id   "KEY456"
                               :key-path "/path/to/key.p8"
                               :bundle-id "com.example.app"
                               :sandbox? false}

;; Stores
:boundary.push/device-store    {:db #ig/ref :wagoe/db}
:boundary.push/analytics-store {:db #ig/ref :wagoe/db}

;; Service
:boundary.push/service {:device-store    #ig/ref :boundary.push/device-store
                        :analytics-store #ig/ref :boundary.push/analytics-store
                        :fcm-provider    #ig/ref :boundary.push/fcm-provider
                        :apns-provider   #ig/ref :boundary.push/apns-provider
                        :job-queue       #ig/ref :wagoe/jobs
                        :callback-secret #env WAG_PUSH_CALLBACK_SECRET}

;; Job handlers — register returned map with your job dispatcher
:boundary.push/job-handlers {:push-service #ig/ref :boundary.push/service}

;; Routes — mount in your router
:boundary.push/routes {:device-store    #ig/ref :boundary.push/device-store
                       :analytics-store #ig/ref :boundary.push/analytics-store
                       :callback-secret #env WAG_PUSH_CALLBACK_SECRET}
```

Require `boundary.push.shell.module-wiring` at system start to load the `defmethod` definitions.

## Gotchas

1. **FCM tokens rotate** — always handle `UNREGISTERED` by marking token invalid; adapters set `:token-invalid? true` automatically
2. **APNs sandbox vs production** — different hosts (`api.sandbox.push.apple.com` vs `api.push.apple.com`), set `:sandbox?` correctly per environment
3. **Callback HMAC** — mobile apps must send back `callback-token` computed from `provider-message-id` included in push data payload; callback handler returns 403 on mismatch
4. **Template variables** — `{{var}}` syntax; missing vars left as-is (not stripped)
5. **Locale fallback** — requested → `:en` → first available
6. **`push_send_log` is legacy** — migration creates the table but current persistence shell writes to `push_analytics_events` only
7. **Upsert strategy** — persistence uses INSERT + catch `SQLIntegrityConstraintViolationException` + UPDATE for H2+PostgreSQL compat (avoids dialect-specific `ON CONFLICT`)
8. **FCM "multicast" is concurrent single sends** — `fcm-send-multicast!` sends one per-token request via `sendAsync`, not FCM batch API
9. **APNs JWT is minted per call** — `make-jwt` runs on every `apns-send!` and `apns-send-batch!` invocation; no caching. Fine for normal throughput; at broadcast scale (thousands of tokens) this becomes a known bottleneck — JWT caching with a ~45-min expiry window is the fix
10. **Registry lives in the shell** — the definition registry (`defpush`, `register-push!`, `get-push`, `list-pushes`, `clear-registry!`) is in `boundary.push.shell.registry`; `boundary.push.core.notification` is pure (rendering only). Call `registry/clear-registry!` in test setup when testing notification lookup.

## REPL Smoke Checks

```clojure
;; Verify registry (shell)
(require '[boundary.push.shell.registry :as registry])
(registry/list-pushes)

;; Render a notification (core is pure)
(require '[boundary.push.core.notification :as notif])
(notif/build-notification (registry/get-push :order-shipped)
                          {:order-id "ORD-123"} :en)

;; Inspect FCM payload shape
(require '[boundary.push.core.delivery :as delivery])
(delivery/build-fcm-payload {:title "Hi" :body "Test" :priority :high
                             :ttl 3600 :data {:order-id "1"}} "some-fcm-token")

;; Verify HMAC round-trip
(require '[boundary.push.shell.service :as svc])
(let [secret "dev-secret"
      msg-id "msg-123"
      tok    (svc/generate-callback-token secret msg-id)]
  (svc/verify-callback-token secret msg-id tok))
;; => true
```

## Testing

```bash
clojure -M:test:db/h2 :push                              # All
clojure -M:test:db/h2 :push --focus-meta :unit            # Unit
clojure -M:test:db/h2 :push --focus-meta :contract        # Contract (H2)
clojure -M:test:db/h2 :push --focus-meta :integration     # Integration
```
