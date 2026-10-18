(ns wagoe.tenant.shell.data-export-test
  "A tenant's data export, and its route, on H2 and PostgreSQL."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [reitit.ring :as ring]
            [support.embedded-pg :as epg]
            [wagoe.platform.ports.privacy :as privacy]
            [wagoe.platform.shell.adapters.database.factory :as factory]
            [wagoe.platform.shell.privacy :as platform-privacy]
            [wagoe.tenant.ports :as ports]
            [wagoe.tenant.shell.data-export :as sut]
            [wagoe.tenant.shell.http :as http]
            [wagoe.tenant.shell.invite-persistence :as invite-persistence]
            [wagoe.tenant.shell.membership-persistence :as membership-persistence]
            [wagoe.tenant.shell.persistence :as tenant-persistence])
  (:import [java.time Instant]
           [java.time.temporal ChronoUnit]))

(defn- backends []
  [["h2" (fn []
           (let [ctx (factory/db-context
                      (factory/h2-config (str "mem:tenant_export_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")))]
             [ctx #(factory/close-db-context! ctx)]))]
   ["postgresql" (fn []
                   (let [pg  (epg/start!)
                         ctx (epg/db-context pg)]
                     [ctx (fn []
                            (factory/close-db-context! ctx)
                            (epg/stop! pg))]))]])

(defn- each-backend [f]
  (doseq [[label open] (backends)]
    (testing label
      (let [[ctx close!] (open)]
        (try
          (tenant-persistence/initialize-tenant-schema! ctx)
          (f {:tenants     (tenant-persistence/create-tenant-repository ctx nil nil)
              :memberships (membership-persistence/create-membership-repository ctx nil nil)
              :invites     (invite-persistence/create-invite-repository ctx nil nil)})
          (finally (close!)))))))

(defn- exporter [{:keys [tenants memberships invites]}]
  (sut/map->TenantDataExporter {:tenant-repository     tenants
                                :membership-repository memberships
                                :invite-repository     invites}))

(defn- seed! [{:keys [tenants memberships invites]}]
  (let [now    (.truncatedTo (Instant/now) ChronoUnit/MILLIS)
        tenant (ports/create-tenant tenants {:id (random-uuid) :slug "acme" :name "Acme"
                                             :schema-name "tenant_acme" :status :active :created-at now})
        member (fn [user-id]
                 (ports/create-membership memberships {:id (random-uuid) :tenant-id (:id tenant) :user-id user-id
                                                       :role :member :status :active
                                                       ;; one instant for all: paging must not hang on created_at
                                                       :invited-at now :created-at now}))
        users  (vec (repeatedly 3 random-uuid))]
    (run! member users)
    (ports/create-invite invites {:id (random-uuid) :tenant-id (:id tenant) :email "new@example.com"
                                  :role :member :status :pending :token-hash (apply str (repeat 64 "a"))
                                  :expires-at (.plus now 7 ChronoUnit/DAYS) :created-at now})
    {:tenant tenant :users users}))

(deftest ^:integration a-tenant-export-holds-its-members-and-invites
  (each-backend
   (fn [repos]
     (let [{:keys [tenant users]} (seed! repos)
           exporter (exporter repos)
           section  (privacy/export-data exporter {:type :tenant :id (:id tenant)})]
       (is (= "acme" (get-in section [:tenant :slug])))
       (is (= (set users) (set (map :user-id (:memberships section)))))
       (is (= ["new@example.com"] (map :email (:invites section))))
       (is (not (re-find #"token" (pr-str section))) "an invite's token hash stays out")

       (testing "every member, read past one page, each once"
         (with-redefs-fn {#'sut/page-size 1}
           #(is (= (sort (map str users))
                   (sort (map (comp str :user-id)
                              (:memberships (privacy/export-data exporter {:type :tenant :id (str (:id tenant))}))))))))

       (testing "a user's section is their memberships; nothing for strangers"
         (is (= [(:id tenant)] (map :tenant-id (:memberships (privacy/export-data exporter {:type :user :id (first users)})))))
         (is (nil? (privacy/export-data exporter {:type :user :id (random-uuid)})))
         (is (nil? (privacy/export-data exporter {:type :tenant :id (random-uuid)})))
         (is (nil? (privacy/export-data exporter {:type :tenant :id "acme"}))))))))

(deftest ^:integration ^:security only-a-platform-admin-exports-a-tenant
  (each-backend
   (fn [repos]
     (let [{:keys [tenant]} (seed! repos)
           data-export (platform-privacy/create-data-export {:tenant (exporter repos)})
           app   (ring/ring-handler (ring/router ["/api/v1" (:api (http/export-routes data-export))]))
           fetch (fn [user id] (app {:request-method :get :uri (str "/api/v1/tenants/" id "/export")
                                     :user user :headers {}}))
           admin {:id (random-uuid) :role :admin}]
       (let [r (fetch admin (:id tenant))]
         (is (= 200 (:status r)))
         (is (re-find #"attachment" (get-in r [:headers "Content-Disposition"])))
         (is (= "Acme" (get-in (json/parse-string (:body r)) ["sections" "tenant" "tenant" "name"]))))
       (is (= 403 (:status (fetch (assoc admin :tenant-id (:id tenant)) (:id tenant))))
           "a tenant's own admin is not a platform admin")
       (is (= 403 (:status (fetch {:id (random-uuid) :role :user} (:id tenant)))))
       (is (= 404 (:status (fetch admin (random-uuid)))))
       (is (= 400 (:status (fetch admin "acme"))))))))
