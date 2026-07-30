(ns wagoe.push.ports
  "Protocol definitions for push notification delivery, device management, and analytics.")

;; ===== Service =====

(defprotocol IPushService
  (send-push! [this notification-id data opts]
    "Enqueue push delivery for all user devices. opts: {:user-id uuid, :locale kw}")
  (schedule-push! [this notification-id data opts scheduled-at]
    "Schedule push for future delivery via jobs.")
  (broadcast! [this notification-id data opts]
    "Send to all registered devices matching opts: {:platform kw, :app-id str}"))

;; ===== Providers =====

(defprotocol IFCMProvider
  (fcm-send! [this payload]
    "Send FCM message. Returns {:success? bool :message-id str :error map}")
  (fcm-send-multicast! [this payload tokens]
    "Send to multiple FCM tokens. Returns per-token results.")
  (fcm-validate-token [this token]
    "Dry-run send to check token validity."))

(defprotocol IAPNsProvider
  (apns-send! [this payload device-token]
    "Send APNs notification. Returns {:success? bool :apns-id str :error map}")
  (apns-send-batch! [this payload device-tokens]
    "Send to multiple APNs devices. Returns per-token results."))

;; ===== Persistence =====

(defprotocol IDeviceTokenStore
  (register-device! [this user-id device-info]
    "Store device token. device-info: {:token str :platform kw :app-id str}")
  (unregister-device! [this user-id device-token]
    "Remove device token.")
  (get-user-devices [this user-id]
    "All active devices for user.")
  (get-devices-by-platform [this platform opts]
    "All devices for platform. opts: {:limit n :offset n}. Used by broadcast.")
  (mark-token-invalid! [this device-token]
    "Flag token as invalid after provider rejection.")
  (cleanup-stale-tokens! [this max-age-days]
    "Purge tokens not used within max-age-days."))

;; ===== Analytics =====

(defprotocol IPushAnalyticsStore
  (record-send! [this event]
    "Log send attempt with provider response.")
  (record-delivery! [this event]
    "Log client-reported delivery confirmation.")
  (record-open! [this event]
    "Log client-reported notification open.")
  (get-push-stats [this notification-id opts]
    "Aggregate stats: sent/delivered/opened/failed counts.")
  (cleanup-old-events! [this retention-days]
    "Purge analytics events older than retention-days."))
