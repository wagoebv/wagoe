(ns wagoe.webhooks.shell.persistence
  "`ISubscriptionStore` and `IDeliveryLog` on the application database, and the
   boot step that creates their tables."
  (:require [clojure.tools.logging :as log]
            [wagoe.core.utils.type-conversion :as tc]
            [wagoe.platform.database :as db]
            [wagoe.webhooks.ports :as ports])
  (:import (java.sql Timestamp)
           (java.time Instant)))

;; =============================================================================
;; Schema
;; =============================================================================

(def migration-resource "wagoe/webhooks/migrations/20261020100001-webhooks-tables.up.sql")

(defn create-tables!
  "Create the webhook tables when absent, for installations that boot without
   running `migrate up`."
  [ctx]
  (db/execute-migration-ddl! ctx migration-resource)
  (log/info "Webhook tables ready"))

;; =============================================================================
;; Conversion
;; =============================================================================

(defn- ->ts [^Instant i] (when i (Timestamp/from i)))


(defn- subscription->row [s]
  (cond-> {}
    (contains? s :id)            (assoc :id (str (:id s)))
    (contains? s :tenant-id)     (assoc :tenant_id (some-> (:tenant-id s) str))
    (contains? s :event-pattern) (assoc :event_pattern (:event-pattern s))
    (contains? s :url)           (assoc :url (:url s))
    (contains? s :secret)        (assoc :secret (:secret s))
    (contains? s :active?)       (assoc :active (boolean (:active? s)))
    (contains? s :description)   (assoc :description (:description s))
    (contains? s :created-at)    (assoc :created_at (->ts (:created-at s)))
    (contains? s :updated-at)    (assoc :updated_at (->ts (:updated-at s)))))

(defn- row->subscription [row]
  (when row
    {:id            (:id row)
     :tenant-id     (:tenant-id row)
     :event-pattern (:event-pattern row)
     :url           (:url row)
     :secret        (:secret row)
     :active?       (tc/int->boolean (:active row))
     :description   (:description row)
     :created-at    (tc/string->instant (:created-at row))
     :updated-at    (tc/string->instant (:updated-at row))}))

(defn- attempt->row [a]
  {:id              (str (:id a))
   :subscription_id (str (:subscription-id a))
   :tenant_id       (some-> (:tenant-id a) str)
   :event_id        (str (:event-id a))
   :topic           (:topic a)
   :attempt         (:attempt a)
   :status          (name (:status a))
   :status_code     (:status-code a)
   :latency_ms      (:latency-ms a)
   :response        (:response a)
   :error           (:error a)
   :created_at      (->ts (:created-at a))})

(defn- row->attempt [row]
  {:id              (:id row)
   :subscription-id (:subscription-id row)
   :tenant-id       (:tenant-id row)
   :event-id        (:event-id row)
   :topic           (:topic row)
   :attempt         (:attempt row)
   :status          (keyword (:status row))
   :status-code     (:status-code row)
   :latency-ms      (:latency-ms row)
   :response        (:response row)
   :error           (:error row)
   :created-at      (tc/string->instant (:created-at row))})

(defn- page
  "`query` filtered by the non-nil `conditions`, one page of it."
  [query conditions {:keys [limit offset]}]
  (cond-> (assoc query :limit (or limit 100))
    (some some? conditions) (assoc :where (into [:and] (filter some?) conditions))
    offset                  (assoc :offset offset)))

;; =============================================================================
;; Store
;; =============================================================================

(defrecord JdbcWebhookStore [ctx]
  ports/ISubscriptionStore
  (save-subscription! [_ s]
    (db/execute-update! ctx {:insert-into :webhook_subscriptions
                             :values      [(subscription->row s)]})
    s)

  (update-subscription! [this id changes]
    (let [row (subscription->row (dissoc changes :id :tenant-id :created-at))]
      (when (seq row)
        (db/execute-update! ctx {:update :webhook_subscriptions
                                 :set    row
                                 :where  [:= :id (str id)]}))
      (ports/find-subscription this id)))

  (delete-subscription! [_ id]
    (pos? (db/execute-update! ctx {:delete-from :webhook_subscriptions
                                   :where       [:= :id (str id)]})))

  (find-subscription [_ id]
    (row->subscription
     (db/execute-one! ctx {:select [:*] :from [:webhook_subscriptions]
                           :where  [:= :id (str id)]})))

  (list-subscriptions [_ {:keys [tenant-id active?] :as opts}]
    (mapv row->subscription
          (db/execute-query! ctx (page {:select   [:*]
                                        :from     [:webhook_subscriptions]
                                        :order-by [[:created_at :desc] [:id :desc]]}
                                       [(when tenant-id [:= :tenant_id (str tenant-id)])
                                        (when (some? active?) [:= :active (boolean active?)])]
                                       opts))))

  (candidate-subscriptions [_ tenant-id]
    (mapv row->subscription
          (db/execute-query! ctx {:select [:*]
                                  :from   [:webhook_subscriptions]
                                  :where  [:and [:= :active true]
                                           (if tenant-id
                                             [:or [:= :tenant_id nil] [:= :tenant_id (str tenant-id)]]
                                             [:= :tenant_id nil])]})))

  ports/IDeliveryLog
  (record-attempt! [_ a]
    (db/execute-update! ctx {:insert-into :webhook_deliveries
                             :values      [(attempt->row a)]})
    a)

  (attempt-count [_ subscription-id event-id]
    (-> (db/execute-one! ctx {:select [[[:count :*] :n]]
                              :from   [:webhook_deliveries]
                              :where  [:and [:= :subscription_id (str subscription-id)]
                                       [:= :event_id (str event-id)]]})
        :n
        long))

  (list-attempts [_ {:keys [subscription-id event-id] :as opts}]
    (mapv row->attempt
          (db/execute-query! ctx (page {:select   [:*]
                                        :from     [:webhook_deliveries]
                                        :order-by [[:created_at :desc] [:attempt :desc]]}
                                       [(when subscription-id [:= :subscription_id (str subscription-id)])
                                        (when event-id [:= :event_id (str event-id)])]
                                       opts)))))

(defn create-store
  "The store on db-context `ctx`."
  [ctx]
  (->JdbcWebhookStore ctx))
