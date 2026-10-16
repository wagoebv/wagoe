(ns wagoe.admin.shell.user-writer-test
  "BOU-665: the shipped users entity is written through the user service, so
   an admin edit is audited, ends sessions where it should and keeps an admin."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [support.test-db :as test-db]
            [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.http.handlers.crud :as crud]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.shell.service :as service]
            [wagoe.admin.shell.user-writer :as user-writer]
            [wagoe.observability.errors.shell.adapters.no-op :as error-reporting-no-op]
            [wagoe.observability.logging.shell.adapters.no-op :as logging-no-op]
            [wagoe.test.logging :refer [with-silent-logging]]
            [wagoe.user.ports :as user-ports]
            [wagoe.user.shell.persistence :as persistence]
            [wagoe.user.shell.service :as user-service])
  (:import [java.time Instant]
           [java.util UUID]))

^{:kaocha.testable/meta {:integration true :admin true}}

(def ^:private shipped
  (edn/read-string (slurp "libs/tools/resources/wagoe/tools/admin/users.edn")))

(def ^:private ^:dynamic *ctx* nil)

(use-fixtures :each
  (fn [f]
    (test-db/with-db "admin_user_writer"
      (fn [db-ctx]
        (persistence/initialize-user-schema! db-ctx)
        (binding [*ctx* db-ctx] (f))))))

(defn- system []
  (let [repo     (persistence/create-user-repository *ctx*)
        sessions (persistence/create-session-repository *ctx*)
        audit    (persistence/create-audit-repository *ctx* {:default-limit 20 :max-limit 100})
        users    (user-service/create-user-service repo sessions audit {} nil)
        config   {:entity-discovery {:mode :allowlist :allowlist #{:users}}
                  :entities         shipped
                  :pagination       {:default-page-size 20 :max-page-size 200}}
        schema   (schema-repo/create-schema-repository *ctx* config)]
    {:repo     repo
     :sessions sessions
     :users    users
     :schema   schema
     :config   config
     :admin    (service/create-admin-service
                *ctx* schema
                (logging-no-op/create-logging-component {})
                (error-reporting-no-op/create-error-reporting-component {})
                config nil nil
                {:user (user-writer/create-user-writer users)})}))

(defn- user! [{:keys [repo sessions]} email role]
  (let [user (.create-user repo {:email email :name email :role role :active true
                                 :password-hash "bcrypt+sha512$test"})]
    (.create-session sessions {:user-id       (:id user)
                               :session-token (str "token-" (UUID/randomUUID))
                               :expires-at    (.plusSeconds (Instant/now) 3600)})
    user))

(defn- live-sessions [{:keys [sessions]} user]
  (count (.find-sessions-by-user sessions (:id user))))

(defn- as
  "Call `f` with `user` as the actor, as the authentication middleware does."
  [user f]
  (user-service/with-audit-context (user-service/request-audit-context {:user user}) f))

(defn- refusal [f]
  (try (with-silent-logging (f)) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest ^:integration role-change-is-audited-and-ends-sessions
  (let [{:keys [admin users] :as sys} (system)
        boss (user! sys "boss@example.com" :admin)
        u    (user! sys "u@example.com" :user)]
    (as boss #(ports/update-entity admin :users (:id u) {:role "viewer"}))
    (is (= :viewer (:role (user-ports/get-user-by-id users (:id u)))))
    (is (zero? (live-sessions sys u)) "a role change ends the user's sessions (BOU-191)")
    (let [entry (first (user-ports/get-audit-logs-for-user users (:id u) {}))]
      (is (= :update (:action entry)))
      (is (= (:id boss) (:actor-id entry)) "the admin is the actor"))))

(deftest ^:integration deactivating-ends-sessions
  (let [{:keys [admin] :as sys} (system)
        _  (user! sys "boss@example.com" :admin)
        u  (user! sys "u@example.com" :user)
        u2 (user! sys "u2@example.com" :user)]
    (testing "unchecking active"
      (ports/update-entity admin :users (:id u) {:active false})
      (is (zero? (live-sessions sys u))))
    (testing "deleting"
      (is (true? (ports/delete-entity admin :users (:id u2))))
      (is (zero? (live-sessions sys u2))))))

(deftest ^:integration the-last-admin-keeps-admin-rights
  (let [{:keys [admin users] :as sys} (system)
        boss (user! sys "boss@example.com" :admin)]
    (doseq [[what f] {"demote"     #(ports/update-entity admin :users (:id boss) {:role "user"})
                      "deactivate" #(ports/update-entity admin :users (:id boss) {:active false})
                      "inline"     #(ports/update-entity-field admin :users (:id boss) :role "user")
                      "delete"     #(ports/delete-entity admin :users (:id boss))
                      "bulk"       #(ports/bulk-delete-entities admin :users [(:id boss)])}]
      (testing what
        (is (= [:conflict :last-admin] ((juxt :type :reason) (refusal f))))))
    (is (= :admin (:role (user-ports/get-user-by-id users (:id boss)))))
    (is (= 1 (live-sessions sys boss)))))

(deftest ^:integration an-admin-cannot-remove-their-own-rights
  (let [{:keys [admin users] :as sys} (system)
        boss  (user! sys "boss@example.com" :admin)
        other (user! sys "other@example.com" :admin)]
    (is (= :own-admin-rights
           (:reason (refusal #(as boss (fn [] (ports/update-entity admin :users (:id boss) {:role "user"})))))))
    (is (= :own-admin-rights
           (:reason (refusal #(as boss (fn [] (ports/delete-entity admin :users (:id boss))))))))
    (testing "another admin can be demoted while one remains"
      (as boss #(ports/update-entity admin :users (:id other) {:role "user"}))
      (is (= :user (:role (user-ports/get-user-by-id users (:id other))))))))

(deftest ^:integration fields-the-user-module-writes-are-read-only
  (let [{:keys [admin repo] :as sys} (system)
        u (user! sys "u@example.com" :user)]
    (is (every? (:readonly-fields (:users shipped)) [:tenant-id :login-count :last-login]))
    (ports/update-entity admin :users (:id u) {:name "Renamed" :login-count 99})
    (let [stored (.find-user-by-id repo (:id u))]
      (is (= "Renamed" (:name stored)))
      (is (not= 99 (:login-count stored))))))

(defn- listed [{:keys [admin]} user]
  (->> (:records (ports/list-entities admin :users {:limit 50}))
       (filter #(= (str (:id user)) (str (:id %))))
       first))

(deftest ^:integration a-deactivated-user-stays-listed-and-can-be-reactivated
  ;; Deactivation sets active only, so the user stays in the list (BOU-681).
  (let [{:keys [admin schema config] :as sys} (system)
        boss    (user! sys "boss@example.com" :admin)
        u       (user! sys "u@example.com" :user)
        handler (crud/update-entity-handler admin schema config)
        save    #(handler {:request-method :put
                           :uri            (str "/web/admin/users/" (:id u))
                           :user           boss
                           :path-params    {:entity "users" :id (str (:id u))}
                           :form-params    {"active" %}})
        resp    (save "false")]
    (is (= 200 (:status resp)))
    (is (nil? (get-in resp [:headers "HX-Redirect"])) "the form again, not the list")
    (is (zero? (live-sessions sys u)))
    (is (false? (:active (listed sys u))) "listed, as inactive")
    (testing "a delete removes the user from the list"
      (let [u2 (user! sys "u2@example.com" :user)]
        (is (true? (ports/delete-entity admin :users (:id u2))))
        (is (nil? (listed sys u2)))
        (is (zero? (live-sessions sys u2)))))
    (testing "reactivated from the list"
      (is (= 200 (:status (save "true"))))
      (is (true? (:active (listed sys u)))))))

(deftest ^:integration a-refused-save-shows-the-form-with-409
  (let [{:keys [admin schema config] :as sys} (system)
        boss    (user! sys "boss@example.com" :admin)
        handler (crud/update-entity-handler admin schema config)
        resp    (with-silent-logging
                  (handler {:request-method :put
                            :uri            (str "/web/admin/users/" (:id boss))
                            :user           (assoc boss :role :admin)
                            :path-params    {:entity "users" :id (str (:id boss))}
                            :form-params    {"role" "user"}}))]
    (is (= 409 (:status resp)))
    (is (str/includes? (str (:body resp)) "refused-last-admin")
        "the refusal is named in the form (the key, without a catalogue)")))
