(ns wagoe.push.shell.persistence
  "Persistence shell for push notification device tokens and analytics events.

   Implements IDeviceTokenStore and IPushAnalyticsStore protocols against a
   db-context through platform's execution layer, which encodes each parameter
   as the engine takes it.

   Upsert strategy: attempt INSERT, catch the unique-key conflict, then UPDATE.
   This needs no dialect-specific ON CONFLICT syntax."
  (:require [wagoe.push.ports :as ports]
            [wagoe.push.core.device :as device]
            [wagoe.platform.database :as database]
            [honey.sql.helpers :as h])
  (:import [java.time Instant]))

;; =============================================================================
;; Shared Helpers
;; =============================================================================

(defn- ->instant
  "PostgreSQL's driver cannot type a java.util.Date parameter; an Instant every
   engine takes."
  [t]
  (some-> t inst-ms Instant/ofEpochMilli))

(defn- unique-conflict? [e]
  (= {:type :conflict :constraint :unique}
     (select-keys (ex-data e) [:type :constraint])))

;; =============================================================================
;; DeviceTokenStore
;; =============================================================================

(defn- row->device [row]
  (when row
    (-> row
        (update :platform keyword)
        (assoc :active? (boolean (:active row)))
        (dissoc :active))))

(defn- insert-device-row! [db row]
  (database/execute-update! db
                            (-> (h/insert-into :push-device-tokens)
                                (h/values [row]))))

(defn- update-device-row! [db token app-id active last-used-at device-name os-version]
  (database/execute-update! db
                            (-> (h/update :push-device-tokens)
                                (h/set {:active       active
                                        :last-used-at last-used-at
                                        :device-name  device-name
                                        :os-version   os-version})
                                (h/where [:and [:= :token token] [:= :app-id app-id]]))))

(defn- upsert-device! [db record]
  (let [row {:id          (:id record)
             :user-id     (:user-id record)
             :token       (:token record)
             :platform    (name (:platform record))
             :app-id      (:app-id record)
             :device-name (:device-name record)
             :os-version  (:os-version record)
             :active      true
             :created-at  (->instant (:created-at record))
             :last-used-at (->instant (:last-used-at record))}]
    (try
      (insert-device-row! db row)
      (catch clojure.lang.ExceptionInfo e
        (when-not (unique-conflict? e) (throw e))
        ;; Duplicate token+app_id — update active status and last-used
        (update-device-row! db
                            (:token record) (:app-id record)
                            true (->instant (:last-used-at record))
                            (:device-name record) (:os-version record))))))

(defrecord DeviceTokenStore [db]
  ports/IDeviceTokenStore

  (register-device! [_ user-id device-info]
    (let [record (device/prepare-device-record user-id device-info (random-uuid) (java.util.Date.))]
      (upsert-device! db record)
      record))

  (unregister-device! [_ user-id device-token]
    (database/execute-update! db
                              (-> (h/delete-from :push-device-tokens)
                                  (h/where [:and [:= :user-id user-id] [:= :token device-token]]))))

  (get-user-devices [_ user-id]
    (->> (database/execute-query! db
                                  (-> (h/select :*)
                                      (h/from :push-device-tokens)
                                      (h/where [:and [:= :user-id user-id] [:= :active true]])))
         (mapv row->device)))

  (get-devices-by-platform [_ platform opts]
    (let [{:keys [limit offset] :or {limit 100 offset 0}} opts
          limit (min limit 1000)]
      (->> (database/execute-query! db
                                    (-> (h/select :*)
                                        (h/from :push-device-tokens)
                                        (h/where [:and [:= :platform (name platform)] [:= :active true]])
                                        (h/limit limit)
                                        (h/offset offset)))
           (mapv row->device))))

  (mark-token-invalid! [_ device-token]
    (database/execute-update! db
                              (-> (h/update :push-device-tokens)
                                  (h/set {:active false})
                                  (h/where [:= :token device-token]))))

  (cleanup-stale-tokens! [_ max-age-days]
    (let [cutoff (java.sql.Timestamp/from
                  (.minus (java.time.Instant/now)
                          (java.time.Duration/ofDays max-age-days)))]
      (database/execute-update! db
                                (-> (h/delete-from :push-device-tokens)
                                    (h/where [:and [:= :active false] [:< :last-used-at cutoff]]))))))

;; =============================================================================
;; PushAnalyticsStore
;; =============================================================================

(defrecord PushAnalyticsStore [db]
  ports/IPushAnalyticsStore

  (record-send! [_ event]
    (database/execute-update! db
                              (-> (h/insert-into :push-analytics-events)
                                  (h/values [{:id                  (:id event)
                                              :notification-id     (name (:notification-id event))
                                              :device-token        (:device-token event)
                                              :platform            (name (:platform event))
                                              :event-type          (name (:event-type event))
                                              :user-id             (:user-id event)
                                              :provider-message-id (:provider-message-id event)
                                              :error-message       (get-in event [:error :message])
                                              :timestamp           (or (->instant (:timestamp event)) (Instant/now))}]))))

  (record-delivery! [this event]
    (ports/record-send! this (assoc event :event-type :delivered)))

  (record-open! [this event]
    (ports/record-send! this (assoc event :event-type :opened)))

  (get-push-stats [_ notification-id _opts]
    (let [rows   (database/execute-query! db
                                          (-> (h/select :event-type [[:count :*] :cnt])
                                              (h/from :push-analytics-events)
                                              (h/where [:= :notification-id (name notification-id)])
                                              (h/group-by :event-type)))
          counts (reduce (fn [m {:keys [event-type cnt]}]
                           (assoc m (keyword event-type) cnt))
                         {:sent 0 :delivered 0 :opened 0 :failed 0}
                         rows)]
      (assoc counts :notification-id notification-id)))

  (cleanup-old-events! [_ retention-days]
    (let [cutoff (java.sql.Timestamp/from
                  (.minus (java.time.Instant/now)
                          (java.time.Duration/ofDays retention-days)))]
      (database/execute-update! db
                                (-> (h/delete-from :push-analytics-events)
                                    (h/where [:< :timestamp cutoff]))))))
