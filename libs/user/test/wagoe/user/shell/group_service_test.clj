(ns wagoe.user.shell.group-service-test
  "The group service on a real database: declared schemas refuse bad grants,
   every change to a user's rights is audited, a change applies to the next
   request, and the migration from grants and the :viewer role keeps
   everyone's reach."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [support.test-db :as test-db]
            [wagoe.platform.core.authorization :as authz-core]
            [wagoe.platform.shell.authorization :as authz]
            [wagoe.user.core.group :as group]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.group-persistence :as group-persistence]
            [wagoe.user.shell.group-service :as sut]
            [wagoe.user.shell.persistence :as persistence]
            [wagoe.user.shell.service :as user-service]))

(def permissions
  "Named by a symbol in the test's settings, as an application would."
  [:enum :invoice/approve :report/view])

(defn- with-service
  ([settings f] (with-service settings {} f))
  ([settings opts f]
   (test-db/with-db "user_group_service"
     (fn [ctx]
       (persistence/initialize-user-schema! ctx)
       (let [users (persistence/create-user-repository ctx)
             audit (persistence/create-audit-repository ctx {:default-limit 50 :max-limit 100})
             svc   (sut/create-group-service (group-persistence/create-group-repository ctx)
                                             users audit (sut/prepare-settings settings) opts)
             user! #(ports/create-user users {:email % :name "Member" :role :user :active true
                                              :password-hash "x"})]
         (f {:svc svc :users users :audit audit :user! user! :ctx ctx}))))))

(defn- actions [audit user]
  (->> (ports/find-audit-logs-by-user audit (:id user) {:limit 50 :sort-by :created-at :sort-direction :asc})
       (map :action)
       sort))

(deftest ^:integration grants-are-checked-against-the-declared-schemas
  (with-service {:permissions `permissions
                 :scope       [:map {:closed true} [:domain-id {:optional true} :int]]}
    (fn [{:keys [svc]}]
      (let [g (ports/create-group svc {:name "Finance"})]
        (is (= :invoice/approve (:permission (ports/add-group-grant svc (:id g) {:permission :invoice/approve
                                                                                 :scope      {:domain-id 3}}))))
        (is (= {} (:scope (ports/add-group-grant svc (:id g) {:permission :report/view}))) "no scope is {}")
        (doseq [[grant field] [[{:permission :invoice/delete :scope {}} :permission]
                               [{:permission :invoice/approve :scope {:tenant 1}} :scope]
                               [{:permission :invoice/approve :scope {:domain-id "3"}} :scope]]]
          (let [e (try (ports/add-group-grant svc (:id g) grant) nil
                       (catch clojure.lang.ExceptionInfo e e))]
            (is (= :validation-error (:type (ex-data e))) (pr-str grant))
            (is (= field (:field (ex-data e))) (pr-str grant))))
        (is (= 2 (count (:roles (ports/get-group svc (:id g))))) "a refused grant is not stored")))))

(deftest ^:unit bad-declarations-fail-boot
  (doseq [bad [{:permissions 'no.such/schema}
               {:permissions [:not-a-malli-type]}
               {:scope 'clojure.core/no-such-var}
               {:unknown true}]]
    (is (= :configuration-error
           (:type (ex-data (try (sut/prepare-settings bad) nil
                                (catch clojure.lang.ExceptionInfo e e)))))
        (pr-str bad)))
  (is (= {:permissions nil :scope-schema nil} (sut/prepare-settings nil))))

(deftest ^:unit the-permissions-enum-is-declared-to-the-registry
  (is (= {:permissions {:orders/refund {:label "orders/refund" :source :groups-schema}
                        :approve       {:label "approve" :source :groups-schema}}}
         (sut/declared-permissions (sut/prepare-settings {:permissions [:enum :orders/refund :approve]}))))
  (testing "a schema that is not an enum lists nothing"
    (is (nil? (sut/declared-permissions (sut/prepare-settings {:permissions :keyword}))))
    (is (nil? (sut/declared-permissions (sut/prepare-settings nil))))))

(deftest ^:integration every-change-to-a-users-rights-is-audited
  (with-service nil
    (fn [{:keys [svc audit user!]}]
      (let [ada   (user! "ada@example.com")
            bob   (user! "bob@example.com")
            admin (random-uuid)
            g     (ports/create-group svc {:name "Finance"})
            empty-g (ports/create-group svc {:name "Empty"})]
        (user-service/with-audit-context {:actor-id admin :actor-email "admin@example.com"}
          (fn []
            (ports/add-group-grant svc (:id g) {:permission :invoice/approve :scope {:domain-id 1}})
            (is (true? (ports/add-group-member svc (:id g) (:id ada))))
            (is (false? (ports/add-group-member svc (:id g) (:id ada))) "joining twice is not audited twice")
            (ports/add-group-member svc (:id g) (:id bob))
            (let [grant (ports/add-group-grant svc (:id g) {:permission :report/view :scope {}})]
              (is (true? (ports/remove-group-grant svc (:id g) (:id grant)))))
            (is (true? (ports/remove-group-member svc (:id g) (:id bob))))
            (ports/add-group-grant svc (:id empty-g) {:permission :report/view :scope {}})
            (is (true? (ports/delete-group svc (:id g))))))
        (is (= [:grant-add :grant-remove :group-join :group-leave] (actions audit ada))
            "the grant added before ada joined is in her join entry, not a grant-add")
        (is (= [:grant-add :grant-remove :group-join :group-leave] (actions audit bob)))
        (let [join (->> (ports/find-audit-logs-by-user audit (:id ada) {:limit 50})
                        (filter #(= :group-join (:action %)))
                        first)]
          (is (= admin (:actor-id join)))
          (is (= "admin@example.com" (:actor-email join)))
          (is (= "Finance" (get-in join [:metadata :group-name])))
          (is (= [{:permission "invoice/approve" :scope "{:domain-id 1}" :role "invoice/approve"}]
                 (get-in join [:changes :grants]))))))))

(deftest ^:integration a-change-applies-to-the-next-request
  ;; What an application handler does on each request: read the grants, check
  ;; them. Nothing is cached, so nothing waits for a new session.
  (with-service nil
    (fn [{:keys [svc user!]}]
      (let [ada     (user! "ada@example.com")
            g       (ports/create-group svc {:name "Finance"})
            handler (fn [request]
                      (if (group/authorized? (ports/user-grants svc (:user request)) :invoice/approve
                                             #(= 1 (:domain-id %)))
                        {:status 200}
                        {:status 403}))
            request {:user ada}]
        (ports/add-group-member svc (:id g) (:id ada))
        (is (= 403 (:status (handler request))))
        (let [grant (ports/add-group-grant svc (:id g) {:permission :invoice/approve :scope {:domain-id 1}})]
          (is (= 200 (:status (handler request))))
          (ports/remove-group-grant svc (:id g) (:id grant))
          (is (= 403 (:status (handler request)))))
        (ports/add-group-grant svc (:id g) {:permission :invoice/approve :scope {:domain-id 1}})
        (is (= 200 (:status (handler request))))
        (ports/remove-group-member svc (:id g) (:id ada))
        (is (= 403 (:status (handler request))))
        (is (= [] (ports/user-grants svc nil)))))))

(deftest ^:integration unknown-groups-and-users-are-refused
  (with-service nil
    (fn [{:keys [svc user!]}]
      (let [ada (user! "ada@example.com")
            g   (ports/create-group svc {:name "Finance"})]
        (doseq [f [#(ports/add-group-member svc (random-uuid) (:id ada))
                   #(ports/add-group-member svc (:id g) (random-uuid))
                   #(ports/add-group-grant svc (random-uuid) {:permission :a})
                   #(ports/update-group svc {:id (random-uuid) :name "X"})]]
          (is (= :not-found (:type (ex-data (try (f) nil (catch clojure.lang.ExceptionInfo e e)))))))
        (is (= :validation-error
               (:type (ex-data (try (ports/create-group svc {:name "  "}) nil
                                    (catch clojure.lang.ExceptionInfo e e))))))
        (is (false? (ports/delete-group svc (random-uuid))))
        (is (false? (ports/remove-group-grant svc (:id g) (random-uuid))))))))

(deftest ^:integration permission-holders-in-one-call
  (with-service nil
    (fn [{:keys [svc user!]}]
      (let [ada (user! "ada@example.com")
            bob (user! "bob@example.com")]
        (ports/import-groups! svc [{:name "Finance" :members [(:id ada) (:id bob)]
                                    :grants [{:permission :invoice/approve :scope {:domain-id 1}}]}
                                   {:name "Audit" :members [(:id bob)]
                                    :grants [{:permission :invoice/approve :scope {:domain-id 2}}]}]
                              {})
        (let [holders (ports/permission-holders svc :invoice/approve)]
          (is (= #{(:id ada) (:id bob)} (set (group/users-with-permission holders (constantly true)))))
          (is (= [(:id bob)] (group/users-with-permission holders #(= 2 (:domain-id %))))))))))

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest ^:integration importing-groups-is-idempotent-and-audited
  (with-service {:permissions `permissions}
    (fn [{:keys [svc audit user!]}]
      (let [ada    (user! "ada@example.com")
            bob    (user! "bob@example.com")
            admin  (random-uuid)
            finance {:name "Finance" :description "Pays" :members [(:id ada)]
                     :grants [{:permission :invoice/approve :scope {:domain-id 1}}]}
            import! #(user-service/with-audit-context {:actor-id admin}
                       (fn [] (ports/import-groups! svc %1 %2)))
            group  #(ports/get-group svc (:id (first (filter (comp #{"Finance"} :name) (ports/list-groups svc)))))
            shape  (fn [g] {:members (set (map :email (:members g)))
                            :grants  (set (map (juxt :role :scope) (:roles g)))})]
        (let [[result] (import! [finance] {})]
          (is (true? (:created? result)))
          (is (= [(:id ada)] (:joined result))))
        (is (= [false] (map :created? (import! [finance] {}))) "the second import finds the group by name")
        (is (= {:members #{"ada@example.com"} :grants #{[:invoice/approve {:domain-id 1}]}} (shape (group))))
        (is (= [:group-join] (actions audit ada)) "an import that changes nothing audits nothing")

        (testing "additive: what is not listed stays"
          (import! [{:name "Finance" :members [(:id bob)] :grants [{:permission :report/view}]}] {})
          (is (= {:members #{"ada@example.com" "bob@example.com"}
                  :grants  #{[:invoice/approve {:domain-id 1}] [:report/view {}]}}
                 (shape (group))))
          (is (= "Pays" (:description (group))) "a description not given is kept"))

        (testing "replace: what is not listed goes"
          (let [[result] (import! [(assoc finance :members [(:id bob)])] {:replace? true})]
            (is (= [(:id ada)] (:left result)))
            (is (= [:report/view] (map :role (:roles-removed result)))))
          (is (= {:members #{"bob@example.com"} :grants #{[:invoice/approve {:domain-id 1}]}} (shape (group)))))

        (is (= [:grant-add :group-join :group-leave] (actions audit ada)))
        (is (= [:grant-remove :group-join] (actions audit bob))
            "bob joined holding both grants, then lost one")
        (is (every? #(= admin (:actor-id %))
                    (ports/find-audit-logs-by-user audit (:id bob) {:limit 50})))))))

(deftest ^:integration a-refused-import-changes-nothing
  (with-service {:permissions `permissions}
    (fn [{:keys [svc user!]}]
      (let [ada  (user! "ada@example.com")
            good {:name "Finance" :members [(:id ada)] :grants [{:permission :invoice/approve}]}]
        (doseq [[groups field] [[[good {:name "Audit" :grants [{:permission :invoice/delete}]}] :permission]
                                [[good {:name "Audit" :grants [{:permission :report/view :scope [1]}]}] :scope]
                                [[good {:name "Audit" :members [(random-uuid)]}] :members]]]
          (let [data (refusal #(ports/import-groups! svc groups {}))]
            (is (= :validation-error (:type data)))
            (is (= field (:field data)))
            (is (= "Audit" (:group data)))))
        (is (= :validation-error (:type (refusal #(ports/import-groups! svc [good good] {}))))
            "a group listed twice")
        (is (= :validation-error (:type (refusal #(ports/import-groups! svc [{:name "X" :colour "red"}] {})))))
        (is (empty? (ports/list-groups svc)) "nothing is written before every group is checked")))))

(deftest ^:integration a-failed-audit-entry-rolls-the-import-back
  (test-db/with-db "user_group_import_rollback"
    (fn [ctx]
      (persistence/initialize-user-schema! ctx)
      (let [users  (persistence/create-user-repository ctx)
            ada    (ports/create-user users {:email "ada@example.com" :name "Ada" :role :user :active true
                                             :password-hash "x"})
            broken (reify ports/IUserAuditRepository
                     (create-audit-log [_ _] (throw (ex-info "audit down" {:type :internal-error})))
                     (find-audit-logs [_ _] [])
                     (find-audit-logs-by-user [_ _ _] [])
                     (delete-audit-logs-before [_ _] 0))
            repo   (group-persistence/create-group-repository ctx)
            svc    (sut/create-group-service repo users broken (sut/prepare-settings nil))]
        (is (= "audit down" (ex-message (try (ports/import-groups! svc [{:name "Finance" :members [(:id ada)]}] {})
                                             nil
                                             (catch clojure.lang.ExceptionInfo e e)))))
        (is (empty? (ports/find-groups repo)))))))

(deftest ^:integration concurrent-imports-of-a-new-group
  ;; One creates it; the other finds it or gets a :conflict, and a rerun works.
  (with-service nil
    (fn [{:keys [svc user!]}]
      (let [ada     (user! "ada@example.com")
            bob     (user! "bob@example.com")
            start   (promise)
            import! (fn [member]
                      (future @start
                              (try (ports/import-groups! svc [{:name "Finance" :members [(:id member)]}] {})
                                   :ok
                                   (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
            runs    [(import! ada) (import! bob)]]
        (deliver start true)
        (let [outcomes (map deref runs)]
          (is (every? #{:ok :conflict} outcomes) (pr-str outcomes))
          (is (some #{:ok} outcomes)))
        (is (= ["Finance"] (map :name (ports/list-groups svc))))
        (ports/import-groups! svc [{:name "Finance" :members [(:id ada) (:id bob)]}] {})
        (is (= 2 (:member-count (first (ports/list-groups svc)))))))))

(deftest ^:integration an-import-creates-the-tables-it-needs
  ;; As in an application's migration ordered before the user library's.
  (test-db/with-db "user_group_import_tables"
    (fn [ctx]
      (let [svc (sut/create-group-service (group-persistence/create-group-repository ctx)
                                          (persistence/create-user-repository ctx)
                                          (persistence/create-audit-repository ctx {:default-limit 50 :max-limit 100})
                                          (sut/prepare-settings nil))]
        (is (= ["Finance"] (map (comp :name :group) (ports/import-groups! svc [{:name "Finance"}] {}))))))))

;; =============================================================================
;; Roles, Everyone, provider groups and the migration
;; =============================================================================

(def ^:private registry
  (authz/create-registry {:wagoe/orders  nil
                          :wagoe/reports nil
                          :wagoe/admin   {:permissions (authz-core/read-write-permissions "admin.users" "users")}}))

(defn- permissions-of [grants]
  (set (map :permission grants)))

(deftest ^:integration roles-are-assigned-to-groups
  (with-service nil {:registry registry}
    (fn [{:keys [svc audit user!]}]
      (let [ada (user! "ada@example.com")
            g   (ports/create-group svc {:name "Sales"})]
        (ports/add-group-member svc (:id g) (:id ada))
        (testing "a code role"
          (ports/add-group-role svc (:id g) {:role :orders/editor})
          (is (= #{:orders/read :orders/write} (permissions-of (ports/user-grants svc ada))))
          (is (= #{:orders/editor} (set (map :role (ports/user-grants svc ada))))))
        (testing "a managed role, from the registry's permissions"
          (is (= :conflict
                 (:type (refusal #(do (ports/create-role svc {:name :sales/lead :permissions #{:reports/read}})
                                      (ports/create-role svc {:name :sales/lead :permissions #{:reports/read}}))))))
          (is (= :permissions (:field (refusal #(ports/create-role svc {:name :x/y :permissions #{:orders/refnud}})))))
          (is (= :name (:field (refusal #(ports/create-role svc {:name :orders/viewer :permissions #{:orders/read}}))))
              "a code role's name")
          (let [a (ports/add-group-role svc (:id g) {:role :sales/lead :scope {:domain-id 4}})]
            (is (contains? (set (map (juxt :permission :scope) (ports/user-grants svc ada)))
                           [:reports/read {:domain-id 4}]))
            (ports/update-role svc {:name :sales/lead :permissions #{:reports/read :reports/write}})
            (is (contains? (permissions-of (ports/user-grants svc ada)) :reports/write)
                "a changed role applies to the next request")
            (is (= :role (:field (refusal #(ports/add-group-role svc (:id g) {:role :no/such})))))
            (is (true? (ports/remove-group-role svc (:id g) (:id a))))
            (is (true? (ports/delete-role svc :sales/lead)))
            (is (= #{:orders/read :orders/write} (permissions-of (ports/user-grants svc ada))))))
        (is (= [:grant-add :grant-add :grant-add :grant-remove :group-join] (actions audit ada))
            "assigning, changing and unassigning a role are audited for the member; deleting it
             once unassigned changes nobody's rights")
        (is (some #(= {:name :orders/editor :source :code} (select-keys % [:name :source]))
                  (ports/list-roles svc)))))))

(deftest ^:integration everyone-and-provider-groups-count-as-membership
  (with-service nil {:registry registry :membership (fn [user] (when (= "ada@example.com" (:email user))
                                                                 #{"Newsroom" "No such group"}))}
    (fn [{:keys [svc user!] :as env}]
      (sut/migrate! svc)
      (let [ada      (user! "ada@example.com")
            newsroom (ports/create-group svc {:name "Newsroom"})]
        (ports/add-group-role svc group/everyone-id {:role :reports/viewer})
        (ports/add-group-role svc (:id newsroom) {:role :orders/editor})
        (let [bob (user! "bob@example.com")]
          (is (= #{:reports/read} (permissions-of (ports/user-grants svc bob)))
              "a user created after the role was given is in Everyone")
          (is (= #{:reports/read :orders/read :orders/write} (permissions-of (ports/user-grants svc (:id ada))))
              "the provider's group counts, looked up by id too")
          (is (= {"Everyone" :everyone "Newsroom" :provider}
                 (into {} (map (juxt :name :source)) (ports/user-groups svc ada))))
          (is (= :validation-error (:type (refusal #(ports/add-group-member svc group/everyone-id (:id bob))))))
          (is (= :validation-error (:type (refusal #(ports/delete-group svc group/everyone-id)))))
          (is (= #{(:id ada) (:id bob)}
                 (set (map :user-id (ports/permission-holders svc :reports/read)))))
          (ports/soft-delete-user (:users env) (:id bob))
          (is (= [] (ports/user-grants svc bob)) "a soft-deleted user is in no group"))))))

(defn- legacy-grant! [ctx group-id permission scope]
  (jdbc/execute! (:datasource ctx)
                 ["INSERT INTO user_group_grants (id, group_id, permission, scope, created_at) VALUES (?, ?, ?, ?, ?)"
                  (str (random-uuid)) (str group-id) (group/permission-str permission) (pr-str scope)
                  (java.sql.Timestamp/from (java.time.Instant/now))]))

(defn- stored-role [ctx user]
  (:role (jdbc/execute-one! (:datasource ctx) ["SELECT role FROM users WHERE id = ?" (str (:id user))]
                            {:builder-fn rs/as-unqualified-lower-maps})))

(deftest ^:integration the-migration-keeps-everyones-reach
  ;; Under :off every signed-in user reaches every module route, and a group
  ;; grant is held in its scope. After the migration the same rights come from
  ;; roles, and no former :user or :viewer holds an admin permission.
  (with-service nil {:registry registry}
    (fn [{:keys [svc users ctx user!]}]
      (let [admin   (ports/create-user users {:email "root@example.com" :name "Root" :role :admin :active true
                                              :password-hash "x"})
            ada     (user! "ada@example.com")
            vic     (user! "vic@example.com")
            finance (ports/create-group svc {:name "Finance"})
            module  #{:orders/read :orders/write :reports/read :reports/write}]
        (jdbc/execute! (:datasource ctx) ["UPDATE users SET role = 'viewer' WHERE id = ?" (str (:id vic))])
        (ports/add-group-member svc (:id finance) (:id ada))
        (legacy-grant! ctx (:id finance) :invoice/approve {:domain-id 1})
        (legacy-grant! ctx (:id finance) :invoice/approve {:domain-id 2})
        (sut/migrate! svc)
        (sut/migrate! svc)
        (doseq [u [ada vic]]
          (let [grants (ports/user-grants svc u)]
            (is (set/subset? module (permissions-of grants)) (:email u))
            (is (not-any? #(re-find #"^admin" (str (namespace (:permission %)))) grants) (:email u))
            (is (nil? (:role (ports/find-user-by-id users (:id u)))))))
        (is (= #{{:domain-id 1} {:domain-id 2}} (set (group/grants-for (ports/user-grants svc ada) :invoice/approve)))
            "each grant is an assignment of the role named after its permission")
        (is (= :admin (:role (ports/find-user-by-id users (:id admin)))))
        (is (= "user" (stored-role ctx vic)) "the column keeps \"user\" for no role")
        (let [readers (some #(when (= "Readers" (:name %)) %) (ports/list-groups svc))]
          (is (= ["vic@example.com"] (map :email (:members (ports/get-group svc (:id readers))))))
          (is (= #{:orders/viewer :reports/viewer}
                 (set (map :role (:roles (ports/get-group svc (:id readers))))))))
        (is (= #{:orders/editor :reports/editor}
               (set (map :role (:roles (ports/get-group svc group/everyone-id)))))
            "Everyone gets every module's editor role but the admin's, once")
        (is (= 2 (count (:roles (ports/get-group svc (:id finance))))) "run twice, nothing doubled")
        (is (zero? (:n (jdbc/execute-one! (:datasource ctx) ["SELECT COUNT(*) AS n FROM user_group_grants"]
                                          {:builder-fn rs/as-unqualified-lower-maps}))))))))

(deftest ^:integration everyone-narrowed-stays-narrowed
  (with-service nil {:registry registry}
    (fn [{:keys [svc user!]}]
      (user! "ada@example.com")
      (sut/migrate! svc)
      (doseq [a (:roles (ports/get-group svc group/everyone-id))]
        (ports/remove-group-role svc group/everyone-id (:id a)))
      (sut/migrate! svc)
      (is (empty? (:roles (ports/get-group svc group/everyone-id)))
          "a later boot does not hand the editor roles out again"))))

(deftest ^:integration only-a-platform-admin-sets-or-clears-admin
  (with-service nil
    (fn [{:keys [users user!]}]
      (let [svc     (user-service/create-user-service users nil nil {} nil)
            ada     (user! "ada@example.com")
            tenant  (ports/create-user users {:email "t@example.com" :name "T" :role :admin :active true
                                              :tenant-id (random-uuid) :password-hash "x"})
            as      (fn [actor f] (user-service/with-audit-context {:actor-id (:id actor)} f))]
        (is (= :forbidden
               (:type (refusal #(as tenant (fn [] (ports/update-user-profile svc {:id (:id ada) :role :admin})))))))
        (is (= :forbidden
               (:type (refusal #(as ada (fn [] (ports/update-user-profile svc {:id (:id ada) :role :admin})))))))))))
