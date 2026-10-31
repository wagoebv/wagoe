(ns wagoe.admin.shell.list-params-test
  "A list's sort and filters come from the request. Only fields the entity
   shows may reach SQL: a hidden one leaked password hashes through a
   `contains` filter, and an unknown one answered 500. Run on SQLite and H2
   over the user module's boot schema, with the users entity `bb setup` ships."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.http.support :as support]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.shell.service :as service]
            [wagoe.observability.errors.shell.adapters.no-op :as error-reporting-no-op]
            [wagoe.observability.logging.shell.adapters.no-op :as logging-no-op]
            [wagoe.platform.shell.adapters.database.factory :as factory]
            [wagoe.user.ports :as user-ports]
            [wagoe.user.shell.persistence :as user-persistence]))

(def ^:private users-entity
  (delay (edn/read-string
          {:default (fn [_ v] v)}
          (some #(let [f (io/file % "tools/resources/wagoe/tools/admin/users.edn")]
                   (when (.exists f) (slurp f)))
                ["libs" ".."]))))

(def ^:private ^:dynamic *repo* nil)

(defn- with-admin [f]
  (doseq [[engine open] [[:sqlite #(let [db (java.io.File/createTempFile "admin-params-" ".db")]
                                     (.deleteOnExit db)
                                     (factory/db-context (factory/sqlite-config (str db))))]
                         [:h2 #(factory/db-context
                                (factory/h2-config (str "mem:admin_params_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")))]]]
    (testing (name engine)
      (let [ctx (open)]
        (try
          (user-persistence/initialize-user-schema! ctx)
          (let [config {:enabled? true
                        :entity-discovery {:mode :allowlist :allowlist #{:users}}
                        :entities @users-entity}
                repo   (user-persistence/create-user-repository ctx)
                admin  (service/create-admin-service
                        ctx (schema-repo/create-schema-repository ctx config)
                        (logging-no-op/create-logging-component {})
                        (error-reporting-no-op/create-error-reporting-component {})
                        config)]
            (doseq [[e role] [["ada@example.com" :user] ["bram@example.com" :admin]]]
              (user-ports/create-user repo {:email e :name e :role role :active true
                                            :password-hash "$2a$12$secret"}))
            (binding [*repo* repo] (f admin)))
          (finally (factory/close-db-context! ctx)))))))

(defn- emails [admin query-params]
  (->> (ports/list-entities admin :users (support/parse-query-params query-params))
       :records (map :email) sort vec))

(def ^:private both ["ada@example.com" "bram@example.com"])

(deftest ^:integration a-hidden-field-is-neither-filtered-nor-sorted-on
  (with-admin
    (fn [admin]
      (testing "a contains filter on the password hash is dropped, not applied"
        (is (= both (emails admin {"filters[password-hash][op]" "contains"
                                   "filters[password-hash][value]" "zzzz"}))))
      (testing "nor on the MFA secret"
        (is (= both (emails admin {"filters[mfa-secret][op]" "contains"
                                   "filters[mfa-secret][value]" "A"}))))
      (testing "nor as a plain parameter"
        (is (= both (emails admin {"password-hash" "nope"}))))
      (testing "a sort on it falls back to the default"
        (is (= both (emails admin {"sort" "password-hash"})))))))

(deftest ^:integration an-unknown-parameter-or-sort-is-ignored
  (with-admin
    (fn [admin]
      (is (= both (emails admin {"utm_source" "newsletter"})))
      (is (= both (emails admin {"sort" "nope"}))))))

(deftest ^:integration a-shown-field-both-tables-have-sorts-and-filters
  ;; auth_users and users both carry updated_at; SQLite refused it bare
  (with-admin
    (fn [admin]
      (is (= both (emails admin {"sort" "updated-at"})))
      (is (= both (emails admin {"sort" "created-at" "dir" "desc"})))
      (is (= ["bram@example.com"] (emails admin {"filters[role][op]" "eq"
                                                 "filters[role][value]" "admin"}))
          "a filter on a shown field still applies"))))

(deftest ^:integration a-count-follows-the-same-rules
  (with-admin
    (fn [admin]
      (is (= 2 (ports/count-entities admin :users {:password-hash "nope"})))
      (is (= 1 (ports/count-entities admin :users {:role "admin"}))))))

(deftest ^:integration a-boolean-reads-and-filters-as-one
  ;; SQLite stores it as 0/1: a deactivated user rendered as active, so saving
  ;; any other field reactivated them, and the active filter matched nothing
  (with-admin
    (fn [admin]
      (let [id-of  (fn [email] (:id (first (filter #(= email (:email %))
                                                   (:records (ports/list-entities admin :users {}))))))
            active (fn [email] (:active (ports/get-entity admin :users (id-of email))))]
        (user-ports/update-user *repo* (assoc (user-ports/find-user-by-email *repo* "ada@example.com") :active false))
        (is (false? (active "ada@example.com")))
        (is (true? (active "bram@example.com")))
        (is (= ["bram@example.com"] (emails admin {"filters[active][op]" "eq" "filters[active][value]" "true"})))
        (is (= ["ada@example.com"] (emails admin {"filters[active][op]" "eq" "filters[active][value]" "false"})))
        (is (= ["bram@example.com"] (emails admin {"active" "true"})))))))
