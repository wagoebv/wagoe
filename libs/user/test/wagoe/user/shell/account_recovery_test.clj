(ns wagoe.user.shell.account-recovery-test
  "An admin unlocks a locked-out user and turns off the second factor of one
   who lost it, audited with the admin as actor."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [support.test-db :as test-db]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.persistence :as persistence]
            [wagoe.user.shell.service :as user-service]
            [wagoe.user.shell.web-handlers :as web-handlers])
  (:import (java.time Instant)))

(def ^:private ^:dynamic *ctx* nil)

(use-fixtures :each
  (fn [f]
    (test-db/with-db "account_recovery"
      (fn [db-ctx]
        (persistence/initialize-user-schema! db-ctx)
        (binding [*ctx* db-ctx] (f))))))

(defn- service []
  (let [repo  (persistence/create-user-repository *ctx*)
        audit (persistence/create-audit-repository *ctx* {:default-limit 20 :max-limit 100})]
    {:repo  repo
     :audit audit
     :svc   (user-service/create-user-service repo (persistence/create-session-repository *ctx*) audit {} nil)}))

(defn- user! [repo email extra]
  (.create-user repo (merge {:email email :name email :role :user :active true
                             :password-hash "bcrypt+sha512$test"}
                            extra)))

(def ^:private admin {:id (random-uuid) :email "admin@example.com" :role :admin})

(defn- as-admin [f]
  (user-service/with-audit-context {:actor-id (:id admin) :actor-email (:email admin)} f))

(defn- last-entry [audit user-id]
  (first (:audit-logs (ports/find-audit-logs audit {:filter-target-user-id user-id
                                                    :sort-by :created-at :sort-direction :desc}))))

(deftest ^:integration an-admin-unlocks-a-locked-account
  (let [{:keys [repo audit svc]} (service)
        u (user! repo "locked@example.com" {:failed-login-count 5
                                            :lockout-until (.plusSeconds (Instant/now) 600)})]
    (is (true? (as-admin #(ports/unlock-user svc (:id u)))))
    (let [after (.find-user-by-id repo (:id u))]
      (is (= 0 (:failed-login-count after)))
      (is (nil? (:lockout-until after))))
    (let [entry (last-entry audit (:id u))]
      (is (= [:update (:id admin)] [(:action entry) (:actor-id entry)])))
    (testing "nothing to unlock"
      (is (false? (ports/unlock-user svc (:id u)))))
    (testing "an unknown user"
      (is (= :user-not-found (:type (ex-data (try (ports/unlock-user svc (random-uuid))
                                                  (catch Exception e e)))))))))

(deftest ^:integration an-admin-resets-a-users-second-factor
  (let [{:keys [repo audit svc]} (service)
        u (user! repo "mfa@example.com" {:mfa-enabled true :mfa-secret "SECRET"
                                         :mfa-backup-codes ["h1"]})]
    (is (true? (as-admin #(ports/reset-user-mfa svc (:id u)))))
    (let [after (.find-user-by-id repo (:id u))]
      (is (false? (boolean (:mfa-enabled after))))
      (is (nil? (:mfa-secret after))))
    (let [entry (last-entry audit (:id u))]
      (is (= [:mfa-disabled (:id admin) (:id u)] [(:action entry) (:actor-id entry) (:target-user-id entry)])))
    (is (false? (ports/reset-user-mfa svc (:id u))) "already off")))

(deftest ^:integration the-handlers-go-back-to-the-sessions-page
  (let [{:keys [repo svc]} (service)
        u    (user! repo "h@example.com" {:failed-login-count 2})
        resp ((web-handlers/unlock-user-handler svc {})
              {:request-method :post :uri (str "/web/users/" (:id u) "/unlock")
               :path-params {:id (str (:id u))}})]
    (is (= (str "/web/users/" (:id u) "/sessions") (get-in resp [:headers "HX-Redirect"])))
    (is (= 404 (:status ((web-handlers/reset-mfa-handler svc {})
                         {:request-method :post :path-params {:id (str (random-uuid))}}))))))

(deftest ^:unit only-a-platform-admin-may-and-not-on-their-own-account
  (let [target (str (random-uuid))]
    (is (web-handlers/account-recovery-allowed? admin target))
    (is (not (web-handlers/account-recovery-allowed? (assoc admin :tenant-id (random-uuid)) target))
        "a tenant admin could reach a platform admin's account")
    (is (not (web-handlers/account-recovery-allowed? (assoc admin :role :user) target)))
    (is (not (web-handlers/account-recovery-allowed? admin (str (:id admin))))
        "their own second factor goes off on the profile, with a password or code")))
