(ns wagoe.user.shell.group-service-test
  "The group service on a real database: declared schemas refuse bad grants,
   every change to a user's rights is audited, and a change applies to the
   next request."
  (:require [clojure.test :refer [deftest is]]
            [support.test-db :as test-db]
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
