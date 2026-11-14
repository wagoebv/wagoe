(ns wagoe.user.shell.tenant-members-test
  "One identity across tenants (BOU-995): an email another tenant holds is
   invited without saying so, and joins only by accepting; a tenant lists its
   members; no tenant's admin changes someone in another tenant too; sign-in
   asks which tenant."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.platform.shell.adapters.database.factory :as factory]
            [wagoe.tenant.ports :as tenant-ports]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.auth :as auth]
            [wagoe.user.shell.in-memory-repository-test :as mem]
            [wagoe.user.shell.interceptors :as interceptors]
            [wagoe.user.shell.persistence :as persistence]
            [wagoe.user.shell.service :as service]
            [wagoe.user.shell.tenant-scope :as tenant-scope]
            [wagoe.user.shell.web-handlers :as web-handlers])
  (:import [java.util UUID]))

(def ^:private tenant-a #uuid "a0000000-0000-0000-0000-00000000000a")
(def ^:private tenant-b #uuid "b0000000-0000-0000-0000-00000000000b")

(defn- membership-service
  "The tenant module's membership service over an atom, its unique
   (tenant, user) key kept."
  [state]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify
    tenant-ports/ITenantMembershipsOfUser
    (memberships-of-user [_ user-id]
      (filterv #(= (str user-id) (str (:user-id %))) @state))
    tenant-ports/ITenantMembershipService
    (invite-user [_ tenant-id user-id role]
      (when (some #(and (= (str tenant-id) (str (:tenant-id %))) (= (str user-id) (str (:user-id %)))) @state)
        (throw (ex-info "Membership already exists" {:type :conflict})))
      (let [m {:id (UUID/randomUUID) :tenant-id tenant-id :user-id user-id :role role :status :invited}]
        (swap! state conj m)
        m))
    (get-membership [_ id]
      (or (first (filter #(= (str id) (str (:id %))) @state))
          (throw (ex-info "Membership not found" {:type :not-found}))))
    (accept-invitation [_ id]
      (swap! state (partial mapv #(cond-> % (= (str id) (str (:id %))) (assoc :status :active)))))
    (list-tenant-members [_ tenant-id {:keys [status]}]
      (filterv #(and (= (str tenant-id) (str (:tenant-id %))) (or (nil? status) (= status (:status %)))) @state))))

(defn- user-service
  [memberships]
  (let [users    (mem/->MemoryUserRepository (atom {}))
        sessions (mem/->MemorySessionRepository (atom {}))]
    (service/create-user-service users sessions (mem/->MemoryAuditRepository (atom []))
                                 {}
                                 (auth/create-authentication-service users sessions nil {})
                                 {:memberships (membership-service memberships)})))

(defn- register! [svc email tenant-id & [role]]
  (ports/register-user svc (cond-> {:email email :name "Someone" :password "Correct-Horse-9" :role (or role :user)}
                             tenant-id (assoc :tenant-id tenant-id))))

(defn- thrown-type [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(deftest ^:integration ^:security creating-a-user-answers-the-same-whether-the-email-exists-in-another-tenant
  ;; It said "already exists" for another tenant's email (BOU-992 pinned that)
  (let [memberships (atom [])
        svc         (user-service memberships)
        theirs      (register! svc "b@example.com" tenant-b)
        hash-before (:password-hash (ports/find-user-by-email (:user-repository svc) "b@example.com"))
        admin-of-a  {:id (UUID/randomUUID) :role :admin :tenant-id tenant-a}
        create      (fn [email]
                      ((web-handlers/create-user-htmx-handler svc nil {})
                       {:user admin-of-a
                        :form-params {"name" "New" "email" email "password" "Other-Horse-77" "role" "user"}}))
        free        (create "free@example.com")
        taken       (create "b@example.com")]
    (is (= (:status free) (:status taken)) "the same answer")
    (is (= (get-in free [:headers "HX-Redirect"]) (get-in taken [:headers "HX-Redirect"])))
    (is (not (re-find #"(?i)already|taken|in use" (str (:body taken)))))
    (testing "the API answer has the same shape"
      (reset! memberships [])
      (is (= (set (keys (register! svc "free2@example.com" tenant-a)))
             (set (keys (register! svc "b@example.com" tenant-a))))))
    (testing "the identity is invited, not joined, and kept its password"
      (is (= [:invited] (map :status @memberships)))
      (is (= #{(str tenant-b)} (ports/tenant-ids svc theirs)))
      (is (= hash-before (:password-hash (ports/find-user-by-email (:user-repository svc) "b@example.com")))))
    (testing "an email this tenant already has, in any state, is said to be"
      (is (= 400 (:status (create "b@example.com"))) "an open invitation")
      (is (= 400 (:status (create "free@example.com"))) "its own user"))))

(deftest ^:integration ^:security a-tenant-is-joined-only-by-accepting-ones-own-invitation
  (let [memberships (atom [])
        svc         (user-service memberships)
        theirs      (register! svc "b@example.com" tenant-b)
        stranger    (register! svc "c@example.com" tenant-b)
        _           (register! svc "b@example.com" tenant-a)
        [invite]    (ports/invitations svc theirs)]
    (is (= (str tenant-a) (:tenant-id invite)))
    (is (= :forbidden (thrown-type #(ports/accept-invitation svc stranger (:id invite)))) "not someone else's")
    (ports/accept-invitation svc theirs (:id invite))
    (is (= #{(str tenant-a) (str tenant-b)} (ports/tenant-ids svc theirs)))
    (is (empty? (ports/invitations svc theirs)))))

(deftest ^:integration ^:security no-tenants-admin-changes-a-user-in-another-tenant-too
  ;; Also through the admin UI, which calls the service, not the routes
  (let [memberships (atom [])
        svc         (user-service memberships)
        admin-of-a  (register! svc "admin-a@example.com" tenant-a :admin)
        admin-of-b  (register! svc "admin-b@example.com" tenant-b :admin)
        platform    (register! svc "root@example.com" nil :admin)
        shared      (register! svc "b@example.com" tenant-b)
        join!       (fn [user]
                      (register! svc (:email user) tenant-a)
                      (let [[i] (ports/invitations svc user)] (ports/accept-invitation svc user (:id i))))
        as          (fn [actor f] (thrown-type #(service/with-audit-context {:actor-id (:id actor)} f)))]
    (join! shared)
    (is (= :forbidden (as admin-of-a #(ports/update-user-profile svc {:id (:id shared) :name "Changed"}))))
    (is (= :forbidden (as admin-of-b #(ports/deactivate-user svc (:id shared)))) "not even their own tenant's")
    (is (= :forbidden (as admin-of-b #(ports/delete-user svc (:id shared)))))
    (is (nil? (as shared #(ports/update-user-profile svc {:id (:id shared) :name "Mine"}))) "they themselves")
    (is (nil? (as platform #(ports/update-user-profile svc {:id (:id shared) :name "Root's"}))) "a platform admin")
    (testing "a platform admin who joined a tenant is no tenant admin's to change"
      (join! platform)
      (is (= :forbidden (as admin-of-a #(ports/update-user-profile svc {:id (:id platform) :email "mine@evil.test"}))))
      (is (= :forbidden (as admin-of-a #(ports/delete-user svc (:id platform))))))))

(deftest ^:integration a-tenants-listing-holds-its-members
  (let [ctx (factory/db-context (factory/h2-config (str "mem:tenant_members_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")))]
    (try
      (persistence/initialize-user-schema! ctx)
      (let [repo     (persistence/create-user-repository ctx)
            sessions (persistence/create-session-repository ctx)
            add!     #(ports/create-user repo {:email %1 :name %1 :role :user :active true :password-hash "x" :tenant-id %2})
            _        (add! "a@example.com" tenant-a)
            member   (add! "b@example.com" tenant-b)
            _        (add! "c@example.com" tenant-b)
            svc      (service/create-user-service repo sessions (mem/->MemoryAuditRepository (atom [])) {}
                                                  (auth/create-authentication-service repo sessions nil {})
                                                  {:memberships (membership-service
                                                                 (atom [{:id (UUID/randomUUID) :tenant-id tenant-a
                                                                         :user-id (:id member) :status :active}]))})]
        (is (= #{"a@example.com" "b@example.com"}
               (set (map :email (:users (ports/list-users svc {:filter-tenant-id tenant-a})))))))
      (finally (factory/close-db-context! ctx)))))

(defn- service-knowing
  "A user service that knows `users` by id and their tenants."
  [users]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify
    ports/IUserService
    (get-user-by-id [_ id] (get users id))
    ports/ITenantMembers
    (tenant-ids [_ user] (:in user))))

(deftest ^:unit ^:security a-member-from-another-tenant-is-seen-but-not-changed
  (let [member  {:id (UUID/randomUUID) :tenant-id tenant-b :in #{(str tenant-a) (str tenant-b)}}
        own     {:id (UUID/randomUUID) :tenant-id tenant-a :in #{(str tenant-a)}}
        users   {(:id member) member (:id own) own}
        call    (fn [method caller target & [opts]]
                  (:status (((tenant-scope/wrap-target-user (service-knowing users) opts) (constantly {:status 200}))
                            {:request-method method :user caller :path-params {:id (str (:id target))}})))
        admin-of-a {:id (UUID/randomUUID) :role :admin :tenant-id tenant-a}]
    (is (= 200 (call :get admin-of-a member)) "listed and seen in A")
    (is (= 403 (call :put admin-of-a member)))
    (is (= 403 (call :get admin-of-a member {:sessions? true})) "their sessions are not A's to see")
    (is (= 200 (call :put admin-of-a own)) "a user of one tenant stays theirs")
    (is (= 200 (call :put {:id (:id member) :role :user :tenant-id tenant-b} member)) "the user themselves")
    (is (= 200 (call :put {:id (UUID/randomUUID) :role :admin} member)) "a platform admin")))

(defn- signed-in-service
  "A user service whose sign-in succeeds for a user in `tenants`, invited to `invited`."
  [tenants & [invited]]
  (let [user {:id (UUID/randomUUID) :email "x@example.com" :role :user :in tenants}]
    #_{:clj-kondo/ignore [:missing-protocol-method]}
    (reify
      ports/IUserService
      (authenticate-user [_ _]
        {:authenticated true :user user :session {:session-token "token"}})
      ports/ITenantMembers
      (tenant-ids [_ u] (:in u))
      (tenants [_ u] (mapv #(hash-map :id % :name (str "Tenant " %)) (sort (:in u))))
      (invitations [_ _] (vec invited)))))

(defn- sign-in [svc]
  ((web-handlers/login-submit-handler svc {})
   {:form-params {"email" "x@example.com" "password" "Correct-Horse-9"} :headers {}}))

(deftest ^:unit sign-in-asks-which-tenant-when-there-are-several-or-an-invitation
  (let [location #(get-in (sign-in %) [:headers "Location"])]
    (is (str/starts-with? (location (signed-in-service #{(str tenant-a) (str tenant-b)})) "/web/choose-tenant?return-to="))
    (is (str/starts-with? (location (signed-in-service #{(str tenant-a)} [{:id "m" :tenant-id (str tenant-b)}]))
                          "/web/choose-tenant"))
    (is (not (str/starts-with? (location (signed-in-service #{(str tenant-a)})) "/web/choose-tenant")))
    (is (= 0 (get-in (sign-in (signed-in-service #{(str tenant-a)})) [:cookies "wagoe-tenant" :max-age]))
        "the last sign-in's choice is cleared")))

(deftest ^:unit the-choice-page-lists-tenants-and-invitations
  (let [user {:id (UUID/randomUUID) :role :user :in #{(str tenant-a) (str tenant-b)}}
        body (str (:body ((web-handlers/choose-tenant-page-handler
                           (signed-in-service nil [{:id "inv-1" :tenant-id "t-c" :name "Gamma"}]) {})
                          {:user user :headers {}})))]
    (is (str/includes? body (str "Tenant " tenant-a)))
    (is (str/includes? body "Gamma"))
    (is (str/includes? body "inv-1") "joined by choosing it")))

(deftest ^:unit ^:security a-tenant-is-chosen-only-among-ones-own
  (let [svc    (signed-in-service nil)
        user   {:id (UUID/randomUUID) :role :user :in #{(str tenant-a) (str tenant-b)}}
        choose #((web-handlers/choose-tenant-submit-handler svc {})
                 {:user user :headers {} :form-params {"tenant-id" (str %) "return-to" "/web/admin"}})
        mine   (choose tenant-b)]
    (is (= 303 (:status mine)))
    (is (= (str tenant-b) (get-in mine [:cookies "wagoe-tenant" :value])))
    (is (= 403 (:status (choose (UUID/randomUUID)))))))

(deftest ^:unit logout-forgets-the-tenant-chosen
  (let [svc #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify ports/IUserService (logout-user [_ _] true))]
    (is (= 0 (get-in ((web-handlers/logout-handler svc {}) {:cookies {"session-token" {:value "t"}} :headers {}})
                     [:cookies "wagoe-tenant" :max-age])))))

(deftest ^:unit the-api-sign-in-names-the-tenants-of-one-in-several
  (let [user    {:id (UUID/randomUUID) :email "x@example.com"}
        respond #(-> ((:enter interceptors/format-session-creation-response)
                      {:system  {:user-service (signed-in-service nil)}
                       :session {:authenticated true :user (assoc user :in %) :session {}}})
                     :response :body :tenants)]
    (is (= 2 (count (respond #{(str tenant-a) (str tenant-b)}))))
    (is (nil? (respond #{(str tenant-a)})) "one tenant: nothing to choose")))

(deftest ^:integration an-invitation-is-audited
  (let [audit (atom [])
        users (mem/->MemoryUserRepository (atom {}))
        sessions (mem/->MemorySessionRepository (atom {}))
        svc   (service/create-user-service users sessions (mem/->MemoryAuditRepository audit) {}
                                           (auth/create-authentication-service users sessions nil {})
                                           {:memberships (membership-service (atom []))})
        actor (UUID/randomUUID)
        theirs (register! svc "b@example.com" tenant-b)]
    (service/with-audit-context {:actor-id actor :actor-email "admin@a.test"}
      #(register! svc "b@example.com" tenant-a))
    (is (some #(and (= (:id theirs) (:target-user-id %)) (= actor (:actor-id %))
                    (= {:invited-to-tenant (str tenant-a)} (:changes %)))
              @audit))))
