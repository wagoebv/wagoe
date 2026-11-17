(ns wagoe.user.shell.user-list-access-test
  "The user list filtered on what a user can do, their groups and roles
   (BOU-1002), on H2."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [support.test-db :as test-db]
            [wagoe.platform.shell.authorization :as authz]
            [wagoe.user.core.group :as group-core]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.access-filter :as access-filter]
            [wagoe.user.shell.group-persistence :as group-persistence]
            [wagoe.user.shell.group-service :as group-service]
            [wagoe.user.shell.persistence :as persistence]
            [wagoe.user.shell.service :as user-service]
            [wagoe.user.shell.web-handlers :as web-handlers]))

(def ^:private registry
  (authz/create-registry {:wagoe/invoice {:permissions {:invoice/approve {:label "Approve invoices"}}}}))

(def ^:private tenant #uuid "a0000000-0000-0000-0000-00000000000a")
(def ^:private tenant-b #uuid "b0000000-0000-0000-0000-00000000000b")

(defn- with-app [f]
  (test-db/with-db "user_list_access"
    (fn [ctx]
      (persistence/initialize-user-schema! ctx)
      (let [users (persistence/create-user-repository ctx)
            audit (persistence/create-audit-repository ctx {:default-limit 50 :max-limit 100})
            usvc  (user-service/->UserService users nil audit {} nil)
            gsvc  (group-service/create-group-service (group-persistence/create-group-repository ctx) users audit
                                                      (group-service/prepare-settings nil)
                                                      {:registry registry :membership (fn [u] (if (= "cy@example.com" (:email u)) #{"Ops"} #{}))})
            user! (fn [email & [m]] (ports/create-user users (merge {:email email :name email :role :user :active true
                                                                     :password-hash "x"} m)))]
        (f {:usvc usvc :gsvc gsvc :user! user!})))))

(defn- found
  "What the access filters find for `filters`, outside any tenant."
  [usvc gsvc filters]
  (access-filter/find-users usvc gsvc nil filters))

(defn- page
  "The user list's body for `admin` asking `params`."
  [usvc gsvc admin params & [request]]
  (:body ((web-handlers/users-page-handler usvc {:group-service gsvc})
          (merge {:request-method :get :uri "/web/users" :user admin :headers {} :query-params params} request))))

(defn- listed
  "The emails the list shows, of `emails`."
  [body emails]
  (set (filter #(str/includes? body (str ">" % "<")) emails)))

(def ^:private everyone ["ada@example.com" "bob@example.com" "cy@example.com" "admin@example.com"])

(deftest ^:integration the-user-list-filters-on-what-users-can-do
  (with-app
    (fn [{:keys [usvc gsvc user!]}]
      (let [admin   (user! "admin@example.com" {:role :admin})
            ada     (user! "ada@example.com")
            bob     (user! "bob@example.com")
            cy      (user! "cy@example.com")
            finance (ports/create-group gsvc {:name "Finance"})
            desk    (ports/create-group gsvc {:name "Desk"})
            ops     (ports/create-group gsvc {:name "Ops"})
            ask     #(page usvc gsvc admin %)]
        (ports/add-group-role gsvc (:id finance) {:role :invoice/editor :scope {}})
        (ports/add-group-role gsvc (:id desk) {:role :invoice/viewer :scope {}})
        (ports/add-group-role gsvc (:id ops) {:role :invoice/viewer :scope {}})
        (ports/add-group-member gsvc (:id finance) (:id ada))
        (ports/add-group-member gsvc (:id desk) (:id bob))
        (testing "unfiltered: everyone, each with their groups, and the filters offered"
          (let [body (ask {})]
            (is (= (set everyone) (listed body everyone)))
            (is (str/includes? body (str "href=\"/web/admin/groups/" (:id finance) "\">Finance<")))
            (is (str/includes? body "users-permission-filter"))
            (is (str/includes? body "value=\"invoice/approve\""))
            (is (str/includes? body (str "<option value=\"" (:id desk) "\"")))
            (is (not (str/includes? body "users-access-note")))))
        (testing "a permission: who holds it through a group, a provider or as platform admin, and which"
          (let [body (ask {"permission" "invoice/write"})]
            (is (= #{"ada@example.com" "admin@example.com"} (listed body everyone)))
            (is (str/includes? body "users-access-note"))
            (is (str/includes? body "users-any-scope"))
            (is (str/includes? body "users-via-platform-admin"))
            (is (not (str/includes? body "users-via-everyone"))))
          (is (= {:everyone? false :provided #{} :platform-admins #{(str (:id admin))}}
                 (:permission (:found (found usvc gsvc {:permission "invoice/write"})))))
          (is (= {:everyone? false :provided #{(str (:id cy))} :platform-admins #{(str (:id admin))}}
                 (:permission (:found (found usvc gsvc {:permission "invoice/read"})))))
          (testing "the counts are of the users listed: another filter narrows them"
            (is (= {:everyone? false :provided #{} :platform-admins #{}}
                   (:permission (:found (found usvc gsvc {:permission "invoice/read" :group (str (:id desk))}))))))
          (is (= (set everyone) (listed (ask {"permission" "invoice/read"}) everyone)))
          (is (= #{"admin@example.com"} (listed (ask {"permission" "invoice/approve"}) everyone))))
        (testing "a group, its provider members too"
          (is (= #{"ada@example.com"} (listed (ask {"group" (str (:id finance))}) everyone)))
          (is (= #{"cy@example.com"} (listed (ask {"group" (str (:id ops))}) everyone)))
          (let [body (ask {"group" (str (random-uuid))})]
            (is (empty? (listed body everyone)) "an unknown group admits nobody")
            (is (str/includes? body "not-offered") "and the select still shows it is set")))
        (testing "a role from the Roles page"
          (is (= #{"bob@example.com" "cy@example.com"} (listed (ask {"has-role" "invoice/viewer"}) everyone))))
        (testing "together, each narrows the others, and the URL keeps them"
          (let [body (ask {"permission" "invoice/read" "group" (str (:id desk))})]
            (is (= #{"bob@example.com"} (listed body everyone)))
            (is (str/includes? body "permission=invoice%2Fread"))
            (is (str/includes? body (str "group=" (:id desk)))))
          (let [body (ask {"has-role" "invoice/viewer" "group" (str (:id ops))})]
            (is (= #{"cy@example.com"} (listed body everyone)))
            (is (str/includes? body "has-role=invoice%2Fviewer")))
          (is (= #{"bob@example.com"} (listed (ask {"permission" "invoice/read" "group" (str (:id desk))
                                                    "has-role" "invoice/viewer"})
                                              everyone))
              "all three"))
        (testing "a bulk action answers with the list as filtered"
          (let [r ((web-handlers/bulk-update-users-htmx-handler usvc {:group-service gsvc})
                   {:request-method :post :uri "/web/users/bulk" :user admin :headers {} :query-params {}
                    :form-params {"ids[]" (str (:id bob)) "action" "activate" "group" (str (:id desk))}})]
            (is (= #{"bob@example.com"} (listed (:body r) everyone)))))
        (testing "Everyone gives it: every user, and the list says so"
          (ports/ensure-everyone (:group-repository gsvc))
          (ports/add-group-role gsvc group-core/everyone-id {:role :invoice/viewer :scope {}})
          (let [body (ask {"permission" "invoice/read"})]
            (is (= (set everyone) (listed body everyone)))
            (is (str/includes? body "users-via-everyone"))
            (is (not (str/includes? body "users-via-provider"))))
          (is (= (set everyone) (listed (ask {"group" (str group-core/everyone-id)}) everyone))
              "Everyone as the group: every user"))
        (testing "the table fragment filters too"
          (is (= #{"ada@example.com"}
                 (listed (page usvc gsvc admin {"group" (str (:id finance))}
                               {:headers {"hx-request" "true" "hx-target" "users-table-container"}})
                         everyone))))))))

(deftest ^:integration ^:security a-tenants-admin-filters-only-their-tenants-users-and-groups
  (with-app
    (fn [{:keys [usvc gsvc user!]}]
      (let [boss  (user! "boss@a.test" {:role :admin :tenant-id tenant})
            dan   (user! "dan@a.test" {:tenant-id tenant})
            ada   (user! "ada@example.com")
            mine  (group-service/with-tenant tenant #(ports/create-group gsvc {:name "Finance"}))
            other (ports/create-group gsvc {:name "Finance"})
            ask   #(page usvc gsvc boss % {:tenant {:id tenant}})]
        (group-service/with-tenant tenant
          #(do (ports/add-group-role gsvc (:id mine) {:role :invoice/editor :scope {}})
               (ports/add-group-member gsvc (:id mine) (:id dan))))
        (ports/add-group-role gsvc (:id other) {:role :invoice/editor :scope {}})
        (ports/add-group-member gsvc (:id other) (:id ada))
        (let [emails ["dan@a.test" "ada@example.com" "boss@a.test"]]
          (is (= #{"dan@a.test"} (listed (ask {"permission" "invoice/write"}) emails))
              "a tenant's admin is no platform admin: they hold only what their groups give")
          (is (empty? (listed (ask {"permission" "invoice/approve"}) emails)))
          (is (not (str/includes? (ask {"permission" "invoice/write"}) "users-via-platform-admin"))
              "a tenant lists no platform admin, so none is counted")
          (is (= #{"dan@a.test"} (listed (ask {"has-role" "invoice/editor"}) emails)))
          (testing "another tenant's group is not offered and admits nobody"
            (let [body (ask {"group" (str (:id other))})]
              (is (empty? (listed body emails)))
              (is (not (str/includes? body (str "<option value=\"" (:id other) "\""))))
              (is (str/includes? body (str "<option value=\"" (:id mine) "\""))))))))))

(deftest ^:integration a-platform-admin-outside-a-tenant-filters-users-of-no-tenant
  (with-app
    (fn [{:keys [usvc gsvc user!]}]
      (let [root (user! "root@example.com" {:role :admin})
            dan  (user! "dan@a.test" {:tenant-id tenant})
            mine (group-service/with-tenant tenant #(ports/create-group gsvc {:name "Finance"}))]
        (group-service/with-tenant tenant
          #(do (ports/add-group-role gsvc (:id mine) {:role :invoice/editor :scope {}})
               (ports/add-group-member gsvc (:id mine) (:id dan))))
        (is (= #{"dan@a.test"} (listed (page usvc gsvc root {}) ["dan@a.test"])) "unfiltered, every tenant")
        (let [body (page usvc gsvc root {"permission" "invoice/write"})]
          (is (empty? (listed body ["dan@a.test"])))
          (is (str/includes? body "users-no-tenant-note")))
        (testing "a tenant user's groups show, but link only in their tenant"
          (let [body (page usvc gsvc root {})]
            (is (str/includes? body ">Finance<"))
            (is (not (str/includes? body (str "/web/admin/groups/" (:id mine)))))))))))

(deftest ^:integration ^:security an-admin-with-no-reach-here-gets-no-filters
  ;; An admin of tenant B who is a plain member of tenant A, on A's host
  (with-app
    (fn [{:keys [usvc gsvc user!]}]
      (let [stranger (user! "stranger@b.test" {:role :admin :tenant-id tenant-b})
            _        (ports/create-group gsvc {:name "Platform staff"})
            body     (page usvc gsvc stranger {"permission" "invoice/read"}
                           {:tenant {:id tenant} :tenant-membership {:role :member}})]
        (is (not (str/includes? body "users-permission-filter")))
        (is (not (str/includes? body "Platform staff")))
        (is (not (str/includes? body "users-access-note")))))))

(deftest ^:unit filters-are-offered-where-groups-are-kept-per-tenant
  (let [tenant-admin {:role :admin :tenant-id tenant}]
    (is (not (access-filter/offered? {:group-repository {}} tenant-admin)) "an older repository keeps one set")
    (is (access-filter/offered? {:group-repository {}} {:role :admin}) "a platform admin's, as the group pages")))

(deftest ^:unit platform-admins-are-found-past-the-first-page
  (let [admin  (fn [i t] {:id (random-uuid) :email (str "a" i "@x.test") :role :admin :tenant-id t})
        pages  [(mapv #(admin % tenant) (range 100)) [(admin 100 nil)]]
        asked  (atom [])
        svc    #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify ports/IUserService
          (list-users [_ opts]
            (swap! asked conj opts)
            {:users (get pages (quot (:offset opts) 100) [])}))]
    (is (= ["a100@x.test"] (map :email (access-filter/platform-admins svc))))
    (is (every? #(and (contains? % :filter-tenant-id) (nil? (:filter-tenant-id %))) @asked))))
