(ns wagoe.user.shell.group-handlers-test
  "The admin's group pages, through their routes, on H2: create a group, add
   a member and a role, see them, remove them. Admins only."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reitit.ring :as ring]
            [support.test-db :as test-db]
            [wagoe.platform.shell.authorization :as authz]
            [wagoe.user.core.group :as group]
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
                                                       users audit (group-service/prepare-settings settings)
                                                       {:registry (authz/create-registry {:wagoe/invoice nil})})
            routes (http/group-routes usvc gsvc nil)
            app    (ring/ring-handler (ring/router ["/web" (:web routes)]))
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

        (testing "a name another group has is refused on the page"
          (let [r (app (req admin :post "/web/admin/groups" {"name" "Finance"}))]
            (is (= 422 (:status r)))
            (is (str/includes? (:body r) "error-name-taken"))))

        (testing "a member, by email"
          (is (= 303 (:status (app (req admin :post (str path "/members") {"email" "ada@example.com"}))))))
        (testing "an unknown email is refused on the page"
          (let [r (app (req admin :post (str path "/members") {"email" "nobody@example.com"}))]
            (is (= 422 (:status r)))))

        (testing "a role, picked from the code and managed ones"
          (let [page (:body (app (req admin :get path)))]
            (is (str/includes? page "value=\"invoice/editor\"")))
          (is (= 303 (:status (app (req admin :post (str path "/roles")
                                        {"role" "invoice/editor" "scope" "{:domain-id 7}"}))))))
        (testing "refused: no such role, not EDN, not a map"
          (doseq [form [{"role" "invoice/boss" "scope" ""}
                        {"role" "invoice/viewer" "scope" "{:domain-id"}
                        {"role" "invoice/viewer" "scope" "[1 2]"}]]
            (is (= 422 (:status (app (req admin :post (str path "/roles") form)))) (pr-str form))))

        (let [page (:body (app (req admin :get path)))]
          (is (str/includes? page "ada@example.com"))
          (is (str/includes? page "domain-id: <strong class=\"scope-value\">7</strong>")))
        (is (= #{:invoice/read :invoice/write} (set (map :permission (ports/user-grants gsvc ada)))))

        (testing "the signed-in admin is the actor"
          (is (= #{(:id admin)}
                 (set (map :actor-id (ports/find-audit-logs-by-user audit (:id ada) {:limit 50}))))))

        (testing "renaming, removing the role and the member, deleting the group"
          (is (= 303 (:status (app (req admin :post path {"name" "Treasury" "description" ""})))))
          (is (= "Treasury" (:name (ports/get-group gsvc gid))))
          (let [a (first (:roles (ports/get-group gsvc gid)))]
            (is (= 303 (:status (app (req admin :post (str path "/roles/" (:id a) "/remove")))))))
          (is (= 303 (:status (app (req admin :post (str path "/members/" (:id ada) "/remove"))))))
          (is (empty? (ports/user-grants gsvc ada)))
          (is (= 303 (:status (app (req admin :post (str path "/delete"))))))
          (is (nil? (ports/get-group gsvc gid)))
          (is (= 404 (:status (app (req admin :get path))))))))))

(deftest ^:integration a-member-is-added-through-a-user-lookup
  (with-app nil
    (fn [{:keys [app users gsvc admin]}]
      (let [ada     (ports/create-user users {:email "ada@example.com" :name "Ada Lovelace" :role :user
                                              :active true :password-hash "x"})
            _       (ports/create-user users {:email "grace@example.com" :name "Grace" :role :user
                                              :active true :password-hash "x"})
            path    (get-in (app (req admin :post "/web/admin/groups" {"name" "Finance"})) [:headers "Location"])
            options #(:body (app (assoc (req admin :get (str path "/members/options")) :query-params {"q" %})))]
        (testing "the page offers a lookup, not an email field"
          (let [page (:body (app (req admin :get path)))]
            (is (str/includes? page (str "hx-get=\"" path "/members/options\"")))
            (is (not (str/includes? page "type=\"email\"")))))
        (testing "the lookup matches name or email"
          (is (str/includes? (options "lovelace") (str "data-value=\"" (:id ada) "\"")))
          (is (str/includes? (options "lovelace") "Ada Lovelace &lt;ada@example.com&gt;"))
          (is (not (str/includes? (options "lovelace") "grace@example.com"))))
        (testing "the picked user's id adds them, and a member is no longer offered"
          (is (= 303 (:status (app (req admin :post (str path "/members") {"user-id" (str (:id ada))}))))))
        (is (= [(:id ada)] (map :id (:members (ports/get-group gsvc (parse-uuid (last (str/split path #"/"))))))))
        (is (not (str/includes? (options "ada") (str (:id ada)))))
        (testing "an id that names nobody is refused on the page"
          (is (= 422 (:status (app (req admin :post (str path "/members") {"user-id" (str (random-uuid))}))))))))))

(deftest ^:integration a-scope-key-pointing-at-an-entity-is-a-lookup
  (with-app {:scope [:map [:domain-id {:optional true :wagoe/entity :domains} :int]
                     [:region {:optional true} :string]]}
    (fn [{:keys [app gsvc admin]}]
      (let [path (get-in (app (req admin :post "/web/admin/groups" {"name" "Finance"})) [:headers "Location"])
            gid  (parse-uuid (last (str/split path #"/")))
            page (:body (app (req admin :get path)))]
        (testing "the key pointing at an entity reads its options from the entity's admin lookup; the other is typed"
          (is (str/includes? page "hx-get=\"/web/admin/domains/lookup?field=scope-domain-id\""))
          (is (str/includes? page "name=\"scope-domain-id\""))
          (is (str/includes? page "name=\"scope-region\""))
          (is (str/includes? page "name=\"scope\"") "and EDN, for a value a field cannot hold"))
        (testing "each value is read as the schema says"
          (is (= 303 (:status (app (req admin :post (str path "/roles")
                                        {"role" "invoice/viewer" "scope-region" "north"
                                         "scope-domain-id" "42"})))))
          (is (= [{:domain-id 42 :region "north"}] (map :scope (:roles (ports/get-group gsvc gid))))))
        (testing "a refused assignment comes back with what was entered"
          (let [r (app (req admin :post (str path "/roles")
                            {"role" "invoice/viewer" "scope-region" "south" "scope-domain-id" "forty"
                             "scope-label-domain-id" "Domain Forty"}))]
            (is (= 422 (:status r)))
            (is (str/includes? (:body r) "value=\"south\""))
            (is (str/includes? (:body r) "value=\"forty\""))
            (is (str/includes? (:body r) "value=\"Domain Forty\"") "the lookup shows the title, not the id")
            (is (str/includes? (:body r) "selected=\"selected\" title=\"Reads invoice\" value=\"invoice/viewer\"")
                "the role picked")))
        (testing "EDN typed beside the fields"
          (is (= 303 (:status (app (req admin :post (str path "/roles")
                                        {"role" "invoice/editor" "scope" "{:region \"west\"}"})))))
          (is (some #{{:region "west"}} (map :scope (:roles (ports/get-group gsvc gid))))))))))

(deftest ^:integration ^:security only-admins-reach-the-pages
  (with-app nil
    (fn [{:keys [app users gsvc]}]
      (let [user (ports/create-user users {:email "u@example.com" :name "U" :role :user
                                           :active true :password-hash "x"})]
        ;; A refused /web request is sent to the login page.
        (doseq [[method uri] [[:get "/web/admin/groups"] [:post "/web/admin/groups"]
                              [:get (str "/web/admin/groups/" (random-uuid) "/members/options")]]]
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

(deftest ^:integration everyone-shows-its-roles-not-its-members
  (with-app nil
    (fn [{:keys [app gsvc admin]}]
      (group-service/migrate! gsvc)
      (let [page (:body (app (req admin :get (str "/web/admin/groups/" group/everyone-id))))]
        (is (str/includes? page "everyone-note"))
        (is (not (str/includes? page "group-member-form")))
        (is (str/includes? page "group-role-form"))))))

(deftest ^:integration provider-members-are-shown-when-asked
  (with-app nil
    (fn [{:keys [app admin]}]
      (let [path (get-in (app (req admin :post "/web/admin/groups" {"name" "Newsroom"})) [:headers "Location"])]
        (is (str/includes? (:body (app (req admin :get path))) (str "hx-get=\"" path "/provider-members\"")))
        (is (str/includes? (:body (app (req admin :get (str path "/provider-members")))) "provider-members-empty"))
        (is (= 404 (:status (app (req admin :get (str "/web/admin/groups/" (random-uuid) "/provider-members"))))))))))
