(ns wagoe.push.shell.persistence
  "Persistence shell for push notification device tokens and analytics events.

   Implements IDeviceTokenStore and IPushAnalyticsStore protocols against a
   db-context through platform's execution layer, which encodes each parameter
   as the engine takes it.

   A token is upserted on (token, app_id, tenant_id) with platform's `upsert!`,
   and refused when another tenant holds it under any app-id."
  (:require [wagoe.push.ports :as ports]
            [wagoe.push.core.device :as device]
            [wagoe.core.utils.type-conversion :as tc]
            [wagoe.platform.database :as database]
            [honey.sql.helpers :as h])
  (:import [java.time Instant]))

;; =============================================================================
;; Shared Helpers
;; =============================================================================

(defn- ->instant
  "PostgreSQL's driver cannot type a java.util.Date parameter; the execution
   layer writes an Instant as each engine takes it."
  [t]
  (some-> t inst-ms Instant/ofEpochMilli))

(defn- days-ago
  "A cutoff encoded as the timestamps are written. A java.sql.Timestamp binds as
   epoch millis on SQLite, which never compares below the ISO text stored there."
  [days]
  (.minus (Instant/now) (java.time.Duration/ofDays days)))

;; =============================================================================
;; DeviceTokenStore
;; =============================================================================

(defn- row->device
  "MySQL and SQLite hand the id back as text."
  [row]
  (when row
    (-> row
        (update :id tc/string->uuid)
        (update :tenant-id tc/string->uuid)
        (update :platform keyword)
        ;; SQLite reads 0 back, which `boolean` calls true
        (assoc :active? (tc/int->boolean (:active row)))
        (dissoc :active))))

(defn- token-rows
  "Every registration of `token`: id, app-id and tenant, under any app-id."
  [db token]
  (mapv #(update % :tenant-id tc/string->uuid)
        (database/execute-query! db (-> (h/select :id :app-id :tenant-id)
                                        (h/from :push-device-tokens)
                                        (h/where [:= :token token])))))

(defn- conflict
  "The refusal of a token another tenant holds, under any app-id (BOU-990, BOU-994)."
  [{:keys [token app-id]}]
  (ex-info "The token is registered in another tenant" {:type :conflict :token token :app-id app-id}))

(defn- upsert-device!
  "Returns the stored row's id: when the token was registered already, that
   row's, not ours. In one tenant the token then belongs to whoever registered
   it last (BOU-683); a token another tenant holds, under any app-id, is
   refused (BOU-990, BOU-994): a token is one device."
  [db record]
  (let [row      {:id           (:id record)
                  :user-id      (:user-id record)
                  :token        (:token record)
                  :platform     (name (:platform record))
                  :app-id       (:app-id record)
                  :device-name  (:device-name record)
                  :os-version   (:os-version record)
                  :tenant-id    (tc/string->uuid (:tenant-id record))
                  :active       true
                  :created-at   (->instant (:created-at record))
                  :last-used-at (->instant (:last-used-at record))}
        stored   #(first (filter (comp #{(:app-id row)} :app-id) (token-rows db (:token row))))
        rows     (token-rows db (:token row))]
    ;; ponytail: checked before the write; two tenants racing under two app-ids can both pass.
    (when (seq (disj (set (map :tenant-id rows)) (:tenant-id row)))
      (throw (conflict row)))
    ;; Keyed on the tenant too, so a racing registration in another tenant
    ;; matches no update and its insert meets the unique (token, app_id) index.
    (if (= :inserted (try (database/upsert! db :push-device-tokens row [:token :app-id :tenant-id]
                                            {:update-columns [:user-id :active :last-used-at :device-name :os-version]})
                          (catch clojure.lang.ExceptionInfo e
                            (throw (if (database/unique-conflict? e) (conflict row) e)))))
      (:id row)
      (tc/string->uuid (:id (or (first (filter (comp #{(:app-id row)} :app-id) rows)) (stored)))))))

(defrecord DeviceTokenStore [db]
  ports/IDeviceTokenStore

  (register-device! [_ user-id device-info]
    (let [record (device/prepare-device-record user-id device-info (random-uuid) (java.util.Date.))]
      (assoc record :id (upsert-device! db record))))

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
    (let [{:keys [limit after-id tenant-id] :or {limit 100}} opts]
      (->> (database/execute-query! db
                                    (-> (h/select :*)
                                        (h/from :push-device-tokens)
                                        (h/where [:= :platform (name platform)]
                                                 [:= :active true]
                                                 ;; A broadcast in a tenant reaches its devices only (BOU-946)
                                                 (when tenant-id [:= :tenant-id tenant-id])
                                                 (when after-id [:> :id after-id]))
                                        (h/order-by :id)
                                        (h/limit (min limit 1000))))
           (mapv row->device))))

  (mark-token-invalid! [_ device-token]
    (database/execute-update! db
                              (-> (h/update :push-device-tokens)
                                  (h/set {:active false})
                                  (h/where [:= :token device-token]))))

  (cleanup-stale-tokens! [_ max-age-days]
    (let [cutoff (days-ago max-age-days)]
      (database/execute-update! db
                                (-> (h/delete-from :push-device-tokens)
                                    (h/where [:and [:= :active false] [:< :last-used-at cutoff]])))))

  ports/IDeviceLookup

  (find-user-devices [_ user-id device-token app-id]
    (->> (database/execute-query! db
                                  (-> (h/select :*)
                                      (h/from :push-device-tokens)
                                      (h/where [:= :user-id user-id]
                                               [:= :token device-token]
                                               [:= :active true]
                                               (when app-id [:= :app-id app-id]))))
         (mapv row->device)))

  ports/IDeviceRemoval

  (remove-device! [_ user-id device-token tenant-id]
    (database/execute-update! db
                              (-> (h/delete-from :push-device-tokens)
                                  (h/where [:= :user-id user-id] [:= :token device-token]
                                           [:= :tenant-id (tc/string->uuid tenant-id)])))))

;; =============================================================================
;; PushAnalyticsStore
;; =============================================================================

(defn- insert-event! [db event]
  (database/execute-update! db
                            (-> (h/insert-into :push-analytics-events)
                                (h/values [{:id                  (:id event)
                                            :notification-id     (name (:notification-id event))
                                            :device-token        (:device-token event)
                                            :platform            (name (:platform event))
                                            :event-type          (name (:event-type event))
                                            :user-id             (:user-id event)
                                            :provider-message-id (:provider-message-id event)
                                            :callback-id         (:callback-id event)
                                            :error-message       (get-in event [:error :message])
                                            ;; A send carries its job's tenant, a callback `callback-tenant`'s
                                            :tenant-id           (tc/string->uuid (:tenant-id event))
                                            :timestamp           (or (->instant (:timestamp event)) (Instant/now))}]))))

(defn- callback-tenant
  "`event` with the tenant of the send its callback id names. Without a
   recorded send, the token's tenant, or none when the token is registered in
   several: one tenant's callback must not count in another's (BOU-990)."
  [db event]
  (if-let [sent (when (:callback-id event)
                  (database/execute-one! db (-> (h/select :tenant-id)
                                                (h/from :push-analytics-events)
                                                (h/where [:= :callback-id (:callback-id event)] [:= :event-type "sent"])
                                                (h/limit 1))))]
    (assoc event :tenant-id (:tenant-id sent))
    (let [tenants (set (map :tenant-id (token-rows db (:device-token event))))]
      (assoc event :tenant-id (when (= 1 (count tenants)) (first tenants))))))

(defn- record-callback!
  "A push's callback, counted once per event type: the index on (callback_id,
   event_type) refuses a replay. In a savepoint, since PostgreSQL refuses the
   rest of a transaction after a failed statement."
  [db event]
  (let [event (callback-tenant db event)]
    (if-not (:callback-id event)
      (insert-event! db event)
      (try
        (database/in-savepoint (database/current-transaction db) #(insert-event! db event))
        (catch clojure.lang.ExceptionInfo e
          (if (database/unique-conflict? e) 0 (throw e)))))))

(defrecord PushAnalyticsStore [db]
  ports/IPushAnalyticsStore

  (record-send! [_ event]
    (insert-event! db event))

  (record-delivery! [_ event]
    (record-callback! db (assoc event :event-type :delivered)))

  (record-open! [_ event]
    (record-callback! db (assoc event :event-type :opened)))

  (get-push-stats [_ notification-id opts]
    (let [rows   (database/execute-query! db
                                          (-> (h/select :event-type [[:count :*] :cnt])
                                              (h/from :push-analytics-events)
                                              (h/where [:= :notification-id (name notification-id)]
                                                       ;; One tenant's counts, nil for no tenant's (BOU-946)
                                                       (when (contains? opts :tenant-id)
                                                         [:= :tenant-id (tc/string->uuid (:tenant-id opts))]))
                                              (h/group-by :event-type)))
          counts (reduce (fn [m {:keys [event-type cnt]}]
                           (assoc m (keyword event-type) cnt))
                         {:sent 0 :delivered 0 :opened 0 :failed 0}
                         rows)]
      (assoc counts :notification-id notification-id)))

  (cleanup-old-events! [_ retention-days]
    (let [cutoff (days-ago retention-days)]
      (database/execute-update! db
                                (-> (h/delete-from :push-analytics-events)
                                    (h/where [:< :timestamp cutoff]))))))
