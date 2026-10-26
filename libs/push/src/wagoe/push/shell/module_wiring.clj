(ns wagoe.push.shell.module-wiring
  (:require [clojure.string :as str]
            [wagoe.push.shell.service :as service]
            [wagoe.push.shell.persistence :as persistence]
            [wagoe.push.shell.adapters.mock :as mock]
            [wagoe.push.shell.adapters.fcm :as fcm]
            [wagoe.push.shell.adapters.apns :as apns]
            [wagoe.push.shell.handlers :as handlers]
            [wagoe.push.shell.jobs :as jobs]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [malli.core :as m]
            [malli.error :as me]
            [wagoe.push.schema :as schema]))

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

(defn- db-ctx
  "`:db-ctx`, the name geo and search use for `:wagoe/db-context`. `:db` is read
   until 2.0."
  [k {:keys [db-ctx db]}]
  (or db-ctx
      (do (when db
            (log/warn (str "DEPRECATED " k " :db goes in 2.0; name it :db-ctx")))
          db)))

(defmethod ig/init-key :wagoe.push/device-store
  [k config]
  (log/info "Push: initializing device token store")
  (persistence/->DeviceTokenStore (db-ctx k config)))

(defmethod ig/init-key :wagoe.push/analytics-store
  [k config]
  (log/info "Push: initializing analytics store")
  (persistence/->PushAnalyticsStore (db-ctx k config)))

(defmethod ig/init-key :wagoe.push/service
  [_ {:keys [device-store analytics-store fcm-provider apns-provider
             job-queue queue-name callback-secret callback-token-ttl-seconds providers]}]
  (log/info "Push: initializing push service" {:queue (or queue-name :default)})
  ;; With mock providers nothing reaches a device, so nothing calls back.
  (when (and (empty? callback-secret)
             (not (and (seq providers) (every? #{:mock} (vals providers)))))
    (log/warn "Push: no :callback-secret in :wagoe/push. Pushes carry no callback token,"
              "and every POST to /push/callback is refused with 403."))
  (service/map->PushService {:device-store               device-store
                             :analytics-store            analytics-store
                             :fcm-provider               fcm-provider
                             :apns-provider              apns-provider
                             :job-queue                  job-queue
                             :queue-name                 (or queue-name :default)
                             :callback-secret            callback-secret
                             :callback-token-ttl-seconds callback-token-ttl-seconds}))

(defmethod ig/init-key :wagoe.push/job-handlers
  [_ {:keys [push-service _job-registry]}]
  ;; The service carries every key the handlers read.
  (log/info "Push: registering job handlers")
  {:push/send      (partial jobs/handle-send-push push-service)
   :push/broadcast (partial jobs/handle-broadcast push-service)})

;; Deprecated: the `/api/push` vector apps mounted by hand before the module
;; contributed `:wagoe.push/route-contribution`. Still built, so they boot.
(defmethod ig/init-key :wagoe.push/routes
  [_ deps]
  (handlers/push-routes deps))

(defmethod ig/init-key :wagoe.push/route-contribution
  [_ deps]
  {:api    (handlers/api-routes deps)
   :web    []
   :static []})

;; =============================================================================
;; Module graph
;; =============================================================================

(defn- provider-settings
  "Settings for a push provider: the real one when every required field is
   present, the mock when none is, and a refusal in between.

   `required` is that adapter's whole field set, so adding one to an adapter
   cannot leave its neighbour's check behind."
  [provider label credentials required]
  (let [present (filter #(some? (get credentials %)) required)
        missing (remove #(some? (get credentials %)) required)]
    (cond
      (empty? present) {:provider :mock}

      (seq missing)
      (throw (ex-info (str label " push is partly configured: "
                           (str/join ", " (map name missing))
                           " missing. Set them, or remove the credentials block to use "
                           "the mock provider — a mock accepts every push and delivers none.")
                      {:type :configuration-error :provider provider :missing (vec missing)}))

      :else (merge {:provider provider} credentials))))

(defn ig-config
  "This module's Integrant entries, for `wagoe.platform.shell.system.config`.

   `:wagoe/push` is a settings block, not a component: it configures the
   `:wagoe.push/*` components assembled here. Both providers fall back to
   `:mock` when no credentials are configured, so an app can enable push before
   it has an FCM account.

   The job handlers and the queue ref appear only when the jobs module is
   enabled. Without them `schedule-push!` enqueued into a nil queue and the
   handlers had no registry to land in, so a scheduled push disappeared without
   an error (BOU-418)."
  [settings {:keys [enabled]}]
  (when-let [explanation (m/explain schema/PushSettings (or settings {}))]
    (throw (ex-info (str "Invalid :wagoe/push settings: " (pr-str (me/humanize explanation)))
                    {:type :configuration-error :errors (me/humanize explanation)})))
  (let [jobs?      (contains? (or enabled #{}) :wagoe/jobs)
        fcm        (provider-settings :fcm "FCM" (:fcm-credentials settings)
                                      [:project-id :credentials-path])
        apns       (provider-settings :apns "APNs" (:apns-credentials settings)
                                      [:team-id :key-id :key-path :bundle-id])
        route-deps {:device-store                   (ig/ref :wagoe.push/device-store)
                    :analytics-store                (ig/ref :wagoe.push/analytics-store)
                    :callback-secret                (:callback-secret settings)
                    :refuse-legacy-callback-tokens? (:refuse-legacy-callback-tokens? settings)}]
    (cond-> {:components
             ;; Credentials are all-or-nothing. A map of unset `#env` values
             ;; is a present map, so "are credentials configured" cannot mean
             ;; "is the key there" — that chose the real provider and threw
             ;; reading a file at path nil (BOU-346 for FCM, BOU-427 for APNs:
             ;; the same defect three lines apart, because the first fix swept
             ;; one adapter and not its neighbour).
             ;;
             ;; Half-configured is the case worth refusing. Falling back to the
             ;; mock would boot a process that accepts every push and delivers
             ;; none — the failure an operator is least likely to notice,
             ;; because nothing errors and nothing arrives (BOU-427 review).
             {:wagoe.push/fcm-provider    fcm
              :wagoe.push/apns-provider   apns
              :wagoe.push/device-store    {:db-ctx (ig/ref :wagoe/db-context)}
              :wagoe.push/analytics-store {:db-ctx (ig/ref :wagoe/db-context)}
              :wagoe.push/service         (cond-> {:device-store    (ig/ref :wagoe.push/device-store)
                                                   :analytics-store (ig/ref :wagoe.push/analytics-store)
                                                   :fcm-provider    (ig/ref :wagoe.push/fcm-provider)
                                                   :apns-provider   (ig/ref :wagoe.push/apns-provider)
                                                   :callback-secret (:callback-secret settings)
                                                   :callback-token-ttl-seconds (:callback-token-ttl-seconds settings)
                                                   :providers       {:fcm  (:provider fcm)
                                                                     :apns (:provider apns)}}
                                            jobs?
                                            (assoc :job-queue  (ig/ref :wagoe/job-queue)
                                                   ;; :default, because that is
                                                   ;; the queue the default worker
                                                   ;; polls. Push used to enqueue
                                                   ;; on :push, which no worker
                                                   ;; read (BOU-418 review); set
                                                   ;; :queue here and list it in
                                                   ;; jobs' :workers :queues to
                                                   ;; get that isolation back.
                                                   :queue-name (:queue settings :default)))
              :wagoe.push/routes          route-deps
              :wagoe.push/route-contribution route-deps}
             :routes [(ig/ref :wagoe.push/route-contribution)]}

      jobs?
      (-> (assoc-in [:components :wagoe.push/job-handlers]
                    {:push-service (ig/ref :wagoe.push/service)})
          (assoc :job-handlers [(ig/ref :wagoe.push/job-handlers)])))))
