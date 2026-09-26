(ns wagoe.admin.shell.lifecycle-events-test
  "Admin writes publish lifecycle events on the event bus (BOU-492), through
   the admin's own HTTP handlers and the components its wiring builds."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [integrant.core :as ig]
            [reitit.ring :as ring]
            [wagoe.admin.shell.http :as admin-http]
            [wagoe.admin.ports :as admin-ports]
            [wagoe.admin.shell.module-wiring :as wiring]
            [wagoe.admin.shell.service :as service]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.events.ports :as events]
            [wagoe.events.shell.module-wiring]
            [wagoe.observability.errors.shell.adapters.no-op :as error-reporting-no-op]
            [wagoe.observability.logging.shell.adapters.no-op :as logging-no-op]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.adapters.database.factory :as db-factory])
  (:import [java.util UUID]))

(def ^:private admin-config
  {:enabled?         true
   :base-path        "/web/admin"
   :require-role     :admin
   :entity-discovery {:mode :allowlist :allowlist #{:invoices}}
   :entities         {:invoices {:label "Invoices" :hide-fields #{:internal-note}}}})

(def ^:private admin-user
  {:id #uuid "00000000-0000-0000-0000-000000000001" :email "admin@example.com"
   :name "Admin" :role :admin :active true})

(def ^:dynamic *db* nil)

(use-fixtures :once
  (fn [f]
    (let [ctx (db-factory/db-context {:adapter       :h2
                                      :database-path "mem:admin_lifecycle_events;DB_CLOSE_DELAY=-1"
                                      :pool          {:minimum-idle 1 :maximum-pool-size 3}})]
      (db/execute-update! ctx {:raw "CREATE TABLE IF NOT EXISTS invoices (
                                       id UUID PRIMARY KEY,
                                       number VARCHAR(50) NOT NULL,
                                       status VARCHAR(20) NOT NULL,
                                       internal_note VARCHAR(200),
                                       created_at TIMESTAMP,
                                       updated_at TIMESTAMP)"})
      (try (binding [*db* ctx] (f))
           (finally
             (db/execute-update! ctx {:raw "DROP TABLE IF EXISTS invoices"})
             (db-factory/close-db-context! ctx))))))

(use-fixtures :each
  (fn [f]
    (db/execute-update! *db* {:raw "DELETE FROM invoices"})
    (f)))

(defn- handler
  "The admin web routes, over a service built the way the wiring builds it."
  [event-publisher]
  (let [provider (schema-repo/create-schema-repository *db* admin-config)
        service  (ig/init-key :wagoe/admin-service
                              (cond-> {:db-ctx          *db*
                                       :schema-provider provider
                                       :logger          (logging-no-op/create-logging-component {})
                                       :error-reporter  (error-reporting-no-op/create-error-reporting-component {})
                                       :config          admin-config}
                                event-publisher (assoc :event-publisher event-publisher)))
        app      (ring/ring-handler
                  (ring/router [["/web/admin" (admin-http/web-routes service provider admin-config nil)]]
                               {:conflicts nil}))]
    (fn [method uri & [form]]
      (app {:request-method method :uri uri :user admin-user
            :headers {} :query-params {} :form-params (or form {})}))))

(defn- collect!
  "Subscribe to the admin topic; returns an atom of received events."
  [bus]
  (let [seen (atom [])]
    (events/subscribe! bus :admin #(swap! seen conj %))
    seen))

(defn- await-event
  "The first received event of `type`, waiting briefly: delivery is async."
  [seen type]
  (loop [n 0]
    (or (some #(when (= type (:type %)) %) @seen)
        (when (< n 100) (Thread/sleep 20) (recur (inc n))))))

(defn- invoice-row [number]
  (db/execute-one! *db* {:select [:*] :from [:invoices] :where [:= :number number]}))

(defn- with-bus [f]
  (let [bus (ig/init-key :wagoe/events {:provider :memory})]
    (try (f bus) (finally (ig/halt-key! :wagoe/events bus)))))

(deftest ^:integration admin-writes-publish-lifecycle-events
  (with-bus
    (fn [bus]
      (let [seen    (collect! bus)
            request (handler bus)]
        (testing "create"
          (is (#{200 302} (:status (request :post "/web/admin/invoices"
                                            {"number" "INV-1" "status" "draft"}))))
          (let [row   (invoice-row "INV-1")
                event (await-event seen :admin/entity-created)]
            (is (some? event) "a subscriber hears about the new invoice")
            (is (= :admin (:source event)))
            (is (= {:entity :invoices :id (:id row)}
                   (select-keys (:payload event) [:entity :id])))
            (is (uuid? (get-in event [:payload :id])))
            (is (= "INV-1" (get-in event [:payload :attrs :number])))
            (is (not (contains? (get-in event [:payload :attrs]) :internal-note))
                "hidden fields do not leave the process")))

        (testing "update carries the new and the prior values"
          (let [id (:id (invoice-row "INV-1"))]
            (is (= 200 (:status (request :put (str "/web/admin/invoices/" id)
                                         {"number" "INV-1" "status" "sent"}))))
            (let [event (await-event seen :admin/entity-updated)]
              (is (= {:entity :invoices :id id}
                     (select-keys (:payload event) [:entity :id])))
              (is (= "sent" (get-in event [:payload :attrs :status])))
              (is (= "draft" (get-in event [:payload :prior :status]))))))

        (testing "delete"
          (let [id (:id (invoice-row "INV-1"))]
            (is (= 200 (:status (request :delete (str "/web/admin/invoices/" id)))))
            (let [event (await-event seen :admin/entity-deleted)]
              (is (= {:entity :invoices :id id}
                     (select-keys (:payload event) [:entity :id])))
              (is (= "INV-1" (get-in event [:payload :attrs :number]))
                  "the record as it was, since it is gone"))))))))

(deftest ^:integration inline-edits-and-bulk-deletes-publish-too
  ;; Both write the table; a subscriber that missed them would drift from it.
  (with-bus
    (fn [bus]
      (let [seen    (collect! bus)
            request (handler bus)]
        (request :post "/web/admin/invoices" {"number" "INV-2" "status" "draft"})
        (request :post "/web/admin/invoices" {"number" "INV-3" "status" "draft"})
        (let [id2 (:id (invoice-row "INV-2"))
              id3 (:id (invoice-row "INV-3"))]
          (request :patch (str "/web/admin/invoices/" id2 "/status") {"status" "paid"})
          (let [event (await-event seen :admin/entity-updated)]
            (is (= id2 (get-in event [:payload :id])))
            (is (= "paid" (get-in event [:payload :attrs :status]))))

          (is (= 200 (:status (request :post "/web/admin/invoices/bulk-delete" {"ids[]" [(str id2) (str id3)]}))))
          (loop [n 0]
            (when (and (< n 100) (< (count (filter #(= :admin/entity-deleted (:type %)) @seen)) 2))
              (Thread/sleep 20) (recur (inc n))))
          (is (= #{id2 id3}
                 (->> @seen
                      (filter #(= :admin/entity-deleted (:type %)))
                      (map (comp :id :payload))
                      set))))))))

(deftest ^:integration a-write-that-changed-nothing-publishes-nothing
  (with-bus
    (fn [bus]
      (let [seen    (collect! bus)
            request (handler bus)]
        (is (not= 200 (:status (request :delete (str "/web/admin/invoices/" (UUID/randomUUID))))))
        (request :post "/web/admin/invoices/bulk-delete" {"ids[]" [(str (UUID/randomUUID))]})
        ;; Nothing to wait for; give a stray event the time it would need.
        (Thread/sleep 200)
        (is (empty? @seen))))))

(deftest ^:integration admin-works-without-an-event-bus
  (let [request (handler nil)]
    (is (#{200 302} (:status (request :post "/web/admin/invoices"
                                      {"number" "INV-4" "status" "draft"}))))
    (let [id (:id (invoice-row "INV-4"))]
      (is (some? id))
      (is (= 200 (:status (request :put (str "/web/admin/invoices/" id)
                                   {"number" "INV-4" "status" "sent"}))))
      (is (= "sent" (:status (invoice-row "INV-4"))))
      (is (= 200 (:status (request :delete (str "/web/admin/invoices/" id)))))
      (is (nil? (invoice-row "INV-4"))))))

(deftest ^:integration a-failing-bus-does-not-fail-the-write
  ;; The row is committed before anything is published; an error would tell
  ;; the user a saved invoice was not saved.
  (doseq [[why bus] {"publish! throws"
                     (reify events/IEventPublisher
                       (publish! [_ _ _] (throw (ex-info "broker down" {:type :events/publish-failed}))))
                     "publish! returns an error"
                     (reify events/IEventPublisher
                       (publish! [_ _ _] {:error {:type :events/publish-failed :message "down"}}))}]
    (testing why
      (let [request (handler bus)]
        (request :post "/web/admin/invoices" {"number" "INV-5" "status" "draft"})
        (let [id (:id (invoice-row "INV-5"))]
          (is (some? id))
          (is (= 200 (:status (request :put (str "/web/admin/invoices/" id)
                                       {"number" "INV-5" "status" "sent"}))))
          (is (= 200 (:status (request :delete (str "/web/admin/invoices/" id)))))
          (is (nil? (invoice-row "INV-5"))))))))

(deftest ^:integration a-bulk-delete-stops-publishing-after-the-first-failure
  ;; Each failed publish can cost the broker's timeout on the request thread;
  ;; a bulk delete during an outage paid it once per row.
  (let [calls   (atom 0)
        bus     (reify events/IEventPublisher
                  (publish! [_ _ _]
                    (swap! calls inc)
                    {:error {:type :events/publish-failed :message "down"}}))
        request (handler bus)]
    (doseq [n ["INV-6" "INV-7" "INV-8"]]
      (request :post "/web/admin/invoices" {"number" n "status" "draft"}))
    (reset! calls 0)
    (let [ids (mapv #(str (:id (invoice-row %))) ["INV-6" "INV-7" "INV-8"])]
      (is (= 200 (:status (request :post "/web/admin/invoices/bulk-delete" {"ids[]" ids}))))
      (is (= 1 @calls))
      (is (nil? (invoice-row "INV-6")) "the delete itself went through"))))

(defn- counting-inner
  "The real service, counting get-entity calls; `bulk-delete` replaces its
   bulk delete when given."
  [provider get-calls & [bulk-delete]]
  (let [real (service/create-admin-service *db* provider nil nil admin-config)]
    #_{:clj-kondo/ignore [:missing-protocol-method]}
    (reify admin-ports/IAdminService
      (get-entity [_ e id] (swap! get-calls inc) (admin-ports/get-entity real e id))
      (bulk-delete-entities [_ e ids]
        (if bulk-delete (bulk-delete e ids) (admin-ports/bulk-delete-entities real e ids))))))

(deftest ^:integration bulk-delete-reads-its-priors-in-one-query
  (with-bus
    (fn [bus]
      (let [seen      (collect! bus)
            request   (handler bus)
            _         (doseq [n ["INV-9" "INV-10"]]
                        (request :post "/web/admin/invoices" {"number" n "status" "draft"}))
            ids       (mapv #(:id (invoice-row %)) ["INV-9" "INV-10"])
            provider  (schema-repo/create-schema-repository *db* admin-config)
            get-calls (atom 0)
            svc       (service/->PublishingAdminService (counting-inner provider get-calls)
                                                        *db* provider bus)]
        (reset! seen [])
        (is (= 2 (:success-count (admin-ports/bulk-delete-entities svc :invoices ids))))
        (is (zero? @get-calls) "no read per id")
        (loop [n 0]
          (when (and (< n 100) (< (count @seen) 2)) (Thread/sleep 20) (recur (inc n))))
        (is (= {(first ids) "INV-9" (second ids) "INV-10"}
               (into {} (map (juxt (comp :id :payload) (comp :number :attrs :payload))) @seen)))))))

(deftest ^:integration a-bulk-delete-that-lost-a-race-publishes-nothing
  ;; Another request deleted one of the rows between the read of the priors
  ;; and this delete. The adapter reports a count, not which rows, so the
  ;; events are skipped rather than duplicated.
  (with-bus
    (fn [bus]
      (let [seen     (collect! bus)
            request  (handler bus)
            _        (doseq [n ["INV-11" "INV-12"]]
                       (request :post "/web/admin/invoices" {"number" n "status" "draft"}))
            ids      (mapv #(:id (invoice-row %)) ["INV-11" "INV-12"])
            provider (schema-repo/create-schema-repository *db* admin-config)
            real     (service/create-admin-service *db* provider nil nil admin-config)
            racing   (fn [e ids]
                       (admin-ports/delete-entity real e (first ids))
                       (admin-ports/bulk-delete-entities real e ids))
            svc      (service/->PublishingAdminService (counting-inner provider (atom 0) racing)
                                                       *db* provider bus)]
        (reset! seen [])
        (is (= 1 (:success-count (admin-ports/bulk-delete-entities svc :invoices ids))))
        (Thread/sleep 200)
        (is (empty? @seen))))))

(deftest ^:unit the-bus-is-wired-only-when-it-is-configured
  (let [service-deps #(get-in (wiring/ig-config {} {:enabled %}) [:components :wagoe/admin-service])]
    (is (= (ig/ref :wagoe/events) (:event-publisher (service-deps #{:wagoe/events}))))
    (is (not (contains? (service-deps #{}) :event-publisher))
        "a ref to an absent component would fail the boot")))
