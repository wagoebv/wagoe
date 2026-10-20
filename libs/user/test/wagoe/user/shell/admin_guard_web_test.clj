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

(defn- service []
  (let [repo (persistence/create-user-repository *ctx*)]
    [repo (user-service/create-user-service
           repo
           (persistence/create-session-repository *ctx*)
           (persistence/create-audit-repository *ctx* {:default-limit 20 :max-limit 100})
           {} nil)]))

(defn- user! [repo email role]
  (.create-user repo {:email email :name email :role role :active true
                      :password-hash "bcrypt+sha512$test"}))

(defn- bulk [svc ids action]
  ((web-handlers/bulk-update-users-htmx-handler svc {})
   {:request-method :post
    :uri            "/web/users/bulk"
    :form-params    {"ids[]" (mapv str ids) "action" action}}))

(deftest ^:integration a-bulk-role-change-sends-only-the-role
  ;; Writing back the whole cached user failed validation on its empty fields.
  (let [[repo svc] (service)
        _boss (user! repo "boss@example.com" :admin)
        tenant (random-uuid)
        u     (.create-user repo {:email "u@example.com" :name "u" :role :user :active true
                                  :password-hash "bcrypt+sha512$test"
                                  :tenant-id tenant :theme :dark})
        resp  (bulk svc [(:id u)] "role-admin")
        after (.find-user-by-id repo (:id u))]
    (is (= 200 (:status resp)))
    (is (= :admin (:role after)))
    (is (= [tenant :dark] [(:tenant-id after) (:theme after)])
        "fields the update left out keep their stored values")))

(deftest ^:integration a-bulk-demote-of-the-last-admin-gets-409
  (let [[repo svc] (service)
        boss (user! repo "boss@example.com" :admin)
        resp (bulk svc [(:id boss)] "role-user")]
    (is (= 409 (:status resp)))
    (is (= :admin (:role (user-ports/get-user-by-id svc (:id boss)))))))

(deftest ^:integration an-admin-demoting-themselves-gets-409
  (let [[repo svc] (service)
        new!    #(user! repo % :admin)
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
