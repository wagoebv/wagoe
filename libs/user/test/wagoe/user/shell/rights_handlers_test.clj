(ns wagoe.user.shell.rights-handlers-test
  "The admin's role, permission and rights pages, through their routes, on H2."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reitit.ring :as ring]
            [support.test-db :as test-db]
            [wagoe.platform.shell.authorization :as authz]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.group-persistence :as group-persistence]
            [wagoe.user.shell.group-service :as group-service]
            [wagoe.user.shell.http :as http]
            [wagoe.user.shell.persistence :as persistence]
            [wagoe.user.shell.rights-handlers :as rights-handlers]
            [wagoe.user.shell.service :as user-service]))

(def ^:private registry
  (authz/create-registry
   {:wagoe/invoice {:permissions {:invoice/approve {:label "Approve invoices"}}}
    :wagoe/app     {:scopes        {:department-id {:label "Department" :sources #{:user/department}
                                                    ;; A department and the one beneath it
                                                    :expand (fn [ids] (into ids (map #(* 10 %)) ids))}
                                    :domain-id     {:label "Domain" :schema :int}}
                    :scope-sources {:user/department {:label "department" :resolve (constantly #{7})}}}}))

(defn- with-app [f]
  (test-db/with-db "user_rights_pages"
    (fn [ctx]
      (persistence/initialize-user-schema! ctx)
      (let [users  (persistence/create-user-repository ctx)
            audit  (persistence/create-audit-repository ctx {:default-limit 50 :max-limit 100})
            usvc   (user-service/->UserService users nil audit {} nil)
            gsvc   (group-service/create-group-service (group-persistence/create-group-repository ctx)
                                                       users audit
                                                       (group-service/with-registry-scopes
                                                         (group-service/prepare-settings nil) registry)
                                                       {:registry registry
                                                        :explain  (partial authz/explain registry)
                                                        :resolve  (partial authz/resolve-rights registry)})
            routes (http/group-routes usvc gsvc nil)
            app    (ring/ring-handler (ring/router ["/web" (:web routes)]))
            admin  (ports/create-user users {:email "admin@example.com" :name "Admin" :role :admin
                                             :active true :password-hash "x"})]
        (f {:app app :users users :usvc usvc :gsvc gsvc :admin admin :audit audit})))))

(defn- req
  ([user method uri] (req user method uri {}))
  ([user method uri form]
   {:request-method method :uri uri :user user :form-params form :headers {}
    :query-params (some-> (second (str/split uri #"\?")) (str/split #"=") ((fn [[k v]] {k (java.net.URLDecoder/decode v "UTF-8")})))}))

(defn- path-of [uri] (first (str/split uri #"\?")))

(defn- get-page [app user uri]
  (app (update (req user :get uri) :uri path-of)))

(deftest ^:integration an-admin-composes-duplicates-and-deletes-roles
  (with-app
    (fn [{:keys [app gsvc admin]}]
      (testing "code roles are listed locked"
        (let [page (:body (get-page app admin "/web/admin/roles"))]
          (is (str/includes? page "invoice/editor"))
          (is (str/includes? page "role-locked"))
          (is (str/includes? page "value=\"invoice/approve\"") "the registry's permissions are offered")))

      (testing "a managed role from ticked permissions"
        (let [r (app (req admin :post "/web/admin/roles" {"name" "invoice/clerk" "description" "Enters"
                                                          "permissions" ["invoice/read" "invoice/write"]}))]
          (is (= 303 (:status r)))
          (is (= "/web/admin/roles/view?name=invoice%2Fclerk" (get-in r [:headers "Location"]))))
        (is (= #{:invoice/read :invoice/write}
               (:permissions (some #(when (= :invoice/clerk (:name %)) %) (ports/list-roles gsvc))))))

      (testing "refused on the page: a code role's name, no permission, one nobody declares"
        (doseq [[form text] [[{"name" "invoice/editor" "permissions" "invoice/read"} "error-name-code"]
                             [{"name" "invoice/empty"} "error-invalid"]
                             [{"name" "invoice/odd" "permissions" "invoice/fly"} "error-permissions"]]]
          (let [r (app (req admin :post "/web/admin/roles" form))]
            (is (= 422 (:status r)) (pr-str form))
            (is (str/includes? (:body r) text) (pr-str form)))))

      (testing "a code role is duplicated into a managed one, then changed"
        (let [r (app (req admin :post "/web/admin/roles/duplicate" {"name" "invoice/viewer" "new-name" "invoice/reader"}))]
          (is (= "/web/admin/roles/view?name=invoice%2Freader" (get-in r [:headers "Location"]))))
        (is (= 303 (:status (app (req admin :post "/web/admin/roles/save"
                                      {"name" "invoice/reader" "permissions" ["invoice/read" "invoice/approve"] "confirm" "true"})))))
        (is (= #{:invoice/read :invoice/approve}
               (:permissions (some #(when (= :invoice/reader (:name %)) %) (ports/list-roles gsvc))))))

      (testing "a code role is not saved or deleted"
        (is (= 422 (:status (app (req admin :post "/web/admin/roles/save"
                                      {"name" "invoice/viewer" "permissions" "invoice/read"})))))
        (is (= 404 (:status (app (req admin :post "/web/admin/roles/delete" {"name" "invoice/viewer" "confirm" "true"}))))))

      (testing "a managed role is deleted"
        (is (= 303 (:status (app (req admin :post "/web/admin/roles/delete" {"name" "invoice/reader" "confirm" "true"})))))
        (is (= 404 (:status (get-page app admin "/web/admin/roles/view?name=invoice/reader"))))))))

(deftest ^:integration a-role-and-a-permission-show-who-holds-them
  (with-app
    (fn [{:keys [app users gsvc admin]}]
      (let [ada (ports/create-user users {:email "ada@example.com" :name "Ada" :role :user
                                          :active true :password-hash "x"})
            g   (ports/create-group gsvc {:name "Finance"})]
        (ports/add-group-member gsvc (:id g) (:id ada))
        (ports/add-group-role gsvc (:id g) {:role :invoice/editor :scope {:domain-id 4}})
        (doseq [uri ["/web/admin/roles/view?name=invoice/editor" "/web/admin/permissions/view?name=invoice/write"]]
          (let [page (:body (get-page app admin uri))]
            (is (str/includes? page "Finance") uri)
            (is (str/includes? page "ada@example.com") uri)
            (is (str/includes? page "Domain: <strong class=\"scope-value\">4</strong>") uri)))
        (is (str/includes? (:body (get-page app admin "/web/admin/permissions")) "invoice/approve"))
        (is (= 404 (:status (get-page app admin "/web/admin/permissions/view?name=invoice/fly"))))))))

(deftest ^:integration the-rights-tab-shows-where-each-right-comes-from
  (with-app
    (fn [{:keys [app users gsvc admin]}]
      (let [ada (ports/create-user users {:email "ada@example.com" :name "Ada" :role :user
                                          :active true :password-hash "x"})
            g   (ports/create-group gsvc {:name "Finance"})]
        (ports/add-group-member gsvc (:id g) (:id ada))
        (ports/add-group-role gsvc (:id g) {:role :invoice/viewer :scope {:department-id :user/department}})
        (let [page (:body (get-page app admin (str "/web/users/" (:id ada) "/rights")))]
          (is (str/includes? page "Finance"))
          (is (str/includes? page "invoice/read"))
          (is (str/includes? page "invoice/viewer"))
          (testing "a scope from the user shows its source and resolved value, expanded as enforcement does"
            (is (str/includes? page "Department: "))
            (is (str/includes? page "scope-from-source"))
            (is (str/includes? page "<strong class=\"scope-value\">7, 70</strong>")))
          (is (not (str/includes? page "platform-admin-note"))))
        (is (str/includes? (:body (get-page app admin (str "/web/users/" (:id admin) "/rights")))
                           "platform-admin-note"))
        (is (= 404 (:status (get-page app admin (str "/web/users/" (random-uuid) "/rights")))))))))

(deftest ^:integration the-rights-page-asks-the-membership-hook-once
  (with-app
    (fn [{:keys [users usvc gsvc admin]}]
      (let [ada     (ports/create-user users {:email "ada@example.com" :name "Ada" :role :user
                                              :active true :password-hash "x"})
            _       (ports/create-group gsvc {:name "Finance"})
            calls   (atom 0)
            handler (rights-handlers/user-rights usvc (assoc gsvc :membership (fn [_] (swap! calls inc) #{"Finance"})))
            page    (handler (assoc (req admin :get "/") :path-params {:id (str (:id ada))}))]
        (is (= 200 (:status page)))
        (is (str/includes? (:body page) "Finance") "the hook's group")
        (is (= 1 @calls))))))

(defn- explain [handler admin user params]
  (handler {:request-method :get :uri "/" :user admin :headers {}
            :path-params {:id (str (:id user))} :query-params params}))

(deftest ^:integration explain-says-why-a-user-may-or-may-not
  (with-app
    (fn [{:keys [users usvc gsvc admin]}]
      (let [ada     (ports/create-user users {:email "ada@example.com" :name "Ada" :role :user
                                              :active true :password-hash "x"})
            finance (ports/create-group gsvc {:name "Finance"})
            ops     (ports/create-group gsvc {:name "Ops"})
            ;; Ops comes from the identity provider
            gsvc    (assoc gsvc :membership (fn [_] #{"Ops"}))
            handler (rights-handlers/explain-access usvc gsvc)
            page    #(:body (explain handler admin ada %))]
        (ports/add-group-member gsvc (:id finance) (:id ada))
        (ports/add-group-role gsvc (:id finance) {:role :invoice/editor :scope {:domain-id 4}})
        (ports/add-group-role gsvc (:id ops) {:role :invoice/viewer :scope {:department-id :user/department}})
        (testing "the port answers with the path, as enforcement decides"
          (let [r (ports/explain-access gsvc ada :invoice/write {:domain-id "4"})]
            (is (:allowed? r))
            (is (= [["Finance" :member :invoice/editor]] (map (juxt :group-name :membership :role) (:paths r))))))
        (testing "yes on a record: the group, how the user is in it, the role"
          (let [body (page {"permission" "invoice/write" "scope.domain-id" "4"})]
            (is (str/includes? body "yes-record"))
            (is (str/includes? body "Finance"))
            (is (str/includes? body "via-member"))))
        (testing "no on another record: the scope that misses, and what would give it"
          (let [body (page {"permission" "invoice/write" "scope.domain-id" "5"})]
            (is (str/includes? body "no-record"))
            (is (str/includes? body "key-outside"))
            (is (str/includes? body "explain-alternative"))))
        (testing "everywhere, as a route without a scope asks: a scoped right does not do"
          (let [body (page {"permission" "invoice/write" "everywhere" "true"})]
            (is (str/includes? body "no-everywhere"))
            (is (str/includes? body "not-everywhere"))))
        (testing "an identity provider group, with where to change it, and the resolved source"
          (let [body (page {"permission" "invoice/read"})]
            (is (str/includes? body "yes-anywhere"))
            (is (str/includes? body "via-provider"))
            (is (str/includes? body "<strong class=\"scope-value\">7, 70</strong>"))))
        (testing "nothing gives it"
          (let [body (page {"permission" "invoice/approve"})]
            (is (str/includes? body ">no</div>"))
            (is (str/includes? body "no-alternatives"))))
        (testing "a platform admin is named"
          (is (str/includes? (:body (explain handler admin admin {"permission" "invoice/approve"}))
                             "yes-platform-admin")))
        (testing "without a permission, the form"
          (let [body (page {})]
            (is (str/includes? body "explain-form"))
            (is (not (str/includes? body "explain-verdict")))))))))

(deftest ^:integration ^:security only-admins-reach-the-rights-pages
  (with-app
    (fn [{:keys [app users]}]
      (let [user (ports/create-user users {:email "u@example.com" :name "U" :role :user
                                           :active true :password-hash "x"})]
        (doseq [[method uri] [[:get "/web/admin/roles"] [:post "/web/admin/roles"]
                              [:post "/web/admin/roles/duplicate"] [:get "/web/admin/permissions"]
                              [:get (str "/web/users/" (:id user) "/rights")]
                              [:get (str "/web/users/" (:id user) "/explain")]]]
          (let [r (app (req user method uri {"name" "x/y"}))]
            (is (= 403 (:status r)) (str method " " uri))
            (is (nil? (get-in r [:headers "Location"])) (str method " " uri))))))))

(deftest ^:integration an-assignment-takes-a-scope-value-from-the-member
  (with-app
    (fn [{:keys [app gsvc admin]}]
      (let [path (get-in (app (req admin :post "/web/admin/groups" {"name" "Desks"})) [:headers "Location"])
            gid  (parse-uuid (last (str/split path #"/")))
            page (:body (get-page app admin path))]
        (testing "each declared key is a field; a key taking a source offers it"
          (is (str/includes? page "name=\"scope-domain-id\""))
          (is (str/includes? page "name=\"scope-source-department-id\""))
          (is (str/includes? page "value=\"user/department\""))
          (is (not (str/includes? page "name=\"scope-source-domain-id\""))))
        (is (= 303 (:status (app (req admin :post (str path "/roles")
                                      {"role" "invoice/viewer" "scope-domain-id" "4"
                                       "scope-source-department-id" "user/department" "confirm" "true"})))))
        (is (= [{:domain-id 4 :department-id :user/department}] (map :scope (:roles (ports/get-group gsvc gid)))))
        (testing "a source the key does not take is refused"
          (is (= 422 (:status (app (req admin :post (str path "/roles")
                                        {"role" "invoice/viewer" "scope-source-department-id" "user/boss"}))))))))))

(deftest ^:integration a-change-shows-its-effect-on-rights-before-it-is-made
  (with-app
    (fn [{:keys [app users gsvc admin audit]}]
      (let [user! #(ports/create-user users {:email % :name "U" :role :user :active true :password-hash "x"})
            ada   (user! "ada@example.com")
            bob   (user! "bob@example.com")
            path  (get-in (app (req admin :post "/web/admin/groups" {"name" "Desks"})) [:headers "Location"])
            gid   (parse-uuid (last (str/split path #"/")))
            a     (ports/add-group-role gsvc gid {:role :invoice/viewer :scope {:department-id :user/department}})]
        (ports/add-group-member gsvc gid (:id bob))
        (testing "adding a member shows their gain, resolved as enforcement resolves it, and changes nothing"
          (let [r (app (req admin :post (str path "/members") {"user-id" (str (:id ada))}))]
            (is (= 200 (:status r)))
            (is (str/includes? (:body r) "preview-added"))
            (is (str/includes? (:body r) "<strong class=\"scope-value\">7, 70</strong>"))
            (is (str/includes? (:body r) (str "name=\"user-id\" type=\"hidden\" value=\"" (:id ada) "\"")))
            (is (str/includes? (:body r) "name=\"confirm\" type=\"hidden\" value=\"true\""))
            (is (empty? (ports/user-grants gsvc ada)))))
        (testing "confirmed, it is made, and its audit entry keeps the diff"
          (is (= 303 (:status (app (req admin :post (str path "/members") {"user-id" (str (:id ada)) "confirm" "true"})))))
          (is (= [{:permission "invoice/read" :scope "{:department-id #{7 70}}"}]
                 (->> (ports/find-audit-logs-by-user audit (:id ada) {:limit 10})
                      (some #(when (= "group-join" (name (:action %))) %))
                      :changes :effective :added
                      (map #(update-keys % keyword))))))
        (testing "removing the role names every member it reaches"
          (let [body (:body (app (req admin :post (str path "/roles/" (:id a) "/remove"))))]
            (is (str/includes? body "preview-affected-count"))
            (is (str/includes? body "ada@example.com"))
            (is (str/includes? body "bob@example.com"))
            (is (= 1 (count (:roles (ports/get-group gsvc gid)))))))
        (testing "a role's permissions changed are previewed on its page"
          (ports/create-role gsvc {:name :invoice/clerk :permissions #{:invoice/read}})
          (ports/add-group-role gsvc gid {:role :invoice/clerk :scope {}})
          (let [body (:body (app (req admin :post "/web/admin/roles/save"
                                      {"name" "invoice/clerk" "permissions" ["invoice/read" "invoice/approve"]})))]
            (is (str/includes? body "preview-permission"))
            (is (str/includes? body "invoice/approve"))
            (is (str/includes? body "name=\"permissions\" type=\"hidden\" value=\"invoice/approve\""))
            (is (= #{:invoice/read} (:permissions (some #(when (= :invoice/clerk (:name %)) %) (ports/list-roles gsvc)))))))))))
