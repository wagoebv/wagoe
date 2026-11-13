(ns wagoe.user.shell.tenant-scope-test
  "A user listing holds the caller's tenant and nothing else (BOU-948), and a
   tenant's admin acts on no other tenant's user (BOU-953)."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [reitit.core :as r]
            [wagoe.user.schema :as schema]
            [wagoe.user.shell.http :as http]
            [wagoe.user.shell.rights-handlers :as rights-handlers]
            [wagoe.user.shell.web-handlers :as web-handlers]
            [wagoe.platform.shell.adapters.database.factory :as factory]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.persistence :as persistence]
            [wagoe.user.shell.tenant-scope :as sut])
  (:import [java.util UUID]))

(def ^:private tenant-a #uuid "a0000000-0000-0000-0000-00000000000a")
(def ^:private tenant-b #uuid "b0000000-0000-0000-0000-00000000000b")

(deftest ^:integration ^:security a-listing-holds-only-the-callers-tenant
  (let [ctx (factory/db-context
             (factory/h2-config (str "mem:user_scope_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")))]
    (try
      (persistence/initialize-user-schema! ctx)
      (let [repo   (persistence/create-user-repository ctx)
            add!   (fn [email tenant-id]
                     (ports/create-user repo (cond-> {:email email :name email :role :user
                                                      :active true :password-hash "x"}
                                               tenant-id (assoc :tenant-id tenant-id))))
            _      (add! "a@example.com" tenant-a)
            _      (add! "b@example.com" tenant-b)
            _      (add! "none@example.com" nil)
            listed (fn [request]
                     (some->> (sut/list-options request {})
                              (ports/find-users repo)
                              :users
                              (map :email)
                              set))
            user   (fn [m] (merge {:id (UUID/randomUUID) :role :user} m))]
        (testing "a member of the resolved tenant lists that tenant"
          (is (= #{"a@example.com"}
                 (listed {:user (user {}) :tenant {:id tenant-a}
                          :tenant-membership {:tenant-id tenant-a :role :member}}))))
        (testing "a tenant's own admin lists their tenant, wherever they ask"
          (is (= #{"a@example.com"} (listed {:user (user {:role :admin :tenant-id tenant-a})})))
          (is (= #{"a@example.com"} (listed {:user (user {:role :admin :tenant-id tenant-a})
                                             :tenant {:id tenant-a}}))))
        (testing "a member of the resolved tenant who administers another lists no one here"
          (is (nil? (listed {:user (user {:role :admin :tenant-id tenant-a}) :tenant {:id tenant-b}
                             :tenant-membership {:tenant-id tenant-b :role :member}}))))
        (testing "a caller outside the resolved tenant lists no one"
          (is (nil? (listed {:user (user {:role :admin :tenant-id tenant-a}) :tenant {:id tenant-b}})))
          (is (nil? (listed {:user (user {}) :tenant {:id tenant-b}}))))
        (testing "a user of no tenant lists the users of no tenant"
          (is (= #{"none@example.com"} (listed {:user (user {})}))))
        (testing "a platform admin lists everyone, or the resolved tenant"
          (is (= #{"a@example.com" "b@example.com" "none@example.com"}
                 (listed {:user (user {:role :admin})})))
          (is (= #{"b@example.com"}
                 (listed {:user (user {:role :admin}) :tenant {:id tenant-b}})))))
      (finally (factory/close-db-context! ctx)))))

(deftest ^:unit ^:security lists?-agrees-with-the-listing
  (let [of-a  {:id (UUID/randomUUID) :tenant-id tenant-a}
        none  {:id (UUID/randomUUID)}]
    (testing "a member of the resolved tenant may name its users only"
      (let [request {:user {:id (UUID/randomUUID) :role :user} :tenant {:id tenant-a}
                     :tenant-membership {:tenant-id tenant-a :role :admin}}]
        (is (sut/lists? request of-a))
        (is (not (sut/lists? request none)))))
    (testing "a caller outside the resolved tenant names no one"
      (is (not (sut/lists? {:user {:id (UUID/randomUUID) :role :admin :tenant-id tenant-a}
                            :tenant {:id tenant-b}}
                           (assoc of-a :tenant-id tenant-b)))))
    (testing "a platform admin names anyone"
      (is (sut/lists? {:user {:id (UUID/randomUUID) :role :admin}} of-a)))))

(def ^:private admin-of-a {:id (UUID/randomUUID) :role :admin :tenant-id tenant-a})
(def ^:private user-of-a  {:id (UUID/randomUUID) :role :user :tenant-id tenant-a})
(def ^:private user-of-b  {:id (UUID/randomUUID) :role :user :tenant-id tenant-b})
(def ^:private no-tenant  {:id (UUID/randomUUID) :role :user})

(defn- service
  "A user service holding the four users above; `acted` collects what it was told to do."
  [acted]
  (let [users (into {} (map (juxt :id identity)) [admin-of-a user-of-a user-of-b no-tenant])]
    #_{:clj-kondo/ignore [:missing-protocol-method]}
    (reify ports/IUserService
      (get-user-by-id [_ id] (get users id))
      (deactivate-user [_ id] (swap! acted conj [:deactivate id]) true)
      (update-user-profile [_ u] (swap! acted conj [:update (:id u)]) u)
      (register-user [_ u] (swap! acted conj [:register u]) (assoc u :id (UUID/randomUUID)))
      (list-users [_ _] {:users [] :total-count 0}))))

(deftest ^:unit ^:security a-route-naming-another-tenants-user-answers-404
  (let [wrapped ((sut/wrap-target-user (service (atom []))) (constantly {:status 200}))
        call    (fn [caller target & [tenant]]
                  (:status (wrapped (cond-> {:user caller :path-params {:id (str (:id target))}}
                                      tenant (assoc :tenant {:id tenant})))))]
    (testing "a tenant's admin reaches their tenant's users, and only those"
      (is (= 200 (call admin-of-a user-of-a)))
      (is (= 404 (call admin-of-a user-of-b)))
      (is (= 404 (call admin-of-a no-tenant)))
      (is (= 404 (call admin-of-a user-of-a tenant-b)) "on another tenant's host"))
    (testing "anyone reaches themselves"
      (is (= 200 (call user-of-b user-of-b tenant-a))))
    (testing "a platform admin reaches anyone; an unknown id is the handler's to answer"
      (is (= 200 (call {:id (UUID/randomUUID) :role :admin} user-of-b)))
      (is (= 200 (call {:id (UUID/randomUUID) :role :admin} {:id (UUID/randomUUID)}))
          "a soft-deleted user, whom no lookup finds, can still be erased")
      (is (= 200 (call admin-of-a {:id "not-a-uuid"}))))))

(deftest ^:unit ^:security a-bulk-action-skips-another-tenants-users
  (doseq [action ["deactivate" "role-admin"]]
    (testing action
      (let [acted (atom [])]
        ((web-handlers/bulk-update-users-htmx-handler (service acted) {})
         {:user        admin-of-a
          :form-params {"ids[]" (mapv (comp str :id) [user-of-a user-of-b no-tenant]) "action" action}})
        (is (= [(:id user-of-a)] (map second @acted)))))))

(deftest ^:unit ^:security a-tenants-admin-creates-users-in-their-tenant
  (let [create (fn [request]
                 (let [acted (atom [])]
                   [(:status ((web-handlers/create-user-htmx-handler (service acted) nil {})
                              (assoc request :form-params {"name" "New" "email" "new@example.com"
                                                           "password" "password123" "role" "admin"})))
                    (some-> @acted first second :tenant-id)]))]
    (testing "so the admin role they grant is their tenant's, not the platform's"
      (is (m/validate schema/CreateUserRequest {:name "New" :email "new@example.com" :password "password123"
                                                :role :admin :tenant-id tenant-a})
          "the service's request schema takes the tenant")
      (is (= [200 tenant-a] (create {:user admin-of-a})))
      (is (= [200 tenant-a] (create {:user admin-of-a :tenant {:id tenant-a}}))))
    (testing "outside the resolved tenant they create no one"
      (is (= [403 nil] (create {:user admin-of-a :tenant {:id tenant-b}}))))
    (testing "a platform admin creates a user of no tenant"
      (is (= [200 nil] (create {:user {:id (UUID/randomUUID) :role :admin}}))))))

(deftest ^:unit ^:security another-tenants-users-rights-are-not-found
  (is (= 404 (:status ((rights-handlers/user-rights (service (atom [])) nil)
                       {:user admin-of-a :path-params {:id (str (:id user-of-b))}})))))

(deftest ^:unit ^:security roles-and-groups-take-a-platform-admin
  ;; Roles and groups carry no tenant: a tenant's admin who edited one, or
  ;; added a role to Everyone, changed it for every tenant (BOU-984)
  (let [routes (into {} (r/routes (r/router (:web (http/group-routes nil nil {})))))
        status (fn [path user]
                 (let [guard (last (:middleware (routes path)))]
                   (:status ((guard (constantly {:status 200})) {:user user :headers {}}))))]
    (is (contains? routes "/admin/roles/save"))
    (doseq [path (keys routes)]
      (testing path
        (is (= 403 (status path admin-of-a)))
        (is (= 403 (status path no-tenant)))
        (is (= 200 (status path {:id (UUID/randomUUID) :role :admin})))))))
