(ns wagoe.admin.shell.list-url-test
  "An entity whose module has its own list page: the admin's list redirects
   there, its query kept (BOU-1003)."
  (:require [clojure.test :refer [deftest is]]
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
        (is (= "/web/people?q=ann" (get-in r [:headers "Location"]))))
      (finally
        (db/execute-update! ctx {:raw "DROP TABLE lu_people"})
        (db-factory/close-db-context! ctx)))))
