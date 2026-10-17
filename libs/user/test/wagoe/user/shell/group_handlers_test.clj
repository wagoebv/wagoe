(ns wagoe.user.shell.group-handlers-test
  "The admin's group pages, through their routes, on H2: create a group, add
   a member and a grant, see them, remove them. Admins only."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reitit.ring :as ring]
            [support.test-db :as test-db]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.group-persistence :as group-persistence]
            [wagoe.user.shell.group-service :as group-service]
            [wagoe.user.shell.http :as http]
            [wagoe.user.shell.persistence :as persistence]
            [wagoe.user.shell.service :as user-service]))

(defn- with-app [settings f]
  (test-db/with-db "user_group_pages"
    (fn [ctx]
      (persistence/initialize-user-schema! ctx)
      (let [users  (persistence/create-user-repository ctx)
            audit  (persistence/create-audit-repository ctx {:default-limit 50 :max-limit 100})
            usvc   (user-service/->UserService users nil audit {} nil)
            gsvc   (group-service/create-group-service (group-persistence/create-group-repository ctx)
                                                       users audit (group-service/prepare-settings settings))
            routes (http/group-routes usvc gsvc nil)
            app    (ring/ring-handler (ring/router [(:web-prefix routes) (:web routes)]))
            admin  (ports/create-user users {:email "admin@example.com" :name "Admin" :role :admin
                                             :active true :password-hash "x"})]
        (f {:app app :users users :audit audit :gsvc gsvc :admin admin})))))

(defn- req
  ([user method uri] (req user method uri {}))
  ([user method uri form]
   {:request-method method :uri uri :user user :form-params form :headers {}}))

(deftest ^:integration an-admin-manages-a-group
  (with-app {:permissions [:enum :invoice/approve :report/view]}
    (fn [{:keys [app users audit gsvc admin]}]
      (let [ada (ports/create-user users {:email "ada@example.com" :name "Ada" :role :user
                                          :active true :password-hash "x"})
            created (app (req admin :post "/web/admin/groups" {"name" "Finance" "description" "Pays"}))
            path    (get-in created [:headers "Location"])
            gid     (parse-uuid (last (str/split path #"/")))]
        (is (= 303 (:status created)))
        (is (str/includes? (:body (app (req admin :get "/web/admin/groups"))) "Finance"))

        (testing "a member, by email"
          (is (= 303 (:status (app (req admin :post (str path "/members") {"email" "ada@example.com"}))))))
        (testing "an unknown email is refused on the page"
          (let [r (app (req admin :post (str path "/members") {"email" "nobody@example.com"}))]
            (is (= 422 (:status r)))))

        (testing "a grant, its permission picked from the declared set"
          (let [page (:body (app (req admin :get path)))]
            (is (str/includes? page "<option value=\"invoice/approve\">")))
          (is (= 303 (:status (app (req admin :post (str path "/grants")
                                        {"permission" "invoice/approve" "scope" "{:domain-id 7}"}))))))
        (testing "refused grants: undeclared, not EDN, not a map"
          (doseq [form [{"permission" "invoice/delete" "scope" ""}
                        {"permission" "report/view" "scope" "{:domain-id"}
                        {"permission" "report/view" "scope" "[1 2]"}]]
            (is (= 422 (:status (app (req admin :post (str path "/grants") form)))) (pr-str form))))

        (let [page (:body (app (req admin :get path)))]
          (is (str/includes? page "ada@example.com"))
          (is (str/includes? page "{:domain-id 7}")))
        (is (= [:invoice/approve] (map :permission (ports/user-grants gsvc ada))))

        (testing "the signed-in admin is the actor"
          (is (= #{(:id admin)}
                 (set (map :actor-id (ports/find-audit-logs-by-user audit (:id ada) {:limit 50}))))))

        (testing "renaming, removing the grant and the member, deleting the group"
          (is (= 303 (:status (app (req admin :post path {"name" "Treasury" "description" ""})))))
          (is (= "Treasury" (:name (ports/get-group gsvc gid))))
          (let [grant (first (:grants (ports/get-group gsvc gid)))]
            (is (= 303 (:status (app (req admin :post (str path "/grants/" (:id grant) "/remove")))))))
          (is (= 303 (:status (app (req admin :post (str path "/members/" (:id ada) "/remove"))))))
          (is (empty? (ports/user-grants gsvc ada)))
          (is (= 303 (:status (app (req admin :post (str path "/delete"))))))
          (is (nil? (ports/get-group gsvc gid)))
          (is (= 404 (:status (app (req admin :get path))))))))))

(deftest ^:integration ^:security only-admins-reach-the-pages
  (with-app nil
    (fn [{:keys [app users gsvc]}]
      (let [user (ports/create-user users {:email "u@example.com" :name "U" :role :user
                                           :active true :password-hash "x"})]
        ;; A refused /web request is sent to the login page.
        (doseq [[method uri] [[:get "/web/admin/groups"] [:post "/web/admin/groups"]]]
          (let [r (app (req user method uri {"name" "X"}))]
            (is (= 302 (:status r)) (str method " " uri))
            (is (str/starts-with? (get-in r [:headers "Location"]) "/web/login") (str method " " uri))))
        (is (empty? (ports/list-groups gsvc)))))))

(deftest ^:unit the-web-ui-switch-turns-the-group-pages-off-too
  (let [usvc (reify ports/IUserService)
        off  {:active {:wagoe/settings {:features {:user-web-ui {:enabled? false}}}}}]
    (testing "on unless switched off"
      (is (seq (:web (http/group-routes usvc nil nil))))
      (is (seq (:web (http/user-routes usvc nil nil)))))
    (testing "off: no web routes from either"
      (is (empty? (:web (http/group-routes usvc nil off))))
      (is (empty? (:web (http/user-routes usvc nil off))))
      (is (seq (:api (http/user-routes usvc nil off)))))))
