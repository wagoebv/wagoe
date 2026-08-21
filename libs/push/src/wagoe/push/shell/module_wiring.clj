(ns wagoe.push.shell.module-wiring
  (:require [wagoe.push.shell.service :as service]
            [wagoe.push.shell.persistence :as persistence]
            [wagoe.push.shell.adapters.mock :as mock]
            [wagoe.push.shell.adapters.fcm :as fcm]
            [wagoe.push.shell.adapters.apns :as apns]
            [wagoe.push.shell.handlers :as handlers]
            [wagoe.push.shell.jobs :as jobs]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

(defmethod ig/init-key :wagoe.push/fcm-provider
  [_ {:keys [provider project-id credentials-path]}]
  (case (or provider :mock)
    :mock (do (log/info "Push: using mock FCM provider")
              (mock/->MockFCMProvider))
    :fcm  (do (log/info "Push: initializing FCM provider" {:project-id project-id})
              (fcm/make-fcm-provider project-id credentials-path))))

(defmethod ig/init-key :wagoe.push/apns-provider
  [_ {:keys [provider team-id key-id key-path bundle-id sandbox?]}]
  (case (or provider :mock)
    :mock (do (log/info "Push: using mock APNs provider")
              (mock/->MockAPNsProvider))
    :apns (do (log/info "Push: initializing APNs provider" {:team-id team-id :sandbox? sandbox?})
              (apns/make-apns-provider team-id key-id key-path bundle-id sandbox?))))

(defmethod ig/init-key :wagoe.push/device-store
  [_ {:keys [db]}]
  (log/info "Push: initializing device token store")
  (persistence/->DeviceTokenStore db))

(defmethod ig/init-key :wagoe.push/analytics-store
  [_ {:keys [db]}]
  (log/info "Push: initializing analytics store")
  (persistence/->PushAnalyticsStore db))

(defmethod ig/init-key :wagoe.push/service
  [_ {:keys [device-store analytics-store fcm-provider apns-provider job-queue callback-secret]}]
  (log/info "Push: initializing push service")
  (service/->PushService device-store analytics-store fcm-provider apns-provider job-queue callback-secret))

(defmethod ig/init-key :wagoe.push/job-handlers
  [_ {:keys [push-service _job-registry]}]
  (let [deps {:push-service    push-service
              :device-store    (:device-store push-service)
              :fcm-provider    (:fcm-provider push-service)
              :apns-provider   (:apns-provider push-service)
              :analytics-store (:analytics-store push-service)
              :callback-secret (:callback-secret push-service)}]
    (log/info "Push: registering job handlers")
    {:push/send      (partial jobs/handle-send-push deps)
     :push/broadcast (partial jobs/handle-broadcast deps)}))

(defmethod ig/init-key :wagoe.push/routes
  [_ {:keys [device-store analytics-store callback-secret]}]
  (handlers/push-routes {:device-store    device-store
                         :analytics-store analytics-store
                         :callback-secret callback-secret}))

;; =============================================================================
;; Module graph
;; =============================================================================

(defn ig-config
  "This module's Integrant entries, for `wagoe.platform.shell.system.config`.

   `:wagoe/push` is a settings block, not a component: it configures the four
   `:wagoe.push/*` components assembled here. Both providers fall back to
   `:mock` when no credentials are configured, so an app can enable push before
   it has an FCM account."
  [settings _ctx]
  {:components
   {:wagoe.push/fcm-provider    {:provider    (if (:fcm-credentials settings) :fcm :mock)
                                 :credentials (:fcm-credentials settings)}
    :wagoe.push/apns-provider   (merge {:provider (if (:apns-credentials settings) :apns :mock)}
                                       (:apns-credentials settings))
    :wagoe.push/device-store    {:db (ig/ref :wagoe/db-context)}
    :wagoe.push/analytics-store {:db (ig/ref :wagoe/db-context)}
    :wagoe.push/service         {:device-store    (ig/ref :wagoe.push/device-store)
                                 :analytics-store (ig/ref :wagoe.push/analytics-store)
                                 :fcm-provider    (ig/ref :wagoe.push/fcm-provider)
                                 :apns-provider   (ig/ref :wagoe.push/apns-provider)
                                 :callback-secret (:callback-secret settings)}
    :wagoe.push/routes          {:device-store    (ig/ref :wagoe.push/device-store)
                                 :analytics-store (ig/ref :wagoe.push/analytics-store)
                                 :callback-secret (:callback-secret settings)}}})
