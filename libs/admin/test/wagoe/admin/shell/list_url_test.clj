(ns wagoe.admin.shell.list-url-test
  "An entity whose module has its own list page: the admin's list redirects
   there, its query kept (BOU-1003)."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [wagoe.admin.schema :as schema]
            [wagoe.admin.shell.http.handlers.list :as list]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.shell.service :as service]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [malli.core :as m]))

^{:kaocha.testable/meta {:integration true :admin true}}

(def ^:private config
  {:entity-discovery {:mode :allowlist :allowlist #{:lu-people}}
   :entities         {:lu-people {:label "People" :list-fields [:name] :list-url "/web/people"}}
   :pagination       {:default-page-size 20 :max-page-size 200}})

(def ^:private admin {:id (random-uuid) :role :admin :active true})

(deftest ^:unit list-url-is-an-entity-option
  (is (m/validate schema/EntityOverrides {:list-url "/web/people"}))
  (is (not (m/validate schema/EntityOverrides {:list-url :people}))))

(deftest ^:unit only-an-administrator-is-sent-to-the-modules-list
  (let [cfg {:list-url "/web/people"}]
    (is (= "/web/people?q=a" (list/list-redirect cfg {:role :admin} "q=a")))
    (is (= "/web/people" (list/list-redirect cfg {:role :admin} nil)))
    (is (nil? (list/list-redirect cfg {:role :user} nil))
        "under :enforce one may read users through a permission; the module's list asks Administrator")
    (is (nil? (list/list-redirect {} {:role :admin} nil)))))

(deftest ^:unit the-shipped-users-entity-has-one-list
  ;; The test profile leaves it out: e2e checks the admin's own list there
  (doseq [f ["resources/conf/dev/admin/users.edn"
             "libs/tools/resources/wagoe/tools/admin/users.edn"
             "examples/shop/resources/conf/dev/admin/users.edn"
             "examples/shop/resources/conf/test/admin/users.edn"]
          :let [users (:users (edn/read-string (slurp f)))]]
    (is (= "/web/users" (:list-url users)) f)
    (is (m/validate schema/EntityOverrides (select-keys users [:list-url :create-redirect-url])) f)))

(deftest ^:unit the-users-field-reads-administrator
  ;; BOU-1003: "role" is for the Roles page
  (doseq [[f expected] [["en.edn" {:admin.users/field-role "Administrator" :common/label-role "Administrator"
                                   :common/column-role "Administrator" :common/role-admin "Yes" :common/role-user "No"}]
                        ["nl.edn" {:admin.users/field-role "Beheerder" :common/label-role "Beheerder"
                                   :common/column-role "Beheerder" :common/role-admin "Ja" :common/role-user "Nee"}]]
          :let [t (edn/read-string (slurp (str "libs/i18n/resources/wagoe/i18n/translations/" f)))]]
    (is (= expected (select-keys t (keys expected))) f)
    (is (re-find #"(?i)admin|beheer" (:admin.users/role-help t)) "one line on what it opens")))

(deftest ^:integration the-admins-list-redirects-to-the-modules-own
  (let [ctx (db-factory/db-context {:adapter       :h2
                                    :database-path "mem:admin_list_url;DB_CLOSE_DELAY=-1"
                                    :pool          {:minimum-idle 1 :maximum-pool-size 2}})]
    (try
      (db/execute-update! ctx {:raw "CREATE TABLE lu_people (id UUID PRIMARY KEY, name VARCHAR(20))"})
      (let [sp  (schema-repo/create-schema-repository ctx config)
            svc (service/create-admin-service ctx sp nil nil config nil nil)
            r   ((list/entity-list-handler svc sp config)
                 {:request-method :get :uri "/web/admin/lu-people" :headers {} :user admin :session {:user admin}
                  :path-params {:entity "lu-people"} :query-params {"q" "ann"} :query-string "q=ann"})]
        (is (= 303 (:status r)))
        (is (= "/web/people?q=ann" (get-in r [:headers "Location"])))
        (testing "no query, and a request for the table alone, go there too"
          (let [handle (list/entity-list-handler svc sp config)
                base {:request-method :get :uri "/web/admin/lu-people" :headers {} :user admin :session {:user admin}
                      :path-params {:entity "lu-people"} :query-params {}}]
            (is (= "/web/people" (get-in (handle base) [:headers "Location"])))
            (is (= "/web/people" (get-in (handle (assoc base :headers {"hx-request" "true" "hx-target" "entity-table-container"}))
                                         [:headers "Location"]))))))
      (finally
        (db/execute-update! ctx {:raw "DROP TABLE lu_people"})
        (db-factory/close-db-context! ctx)))))
