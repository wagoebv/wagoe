(ns wagoe.push.shell.persistence
  "Persistence shell for push notification device tokens and analytics events.

   Implements IDeviceTokenStore and IPushAnalyticsStore protocols against a
   relational database via next.jdbc + HoneySQL.

   Upsert strategy: attempt INSERT, catch duplicate-key violation, then UPDATE.
   This approach is compatible with both H2 (tests) and PostgreSQL (production)
   without requiring dialect-specific ON CONFLICT syntax."
  (:require [wagoe.push.ports :as ports]
            [wagoe.push.core.device :as device]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [honey.sql.helpers :as h])
  (:import [java.sql SQLIntegrityConstraintViolationException]))

;; =============================================================================
;; Shared Helpers
;; =============================================================================

(def ^:private default-opts
  {:builder-fn rs/as-unqualified-kebab-maps})

(defn- execute-one! [db sqlmap]
  (jdbc/execute-one! db (sql/format sqlmap) default-opts))

(defn- execute! [db sqlmap]
  (jdbc/execute! db (sql/format sqlmap) default-opts))

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
  (execute-one! db
                (-> (h/insert-into :push-device-tokens)
                    (h/values [row]))))

(defn- update-device-row! [db token app-id active last-used-at device-name os-version]
  (execute-one! db
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
             :created-at  (:created-at record)
             :last-used-at (:last-used-at record)}]
    (try
      (insert-device-row! db row)
      (catch SQLIntegrityConstraintViolationException _
        ;; Duplicate token+app_id — update active status and last-used
        (update-device-row! db
                            (:token record) (:app-id record)
                            true (:last-used-at record)
                            (:device-name record) (:os-version record))))))

(defrecord DeviceTokenStore [db]
  ports/IDeviceTokenStore

  (register-device! [_ user-id device-info]
    (let [record (device/prepare-device-record user-id device-info (random-uuid) (java.util.Date.))]
      (upsert-device! db record)
      record))

  (unregister-device! [_ user-id device-token]
    (execute-one! db
                  (-> (h/delete-from :push-device-tokens)
                      (h/where [:and [:= :user-id user-id] [:= :token device-token]]))))

  (get-user-devices [_ user-id]
    (->> (execute! db
                   (-> (h/select :*)
                       (h/from :push-device-tokens)
                       (h/where [:and [:= :user-id user-id] [:= :active true]])))
         (mapv row->device)))

  (get-devices-by-platform [_ platform opts]
    (let [{:keys [limit offset] :or {limit 100 offset 0}} opts
          limit (min limit 1000)]
      (->> (execute! db
                     (-> (h/select :*)
                         (h/from :push-device-tokens)
                         (h/where [:and [:= :platform (name platform)] [:= :active true]])
                         (h/limit limit)
                         (h/offset offset)))
           (mapv row->device))))

  (mark-token-invalid! [_ device-token]
    (execute-one! db
                  (-> (h/update :push-device-tokens)
                      (h/set {:active false})
                      (h/where [:= :token device-token]))))

  (cleanup-stale-tokens! [_ max-age-days]
    (let [cutoff (java.sql.Timestamp/from
                  (.minus (java.time.Instant/now)
                          (java.time.Duration/ofDays max-age-days)))]
      (execute-one! db
                    (-> (h/delete-from :push-device-tokens)
                        (h/where [:and [:= :active false] [:< :last-used-at cutoff]]))))))

;; =============================================================================
;; PushAnalyticsStore
;; =============================================================================

(defrecord PushAnalyticsStore [db]
  ports/IPushAnalyticsStore

  (record-send! [_ event]
    (execute-one! db
                  (-> (h/insert-into :push-analytics-events)
                      (h/values [{:id                  (:id event)
                                  :notification-id     (name (:notification-id event))
                                  :device-token        (:device-token event)
                                  :platform            (name (:platform event))
                                  :event-type          (name (:event-type event))
                                  :user-id             (:user-id event)
                                  :provider-message-id (:provider-message-id event)
                                  :error-message       (:error event)
                                  :timestamp           (or (:timestamp event) (java.util.Date.))}]))))

  (record-delivery! [this event]
    (ports/record-send! this (assoc event :event-type :delivered)))

  (record-open! [this event]
    (ports/record-send! this (assoc event :event-type :opened)))

  (get-push-stats [_ notification-id _opts]
    (let [rows   (execute! db
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
      (execute-one! db
                    (-> (h/delete-from :push-analytics-events)
                        (h/where [:< :timestamp cutoff]))))))
