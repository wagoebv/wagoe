(ns wagoe.user.shell.email-change-test
  "An admin of a user's own tenant may not change the email of one who is a
   member of another tenant: they could then sign in as them through OIDC
   (BOU-1008)."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.tenant.ports :as tenant-ports]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.in-memory-repository-test :as mem]
            [wagoe.user.shell.service :as service]))

(def ^:private tenant-a #uuid "00000000-0000-0000-0000-00000000000a")
(def ^:private tenant-b #uuid "00000000-0000-0000-0000-00000000000b")

(defn- setup [elsewhere]
  (let [users (mem/->MemoryUserRepository (atom {}))
        svc   (service/create-user-service
               users (mem/->MemorySessionRepository (atom {})) (mem/->MemoryAuditRepository (atom [])) {} nil
               {:memberships (reify tenant-ports/ITenantMembershipsOfUser
                               (memberships-of-user [_ _] elsewhere))})
        user! (fn [email m] (ports/create-user users (merge {:email email :name email :role :user :active true
                                                             :password-hash "x"} m)))]
    {:svc   svc
     :ada   (user! "ada@a.example" {:tenant-id tenant-a})
     :admin (user! "admin@a.example" {:role :admin :tenant-id tenant-a})
     :root  (user! "root@example.com" {:role :admin})}))

(defn- change-email [svc actor target]
  (try (service/with-audit-context {:actor-id (:id actor) :actor-email (:email actor)}
         #(ports/update-user-profile svc {:id (:id target) :email "taken-over@evil.example"}))
       :changed
       (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(deftest ^:unit ^:security a-tenants-admin-cannot-change-the-email-of-a-member-elsewhere
  (let [{:keys [svc ada admin root]} (setup [{:tenant-id tenant-b :role :member}])]
    (is (= :member-of-another-tenant (change-email svc admin ada)))
    (is (= "ada@a.example" (:email (ports/get-user-by-id svc (:id ada)))) "unchanged")
    (testing "the user themself, or a platform admin, still can"
      (is (= :changed (change-email svc ada ada)))
      (is (= :changed (change-email svc root ada))))))

(deftest ^:unit ^:security an-invitation-elsewhere-counts-a-revoked-membership-does-not
  ;; An invited membership is theirs once they accept it
  (let [{:keys [svc ada admin]} (setup [{:tenant-id tenant-b :role :admin :status :invited}])]
    (is (= :member-of-another-tenant (change-email svc admin ada))))
  (let [{:keys [svc ada admin]} (setup [{:tenant-id tenant-b :role :admin :status :revoked}])]
    (is (= :changed (change-email svc admin ada)))))

(deftest ^:unit ^:security an-email-change-drops-the-oidc-links
  ;; One made while the address was someone else's must not outlive it
  (let [links (atom #{["https://idp" "s-1"]})
        ids   #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify ports/IUserIdentityRepository
          (find-identities-by-user [_ _] (map (fn [[i s]] {:issuer i :subject s}) @links))
          (unlink-identity [_ i s] (swap! links disj [i s]) true))
        {:keys [svc ada]} (setup [])
        svc (assoc svc :identities ids)]
    (is (= :changed (change-email svc ada ada)))
    (is (empty? @links))))

(deftest ^:unit a-tenants-admin-still-changes-the-email-of-their-own-tenants-users
  (let [{:keys [svc ada admin]} (setup [{:tenant-id tenant-a :role :member}])]
    (is (= :changed (change-email svc admin ada)))))
