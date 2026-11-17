(ns wagoe.user.shell.user-list-access-test
  "The user list filtered on what a user can do, their groups and roles
   (BOU-1002), on H2."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [support.test-db :as test-db]
            [wagoe.platform.shell.authorization :as authz]
            [wagoe.user.core.group :as group-core]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.group-persistence :as group-persistence]
            [wagoe.user.shell.group-service :as group-service]
            [wagoe.user.shell.persistence :as persistence]
            [wagoe.user.shell.service :as user-service]
            [wagoe.user.shell.web-handlers :as web-handlers]))

(def ^:private registry
  (authz/create-registry {:wagoe/invoice {:permissions {:invoice/approve {:label "Approve invoices"}}}}))

(def ^:private tenant #uuid "a0000000-0000-0000-0000-00000000000a")

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
            _cy     (user! "cy@example.com")
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
            (is (not (str/includes? body "users-permission-note")))))
        (testing "a permission: who holds it through a group, a provider or as platform admin, and which"
          (let [body (ask {"permission" "invoice/write"})]
            (is (= #{"ada@example.com" "admin@example.com"} (listed body everyone)))
            (is (str/includes? body "users-permission-note"))
            (is (str/includes? body "via-platform-admin")))
          (is (= (set everyone) (listed (ask {"permission" "invoice/read"}) everyone)))
          (is (= #{"admin@example.com"} (listed (ask {"permission" "invoice/approve"}) everyone))))
        (testing "a group, its provider members too"
          (is (= #{"ada@example.com"} (listed (ask {"group" (str (:id finance))}) everyone)))
          (is (= #{"cy@example.com"} (listed (ask {"group" (str (:id ops))}) everyone)))
          (is (empty? (listed (ask {"group" (str (random-uuid))}) everyone)) "an unknown group admits nobody"))
        (testing "a role from the Roles page"
          (is (= #{"bob@example.com" "cy@example.com"} (listed (ask {"has-role" "invoice/viewer"}) everyone))))
        (testing "together, each narrows the others, and the URL keeps them"
          (let [body (ask {"permission" "invoice/read" "group" (str (:id desk))})]
            (is (= #{"bob@example.com"} (listed body everyone)))
            (is (str/includes? body "permission=invoice%2Fread"))
            (is (str/includes? body (str "group=" (:id desk))))))
        (testing "Everyone gives it: every user, and the list says so"
          (ports/ensure-everyone (:group-repository gsvc))
          (ports/add-group-role gsvc group-core/everyone-id {:role :invoice/viewer :scope {}})
          (let [body (ask {"permission" "invoice/read"})]
            (is (= (set everyone) (listed body everyone)))
            (is (str/includes? body "via-everyone"))))
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
        (let [emails ["dan@a.test" "ada@example.com"]]
          (is (= #{"dan@a.test"} (listed (ask {"permission" "invoice/write"}) emails)))
          (is (= #{"dan@a.test"} (listed (ask {"has-role" "invoice/editor"}) emails)))
          (testing "another tenant's group is not offered and admits nobody"
            (let [body (ask {"group" (str (:id other))})]
              (is (empty? (listed body emails)))
              (is (not (str/includes? body (str "<option value=\"" (:id other) "\""))))
              (is (str/includes? body (str "<option value=\"" (:id mine) "\""))))))))))
