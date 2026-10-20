(ns wagoe.user.shell.group-service-test
  "The group service on a real database: declared schemas refuse bad grants,
   every change to a user's rights is audited, and a change applies to the
   next request."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [support.test-db :as test-db]
            [wagoe.platform.shell.authorization :as authorization]
            [wagoe.user.core.group :as group]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.group-persistence :as group-persistence]
            [wagoe.user.shell.group-service :as sut]
            [wagoe.user.shell.persistence :as persistence]
            [wagoe.user.shell.service :as user-service]))

(def permissions
  "Named by a symbol in the test's settings, as an application would."
  [:enum :invoice/approve :report/view])

(defn- with-service [settings f]
  (test-db/with-db "user_group_service"
    (fn [ctx]
      (persistence/initialize-user-schema! ctx)
      (let [users (persistence/create-user-repository ctx)
            audit (persistence/create-audit-repository ctx {:default-limit 50 :max-limit 100})
            svc   (sut/create-group-service (group-persistence/create-group-repository ctx)
                                            users audit (sut/prepare-settings settings))
            user! #(ports/create-user users {:email % :name "Member" :role :user :active true
                                             :password-hash "x"})]
        (f {:svc svc :users users :audit audit :user! user!})))))

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
        (is (= 2 (count (:grants (ports/get-group svc (:id g))))) "a refused grant is not stored")))))

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

(deftest ^:unit the-scope-map-is-declared-to-the-registry
  (let [{:keys [scopes]} (sut/declared-scopes
                          (sut/prepare-settings {:scope [:map [:domain-id {:optional true :wagoe/entity :domains} :int]]}))]
    (is (= {:label "domain-id" :source :groups-schema :entity :domains}
           (dissoc (:domain-id scopes) :schema)))
    (is (= :int (m/form (get-in scopes [:domain-id :schema])))))
  (is (nil? (sut/declared-scopes (sut/prepare-settings {:scope [:map-of :keyword :any]})))))

(deftest ^:unit grants-are-checked-against-the-registrys-scope-keys
  (let [registry (authorization/create-registry
                  {:wagoe/app {:scopes        {:domain-id     {:label "Domain" :entity :domains :schema :int}
                                               :department-id {:label "Department" :sources #{:user/department}}}
                               :scope-sources {:user/department {:label "Their department" :resolve (constantly #{7})}}}})
        settings (sut/with-registry-scopes (sut/prepare-settings nil) registry)
        refused  #(get-in (group/grant-refusal {:permission :orders/read :scope %} settings) [:error :field])]
    (is (nil? (refused {:domain-id 42})))
    (is (nil? (refused {:department-id :user/department})) "a value taken from the user")
    (is (nil? (refused {})))
    (is (= :scope (refused {:domain-id "42"})))
    (is (= :scope (refused {:area-id 1})) "an undeclared key")
    (is (= :scope (refused {:domain-id :user/department})) "a source the key does not take")
    (testing "without declared scope keys, the :groups :scope schema stands"
      (is (= (sut/prepare-settings nil)
             (sut/with-registry-scopes (sut/prepare-settings nil) (authorization/create-registry {:wagoe/app nil})))))))

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
          (is (= [{:permission "invoice/approve" :scope "{:domain-id 1}"}] (get-in join [:changes :grants]))))))))

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
                            :grants  (set (map (juxt :permission :scope) (:grants g)))})]
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
            (is (= [:report/view] (map :permission (:grants-removed result)))))
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
