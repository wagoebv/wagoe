(ns wagoe.admin.shell.workflow-state-test
  "An entity with a workflow shows its state and links to the instance, and
   deleting it removes the instance (BOU-563). The admin reaches the workflow
   module only through `IEntityWorkflows`, which is faked here."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [integrant.core :as ig]
            [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.http.handlers.detail :as detail]
            [wagoe.admin.shell.http.handlers.list :as list]
            [wagoe.admin.shell.module-wiring :as wiring]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.shell.service :as service]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]))

^{:kaocha.testable/meta {:integration true :admin true}}

(def ^:private config
  {:entity-discovery {:mode :allowlist :allowlist #{:ws-invoices :ws-notes}}
   :entities         {:ws-invoices {:label       "Invoices"
                                    :list-fields [:number]
                                    :workflow    {:entity-type :invoice}}
                      :ws-notes    {:label "Notes" :list-fields [:body]}}
   :pagination       {:default-page-size 20 :max-page-size 200}})

(def ^:private admin {:id (random-uuid) :role :admin :active true})

(def ^:private ^:dynamic *db* nil)

(use-fixtures :once
  (fn [f]
    (let [ctx (db-factory/db-context {:adapter       :h2
                                      :database-path "mem:admin_workflow_state;DB_CLOSE_DELAY=-1"
                                      :pool          {:minimum-idle 1 :maximum-pool-size 3}})]
      (db/execute-update! ctx {:raw "CREATE TABLE ws_invoices (id UUID PRIMARY KEY, number VARCHAR(20) NOT NULL)"})
      (db/execute-update! ctx {:raw "CREATE TABLE ws_notes (id UUID PRIMARY KEY, body VARCHAR(20) NOT NULL)"})
      (try (binding [*db* ctx] (f))
           (finally
             (db/execute-update! ctx {:raw "DROP TABLE ws_invoices"})
             (db/execute-update! ctx {:raw "DROP TABLE ws_notes"})
             (db-factory/close-db-context! ctx))))))

(defn- fake-workflows
  "IEntityWorkflows over an atom of {[entity-type entity-id] instance}."
  [instances calls]
  (reify ports/IEntityWorkflows
    (entity-workflows [_ entity-type ids]
      (swap! calls conj [:read entity-type])
      (into {} (keep (fn [id] (some->> (get @instances [entity-type id]) (vector id)))) ids))
    (remove-entity-workflows! [_ entity-type id]
      (swap! calls conj [:remove entity-type id])
      (let [had? (contains? @instances [entity-type id])]
        (swap! instances dissoc [entity-type id])
        (if had? 1 0)))))

(defn- row! [table col value]
  (let [id (random-uuid)]
    (db/execute-update! *db* {:raw (str "INSERT INTO " table " (id, " col ") VALUES ('" id "', '" value "')")})
    id))

(defn- request [entity & [id]]
  (cond-> {:request-method :get :uri (str "/web/admin/" entity) :headers {}
           :user admin :session {:user admin}
           :path-params {:entity entity} :query-params {}}
    id (assoc-in [:path-params :id] (str id))))

(defn- system [instances calls]
  (let [workflows (fake-workflows instances calls)
        sp        (schema-repo/create-schema-repository *db* config)]
    {:sp  sp
     :svc (service/create-admin-service *db* sp nil nil config nil workflows)
     :cfg (assoc config :workflows workflows)}))

(deftest ^:integration list-and-detail-show-the-state-and-link-to-the-instance
  (let [invoice  (row! "ws_invoices" "number" "INV-7")
        instance (random-uuid)
        {:keys [svc sp cfg]} (system (atom {[:invoice invoice] {:instance-id instance :workflow-id :invoice-workflow
                                                                :state :delivered}})
                                     (atom []))
        link     (str "href=\"/web/admin/workflows/" instance "\"")]
    (testing "the list"
      (let [body (:body ((list/entity-list-handler svc sp cfg) (request "ws-invoices")))]
        (is (str/includes? body link))
        (is (str/includes? body "Delivered"))))
    (testing "the detail page"
      (let [body (:body ((detail/entity-detail-handler svc sp cfg) (request "ws-invoices" invoice)))]
        (is (str/includes? body link))
        (is (str/includes? body "Delivered"))))))

(deftest ^:integration an-entity-without-a-workflow-asks-nothing
  (let [calls (atom [])
        _     (row! "ws_notes" "body" "hello")
        {:keys [svc sp cfg]} (system (atom {}) calls)
        body  (:body ((list/entity-list-handler svc sp cfg) (request "ws-notes")))]
    (is (str/includes? body "hello"))
    (is (not (str/includes? body "/web/admin/workflows/")))
    (is (empty? @calls))))

(deftest ^:integration deleting-the-entity-removes-its-workflow-instance
  (let [invoice   (row! "ws_invoices" "number" "INV-8")
        instances (atom {[:invoice invoice] {:instance-id (random-uuid) :state :entered}})
        {:keys [svc]} (system instances (atom []))]
    (is (true? (ports/delete-entity svc :ws-invoices invoice)))
    (is (empty? @instances)))
  (testing "bulk delete too"
    (let [a         (row! "ws_invoices" "number" "INV-9")
          b         (row! "ws_invoices" "number" "INV-10")
          instances (atom {[:invoice a] {:instance-id (random-uuid) :state :entered}
                           [:invoice b] {:instance-id (random-uuid) :state :paid}})
          {:keys [svc]} (system instances (atom []))]
      (ports/bulk-delete-entities svc :ws-invoices [a b])
      (is (empty? @instances)))))

(deftest ^:unit the-workflow-port-is-wired-only-when-workflow-is-on
  (let [graph #(:components (wiring/ig-config {} {:enabled %}))]
    (testing "on"
      (let [g (graph #{:wagoe/admin :wagoe/workflow})]
        (is (= (ig/ref :wagoe/workflow-admin) (get-in g [:wagoe/admin-service :workflows])))
        (is (= (ig/ref :wagoe/workflow-admin) (get-in g [:wagoe/admin-routes :workflows])))))
    (testing "off: a ref to an absent component would fail the boot"
      (let [g (graph #{:wagoe/admin})]
        (is (not (contains? (:wagoe/admin-service g) :workflows)))
        (is (not (contains? (:wagoe/admin-routes g) :workflows)))))))
