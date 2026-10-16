(ns wagoe.user.shell.admin-guard-web-test
  "BOU-665: the authentication middleware makes the signed-in user the actor,
   so /web/users refuses an admin removing their own rights."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [support.test-db :as test-db]
            [wagoe.user.ports :as user-ports]
            [wagoe.user.shell.middleware :as middleware]
            [wagoe.user.shell.persistence :as persistence]
            [wagoe.user.shell.service :as user-service]
            [wagoe.user.shell.web-handlers :as web-handlers]))

(def ^:private ^:dynamic *ctx* nil)

(use-fixtures :each
  (fn [f]
    (test-db/with-db "admin_guard_web"
      (fn [db-ctx]
        (persistence/initialize-user-schema! db-ctx)
        (binding [*ctx* db-ctx] (f))))))

(deftest ^:integration an-admin-demoting-themselves-gets-409
  (let [repo    (persistence/create-user-repository *ctx*)
        svc     (user-service/create-user-service
                 repo
                 (persistence/create-session-repository *ctx*)
                 (persistence/create-audit-repository *ctx* {:default-limit 20 :max-limit 100})
                 {} nil)
        new!    #(.create-user repo {:email % :name % :role :admin :active true
                                     :password-hash "bcrypt+sha512$test"})
        boss    (new! "boss@example.com")
        _other  (new! "other@example.com")
        handler (middleware/flexible-authentication-middleware
                 svc (web-handlers/update-user-htmx-handler svc {}))
        resp    (handler {:request-method :put
                          :uri            (str "/web/users/" (:id boss))
                          :user           boss
                          :path-params    {:id (str (:id boss))}
                          :form-params    {"name" "Boss" "email" "boss@example.com"
                                           "role" "user" "active" "on"}})]
    (is (= 409 (:status resp)))
    (is (= :admin (:role (user-ports/get-user-by-id svc (:id boss)))))))
