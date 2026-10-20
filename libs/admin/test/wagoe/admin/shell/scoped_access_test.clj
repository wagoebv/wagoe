(ns wagoe.admin.shell.scoped-access-test
  "The admin under `:wagoe/authorization` (ADR-041 §6): `admin.<entity>/read`
   and `/write` in the user's scopes under `:enforce`, today's role rule under
   `:off` and `:report`. The rights are handed in as the platform resolves
   them, so this runs the admin's routes, service and H2 without the user
   module."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [reitit.ring :as ring]
            [wagoe.admin.core.permissions :as permissions]
            [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.http :as admin-http]
            [wagoe.admin.shell.scoped-service :as scoped-service]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.shell.service :as service]
            [wagoe.i18n.shell.catalogue :as i18n-catalogue]
            [wagoe.observability.errors.shell.adapters.no-op :as error-reporting-no-op]
            [wagoe.observability.logging.shell.adapters.no-op :as logging-no-op]
            [wagoe.platform.core.http.nav :as nav]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]))

(def ^:private settings
  {:enabled?         true
   :base-path        "/web/admin"
   :entity-discovery {:mode :allowlist :allowlist #{:orders :notes :lines}}
   :entities         {:orders {:label "Orders" :list-fields [:number :domain-id]
                               :scope {:domain-id :domain-id}}
                      ;; A line's domain is its order's
                      :lines  {:label "Lines" :list-fields [:sku]
                               :scope {:domain-id (fn [ids] [:in :order_id {:select [:id] :from [:orders]
                                                                            :where  [:in :domain_id ids]}])}}
                      :notes  {:label "Notes" :list-fields [:body]}}
   :pagination       {:default-page-size 50 :max-page-size 200}})

(def ^:private o1 "00000000-0000-0000-0000-0000000000a1")
(def ^:private o2 "00000000-0000-0000-0000-0000000000a2")
(def ^:private o3 "00000000-0000-0000-0000-0000000000a3")
(def ^:private l1 "00000000-0000-0000-0000-0000000000b1")
(def ^:private l3 "00000000-0000-0000-0000-0000000000b3")

(defonce ^:private state (atom nil))

(defn- reset-rows! [db-ctx]
  (db/execute-update! db-ctx {:raw "DELETE FROM lines"})
  (db/execute-update! db-ctx {:raw "DELETE FROM orders"})
  (db/execute-update! db-ctx {:insert-into :orders
                              :values [{:id o1 :number "ORD-42-A" :domain_id 42}
                                       {:id o2 :number "ORD-42-B" :domain_id 42}
                                       {:id o3 :number "ORD-7-C" :domain_id 7}]})
  (db/execute-update! db-ctx {:insert-into :lines
                              :values [{:id l1 :sku "SKU-42-A" :order_id o1}
                                       {:id l3 :sku "SKU-7-C" :order_id o3}]}))

(defn- with-db [f]
  (let [db-ctx (db-factory/db-context {:adapter :h2 :database-path "mem:admin_scoped_access;DB_CLOSE_DELAY=-1"})]
    (try
      (db/execute-update! db-ctx {:raw "CREATE TABLE IF NOT EXISTS orders (id VARCHAR(36) PRIMARY KEY, number VARCHAR(50) NOT NULL, domain_id INT NOT NULL)"})
      (db/execute-update! db-ctx {:raw "CREATE TABLE IF NOT EXISTS notes (id VARCHAR(36) PRIMARY KEY, body VARCHAR(200))"})
      (db/execute-update! db-ctx {:raw "CREATE TABLE IF NOT EXISTS lines (id VARCHAR(36) PRIMARY KEY, sku VARCHAR(50) NOT NULL, order_id VARCHAR(36) NOT NULL)"})
      (let [provider (schema-repo/create-schema-repository db-ctx settings {})]
        (reset! state {:db-ctx   db-ctx
                       :provider provider
                       :service  (service/create-admin-service db-ctx provider
                                                               (logging-no-op/create-logging-component {})
                                                               (error-reporting-no-op/create-error-reporting-component {})
                                                               settings)}))
      (f)
      (finally
        (db/execute-update! db-ctx {:raw "DROP TABLE IF EXISTS lines"})
        (db/execute-update! db-ctx {:raw "DROP TABLE IF EXISTS orders"})
        (db/execute-update! db-ctx {:raw "DROP TABLE IF EXISTS notes"})
        (db-factory/close-db-context! db-ctx)
        (reset! state nil)))))

(use-fixtures :once with-db)
(use-fixtures :each (fn [f] (reset-rows! (:db-ctx @state)) (f)))

(def ^:private catalogue
  (i18n-catalogue/create-map-catalogue (i18n-catalogue/load-catalogue "wagoe/i18n/translations")))

(defn- config
  "Routes config under `mode`, every user holding `rights` (by id)."
  [mode rights]
  (cond-> (assoc settings :authorization-mode mode)
    (not= :off mode)
    (assoc :access (fn [user]
                     {:mode            mode
                      :platform-admin? (and (= :admin (:role user)) (nil? (:tenant-id user)))
                      :rights          (get rights (:id user) [])}))))

(defn- handler [cfg]
  (let [{:keys [service provider]} @state
        app (ring/ring-handler (ring/router (admin-http/web-routes service provider cfg nil) {:conflicts nil}))]
    (fn [request]
      (try (app request)
           (catch clojure.lang.ExceptionInfo e
             {:status (case (:type (ex-data e)) :not-found 404 :forbidden 403 500)
              :body   (ex-message e)})))))

(defn- request
  ([user method uri] (request user method uri nil))
  ([user method uri form]
   (cond-> {:request-method       method
            :uri                  uri
            :headers              {}
            :query-params         {}
            :user                 user
            :i18n/catalogue       catalogue
            :i18n/default-locale  :en}
     form (assoc :form-params form))))

(def ^:private viewer {:id #uuid "00000000-0000-0000-0000-000000000011" :name "Viewer"})
(def ^:private editor {:id #uuid "00000000-0000-0000-0000-000000000012" :name "Editor"})
(def ^:private platform-admin {:id #uuid "00000000-0000-0000-0000-000000000013" :role :admin})
(def ^:private tenant-admin {:id #uuid "00000000-0000-0000-0000-000000000014" :role :admin
                             :tenant-id #uuid "00000000-0000-0000-0000-0000000000f1"})

(def ^:private in-42 {:domain-id #{42}})

(def ^:private rights
  ;; What the admin.orders/viewer and /editor roles give, assigned with scope {:domain-id 42}
  {(:id viewer) [{:permission :admin.orders/read :scope in-42}]
   (:id editor) [{:permission :admin.orders/read :scope in-42}
                 {:permission :admin.orders/write :scope in-42}]})

(defn- numbers [body]
  (set (re-seq #"ORD-[0-9]+-[A-C]" (str body))))

(defn- domain-of [id]
  (some-> (db/execute-one! (:db-ctx @state) {:select [:domain_id] :from [:orders] :where [:= :id id]})
          vals first))

(deftest ^:integration a-viewer-scoped-to-domain-42-sees-only-it-and-cannot-edit
  (let [app (handler (config :enforce rights))
        list-page (app (request viewer :get "/orders"))]
    (is (= 200 (:status list-page)))
    (is (= #{"ORD-42-A" "ORD-42-B"} (numbers (:body list-page))))
    (is (not (str/includes? (:body list-page) "/web/admin/orders/new")) "no New button")
    (testing "search stays inside the scope"
      (is (= #{} (numbers (:body (app (assoc (request viewer :get "/orders") :query-params {"search" "ORD-7"}))))))
      (is (= #{"ORD-42-A"} (numbers (:body (app (assoc (request viewer :get "/orders") :query-params {"search" "ORD-42-A"})))))))
    (testing "may not edit, create or delete"
      (is (= 403 (:status (app (request viewer :get (str "/orders/" o1))))))
      (is (= 403 (:status (app (request viewer :put (str "/orders/" o1) {"number" "X"})))))
      (is (= 403 (:status (app (request viewer :post "/orders" {"number" "N" "domain-id" "42"})))))
      (is (= 403 (:status (app (request viewer :delete (str "/orders/" o1))))))
      (is (= 42 (domain-of o1))))
    (testing "an entity it holds nothing on"
      (is (= 403 (:status (app (request viewer :get "/notes"))))))))

(deftest ^:integration an-editor-changes-only-records-inside-its-scope
  (let [app (handler (config :enforce rights))]
    (testing "outside the scope is not found"
      (is (= 404 (:status (app (request editor :get (str "/orders/" o3))))))
      (is (= 404 (:status (app (request editor :put (str "/orders/" o3) {"number" "X"})))))
      (is (= 404 (:status (app (request editor :delete (str "/orders/" o3))))))
      (is (= 404 (:status (app (request editor :post "/orders/bulk-delete" {"ids[]" [o1 o3]})))))
      (is (= 7 (domain-of o3)))
      (is (= 42 (domain-of o1)) "nothing of the batch was deleted"))
    (testing "inside it, a record opens"
      (is (= 200 (:status (app (request editor :get (str "/orders/" o1)))))))
    (testing "a write may not move a record outside, nor create one there"
      (is (= 422 (:status (app (request editor :put (str "/orders/" o1) {"number" "ORD-42-A" "domain-id" "7"})))))
      (is (= 42 (domain-of o1)))
      (is (= 422 (:status (app (request editor :post "/orders" {"number" "ORD-7-D" "domain-id" "7"})))))
      (is (nil? (db/execute-one! (:db-ctx @state) {:select [:id] :from [:orders] :where [:= :number "ORD-7-D"]}))))
    (testing "and creates one inside"
      (is (= 200 (:status (app (request editor :post "/orders" {"number" "ORD-42-D" "domain-id" "42"})))))
      (is (some? (db/execute-one! (:db-ctx @state) {:select [:id] :from [:orders] :where [:= :number "ORD-42-D"]}))))))

(deftest ^:integration a-relation-condition-scopes-like-a-field
  (let [app    (handler (config :enforce {(:id editor) [{:permission :admin.lines/read :scope in-42}
                                                        {:permission :admin.lines/write :scope in-42}]}))
        skus   #(set (re-seq #"SKU-[0-9]+-[A-C]" (str %)))]
    (is (= #{"SKU-42-A"} (skus (:body (app (request editor :get "/lines"))))))
    (is (= 404 (:status (app (request editor :delete (str "/lines/" l3))))))
    (testing "a write it cannot check on the record's values is refused"
      (is (= 403 (:status (app (request editor :put (str "/lines/" l1) {"sku" "SKU-42-B"})))))
      (is (= 403 (:status (app (request editor :post "/lines" {"sku" "SKU-42-E" "order-id" o1}))))))))

(deftest ^:integration an-entity-mapping-no-scope-admits-only-unscoped-holders
  (let [scoped   {(:id viewer) [{:permission :admin.notes/read :scope in-42}]}
        unscoped {(:id viewer) [{:permission :admin.notes/read}]}]
    (is (= 403 (:status ((handler (config :enforce scoped)) (request viewer :get "/notes")))))
    (is (= 200 (:status ((handler (config :enforce unscoped)) (request viewer :get "/notes")))))))

(deftest ^:integration the-platform-admin-passes-and-a-tenant-admin-does-not
  (let [app (handler (config :enforce rights))]
    (is (= #{"ORD-42-A" "ORD-42-B" "ORD-7-C"} (numbers (:body (app (request platform-admin :get "/orders"))))))
    (is (= 403 (:status (app (request tenant-admin :get "/orders")))))
    (is (= 403 (:status (app (request tenant-admin :get "/")))) "the admin home needs read on one entity")))

(deftest ^:integration off-and-report-keep-the-role-rule
  (doseq [mode [:off :report]]
    (testing (name mode)
      (let [app (handler (config mode rights))]
        (is (= #{"ORD-42-A" "ORD-42-B" "ORD-7-C"} (numbers (:body (app (request tenant-admin :get "/orders")))))
            "an :admin sees everything, whatever its rights")
        (is (= 403 (:status (app (request viewer :get "/orders")))) "rights admit no one without the role")))))

(deftest ^:integration the-sidebar-lists-only-readable-entities-under-enforce
  (let [routes (admin-http/admin-routes nil (:provider @state) (config :enforce rights) nil)
        hrefs  (fn [user] (->> (nav/for-user [(:admin ((:nav routes)))] "/web" user)
                               (mapcat :items) (mapv :href) set))]
    (is (= #{"/web/admin/orders"} (hrefs viewer)))
    (is (= #{} (hrefs tenant-admin)))
    (is (= #{"/web/admin/orders" "/web/admin/notes" "/web/admin/lines"} (hrefs platform-admin)))))

(deftest ^:integration a-parents-children-show-only-where-their-own-read-admits-them
  (let [{:keys [service provider]} @state
        rel      {:entity :lines :table :lines :foreign-key :order-id :label "Lines" :fields [:sku]}
        children (fn [rights]
                   (let [user (permissions/with-access editor {:mode :enforce :platform-admin? false :rights rights})]
                     (mapv :sku (ports/list-related-entities (scoped-service/for-user service provider user)
                                                             :orders o1 rel))))]
    (is (= [] (children (get rights (:id editor)))) "no read on lines")
    (is (= ["SKU-42-A"] (children [{:permission :admin.lines/read :scope in-42}])))))
