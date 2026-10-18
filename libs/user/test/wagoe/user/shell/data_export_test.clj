(ns wagoe.user.shell.data-export-test
  "A user's data export and the audit-log retention policy, on the engine
   WAGOE_TEST_DB names."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [reitit.ring :as ring]
            [support.test-db :as test-db]
            [wagoe.platform.ports.privacy :as privacy]
            [wagoe.platform.shell.privacy :as platform-privacy]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.data-export :as sut]
            [wagoe.user.shell.group-persistence :as group-persistence]
            [wagoe.user.shell.http :as http]
            [wagoe.user.shell.identity-persistence :as identity-persistence]
            [wagoe.user.shell.persistence :as persistence]
            [wagoe.user.shell.service :as user-service])
  (:import [java.time Duration Instant]))

(defn- with-repos [f]
  (test-db/with-db "user_data_export"
    (fn [ctx]
      (persistence/initialize-user-schema! ctx)
      (let [repos {:users      (persistence/create-user-repository ctx)
                   :sessions   (persistence/create-session-repository ctx)
                   :audit      (persistence/create-audit-repository ctx {:default-limit 20 :max-limit 100})
                   :identities (identity-persistence/create-identity-repository ctx)
                   :groups     (group-persistence/create-group-repository ctx)}]
        (f (assoc repos :exporter (sut/map->UserDataExporter
                                   {:user-repository     (:users repos)
                                    :session-repository  (:sessions repos)
                                    :audit-repository    (:audit repos)
                                    :identity-repository (:identities repos)
                                    :group-repository    (:groups repos)})))))))

(defn- user! [users email role]
  (ports/create-user users {:email email :name email :role role :active true
                            :password-hash "secret-hash" :mfa-secret "totp-secret"}))

(defn- audit! [audit actor target & [created-at]]
  (ports/create-audit-log audit (cond-> {:action :update :actor-id (:id actor) :actor-email (:email actor)
                                         :target-user-id (:id target) :target-user-email (:email target)
                                         :result :success}
                                  created-at (assoc :created-at created-at))))

(deftest ^:integration a-user-export-holds-their-data-and-no-credentials
  (with-repos
    (fn [{:keys [users sessions audit identities groups exporter]}]
      (let [ada (user! users "ada@example.com" :user)
            bob (user! users "bob@example.com" :user)
            _   (ports/create-session sessions {:user-id (:id ada) :session-token "tok-ada"
                                                :expires-at (.plusSeconds (Instant/now) 3600)})
            _   (ports/link-identity identities {:user-id (:id ada) :provider "google" :email "ada@example.com"
                                                 :issuer "https://accounts.google.com" :subject "s-1"})
            g   (ports/insert-group groups {:name "Finance"})
            _   (ports/insert-member groups (:id g) (:id ada))
            a1  (audit! audit ada ada)
            a2  (audit! audit ada bob)
            a3  (audit! audit bob ada)
            _   (audit! audit bob bob)
            {:keys [profile sessions identities groups audit-log]} (privacy/export-data exporter {:type :user :id (:id ada)})]
        (is (= "ada@example.com" (:email profile)))
        (is (not-any? #(contains? profile %) [:password-hash :mfa-secret :mfa-backup-codes]))
        (is (= 1 (count sessions)))
        (is (not-any? :session-token sessions))
        (is (= ["s-1"] (map :subject identities)))
        (is (= ["Finance"] (map :name groups)))
        (is (= #{(:id a1) (:id a2) (:id a3)} (set (map :id audit-log)))
            "as target or actor, each once, and not bob's own")
        (is (not (re-find #"bob@" (pr-str audit-log))) "nothing that identifies bob")
        (is (= "ada@example.com" (:actor-email (first (filter #(= (:id a1) (:id %)) audit-log)))))

        (testing "a string id works, and an unknown or other-type subject has nothing"
          (is (= (:id ada) (get-in (privacy/export-data exporter {:type :user :id (str (:id ada))}) [:profile :id])))
          (is (nil? (privacy/export-data exporter {:type :user :id (random-uuid)})))
          (is (nil? (privacy/export-data exporter {:type :user :id "not-a-uuid"})))
          (is (nil? (privacy/export-data exporter {:type :tenant :id "acme"}))))

        (testing "the audit trail is read past one page"
          (with-redefs-fn {#'sut/page-size 1}
            #(is (= 3 (count (:audit-log (privacy/export-data exporter {:type :user :id (:id ada)})))))))))))

(defn- app [users audit exporter]
  (let [routes (http/export-routes (user-service/->UserService users nil audit {} nil)
                                   (platform-privacy/create-data-export {:user exporter})
                                   nil)]
    (ring/ring-handler (ring/router [["/api/v1" (:api routes)] ["/web" (:web routes)]]))))

(deftest ^:integration ^:security users-download-their-own-data-and-admins-anyones
  (with-repos
    (fn [{:keys [users audit exporter]}]
      (let [ada   (user! users "ada@example.com" :user)
            bob   (user! users "bob@example.com" :user)
            admin (user! users "admin@example.com" :admin)
            app   (app users audit exporter)
            fetch (fn [user uri] (app {:request-method :get :uri uri :user user :headers {}}))]
        (doseq [uri ["/api/v1/me/export" "/web/profile/export"]]
          (testing uri
            (let [r    (fetch ada uri)
                  body (json/parse-string (:body r))]
              (is (= 200 (:status r)))
              (is (re-find #"attachment" (get-in r [:headers "Content-Disposition"])))
              (is (= "wagoe.data-export/1" (get body "format")))
              (is (= "ada@example.com" (get-in body ["sections" "user" "profile" "email"])))
              (is (contains? (get-in body ["sections" "user"]) "auditLog") "keys are camelCase"))))

        (testing "signed out, nothing"
          (is (= 401 (:status (fetch nil "/api/v1/me/export")))))

        (testing "another user's data is an admin's to export"
          (is (= 403 (:status (fetch ada (str "/api/v1/users/" (:id bob) "/export")))))
          (is (= 403 (:status (fetch (assoc admin :tenant-id "acme") (str "/api/v1/users/" (:id bob) "/export"))))
              "a tenant's admin is not a platform admin")
          (let [r (fetch admin (str "/api/v1/users/" (:id bob) "/export"))]
            (is (= 200 (:status r)))
            (is (= "bob@example.com" (get-in (json/parse-string (:body r)) ["sections" "user" "profile" "email"])))))

        (testing "an unknown user is not found, not an empty export"
          (is (= :not-found
                 (:type (ex-data (try (fetch admin (str "/api/v1/users/" (random-uuid) "/export"))
                                      (catch clojure.lang.ExceptionInfo e e)))))))))))

(deftest ^:integration audit-log-retention-deletes-only-older-entries
  (with-repos
    (fn [{:keys [users audit]}]
      (let [ada    (user! users "ada@example.com" :user)
            now    (Instant/now)
            _      (audit! audit ada ada (.minus now (Duration/ofDays 800)))
            recent (audit! audit ada ada (.minus now (Duration/ofDays 10)))
            policy (sut/->AuditLogRetention audit)]
        (is (= 1 (privacy/purge-before! policy (.minus now (Duration/ofDays 730)))))
        (is (= [(:id recent)] (map :id (:audit-logs (ports/find-audit-logs audit {:limit 10})))))
        (is (zero? (privacy/purge-before! policy (.minus now (Duration/ofDays 730)))))))))
