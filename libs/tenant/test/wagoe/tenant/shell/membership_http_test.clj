(ns wagoe.tenant.shell.membership-http-test
  "Contract tests for membership HTTP endpoints."
  (:require [wagoe.tenant.shell.membership-http :as sut]
            [wagoe.tenant.ports :as ports]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import (java.time Instant)
           (java.util UUID)))

^{:kaocha.testable/meta {:contract true :tenant true}}

;; =============================================================================
;; Mock service
;; =============================================================================

(def tenant-id-1 #uuid "10000000-0000-0000-0000-000000000001")
(def user-id-1   #uuid "20000000-0000-0000-0000-000000000001")
(def member-id-1 #uuid "30000000-0000-0000-0000-000000000001")
(def member-id-2 #uuid "30000000-0000-0000-0000-000000000002")

(def sample-invited
  {:id          member-id-1
   :tenant-id   tenant-id-1
   :user-id     user-id-1
   :role        :member
   :status      :invited
   :invited-at  (Instant/parse "2026-01-01T00:00:00Z")
   :accepted-at nil
   :created-at  (Instant/parse "2026-01-01T00:00:00Z")
   :updated-at  nil})

(def sample-active
  (assoc sample-invited
         :id          member-id-2
         :status      :active
         :accepted-at (Instant/parse "2026-01-02T00:00:00Z")
         :updated-at  (Instant/parse "2026-01-02T00:00:00Z")))

(defrecord MockMembershipService [memberships]
  ports/ITenantMembershipService

  (invite-user [_ tenant-id user-id role]
    (let [existing (some #(and (= tenant-id (:tenant-id %))
                               (= user-id (:user-id %)) %)
                         (vals @memberships))]
      (when existing
        (throw (ex-info "Membership already exists for this user in tenant"
                        {:type :conflict})))
      (let [m {:id          (UUID/randomUUID)
               :tenant-id   tenant-id
               :user-id     user-id
               :role        role
               :status      :invited
               :invited-at  (Instant/now)
               :accepted-at nil
               :created-at  (Instant/now)
               :updated-at  nil}]
        (swap! memberships assoc (:id m) m)
        m)))

  (bootstrap-open? [_ tenant-id]
    (empty? (filter #(= tenant-id (:tenant-id %)) (vals @memberships))))

  (bootstrap-first-member [_ tenant-id user-id role]
    (when-not (.bootstrap-open? ^wagoe.tenant.ports.ITenantMembershipService _ tenant-id)
      (throw (ex-info "Tenant already has members" {:type :conflict})))
    (let [m {:id          (UUID/randomUUID)
             :tenant-id   tenant-id
             :user-id     user-id
             :role        role
             :status      :active
             :invited-at  (Instant/now)
             :accepted-at (Instant/now)
             :created-at  (Instant/now)
             :updated-at  nil}]
      (swap! memberships assoc (:id m) m)
      m))

  (accept-invitation [_ membership-id]
    (if-let [m (get @memberships membership-id)]
      (if (= :invited (:status m))
        (let [updated (assoc m :status :active
                             :accepted-at (Instant/now)
                             :updated-at  (Instant/now))]
          (swap! memberships assoc membership-id updated)
          updated)
        (throw (ex-info "Membership is not in invited status"
                        {:type :validation-error})))
      (throw (ex-info "Membership not found" {:type :not-found}))))

  (update-member-role [_ membership-id role]
    (if-let [m (get @memberships membership-id)]
      (let [updated (assoc m :role role :updated-at (Instant/now))]
        (swap! memberships assoc membership-id updated)
        updated)
      (throw (ex-info "Membership not found" {:type :not-found}))))

  (suspend-member [_ membership-id]
    (if-let [m (get @memberships membership-id)]
      (let [updated (assoc m :status :suspended :updated-at (Instant/now))]
        (swap! memberships assoc membership-id updated)
        updated)
      (throw (ex-info "Membership not found" {:type :not-found}))))

  (revoke-member [_ membership-id]
    (if-let [m (get @memberships membership-id)]
      (let [updated (assoc m :status :revoked :updated-at (Instant/now))]
        (swap! memberships assoc membership-id updated)
        updated)
      (throw (ex-info "Membership not found" {:type :not-found}))))

  (get-membership [_ membership-id]
    (if-let [m (get @memberships membership-id)]
      m
      (throw (ex-info "Membership not found" {:type :not-found}))))

  (get-active-membership [_ user-id tenant-id]
    (->> (vals @memberships)
         (filter #(and (= user-id (:user-id %))
                       (= tenant-id (:tenant-id %))
                       (= :active (:status %))))
         first))

  (list-tenant-members [_ tenant-id {:keys [limit offset]
                                     :or   {limit 50 offset 0}}]
    (->> (vals @memberships)
         (filter #(= tenant-id (:tenant-id %)))
         (drop offset)
         (take limit)
         vec)))

(def ^:dynamic *mock-service* nil)

(defn setup! []
  (let [memberships (atom {member-id-1 sample-invited
                           member-id-2 sample-active})]
    (alter-var-root #'*mock-service* (constantly (->MockMembershipService memberships)))))

(defn teardown! []
  (alter-var-root #'*mock-service* (constantly nil)))

(use-fixtures :each
  (fn [f]
    (setup!)
    (f)
    (teardown!)))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn parse-body [response]
  (when-let [body (:body response)]
    (json/parse-string body true)))

(defn make-request
  ([method path-params]
   (make-request method path-params nil))
  ([method path-params body]
   {:request-method method
    :path-params    path-params
    :params         {}
    :body-params    body}))

(defn- as-invitee [request]
  (assoc request :user {:id user-id-1}))

;; =============================================================================
;; invite-user-handler
;; =============================================================================

(deftest ^:contract invite-user-handler-test
  (testing "201 on valid invite"
    (let [handler  (sut/invite-user-handler *mock-service*)
          request  (make-request :post
                                 {:tenant-id (str tenant-id-1)}
                                 {:userId (str (UUID/randomUUID)) :role "admin"})
          response (handler request)
          body     (parse-body response)]
      (is (= 201 (:status response)))
      (is (= "invited" (name (:status body))))))

  (testing "400 for invalid tenant UUID"
    (let [handler  (sut/invite-user-handler *mock-service*)
          request  (make-request :post {:tenant-id "not-a-uuid"})
          response (handler request)]
      (is (= 400 (:status response)))))

  (testing "400 for missing userId"
    (let [handler  (sut/invite-user-handler *mock-service*)
          request  (make-request :post
                                 {:tenant-id (str tenant-id-1)}
                                 {:role "member"})
          response (handler request)]
      (is (= 400 (:status response)))))

  (testing "400 for invalid role"
    (let [handler  (sut/invite-user-handler *mock-service*)
          request  (make-request :post
                                 {:tenant-id (str tenant-id-1)}
                                 {:userId (str (UUID/randomUUID)) :role "superuser"})
          response (handler request)]
      (is (= 400 (:status response)))))

  (testing "409 when membership already exists"
    (let [handler  (sut/invite-user-handler *mock-service*)
          request  (make-request :post
                                 {:tenant-id (str tenant-id-1)}
                                 {:userId (str user-id-1) :role "member"})
          response (handler request)]
      (is (= 409 (:status response))))))

;; =============================================================================
;; list-members-handler
;; =============================================================================

(deftest ^:contract list-members-handler-test
  (testing "200 with list of members"
    (let [handler  (sut/list-members-handler *mock-service*)
          request  (make-request :get {:tenant-id (str tenant-id-1)})
          response (handler request)
          body     (parse-body response)]
      (is (= 200 (:status response)))
      (is (= 2 (count body)))))

  (testing "400 for invalid tenant UUID"
    (let [handler  (sut/list-members-handler *mock-service*)
          request  (make-request :get {:tenant-id "bad-uuid"})
          response (handler request)]
      (is (= 400 (:status response))))))

;; =============================================================================
;; get-membership-handler
;; =============================================================================

(deftest ^:contract get-membership-handler-test
  (testing "200 for existing membership"
    (let [handler  (sut/get-membership-handler *mock-service*)
          request  (make-request :get {:tenant-id (str tenant-id-1)
                                       :id        (str member-id-1)})
          response (handler request)
          body     (parse-body response)]
      (is (= 200 (:status response)))
      (is (= (str member-id-1) (str (:id body))))))

  (testing "404 for non-existent membership"
    (let [handler  (sut/get-membership-handler *mock-service*)
          request  (make-request :get {:tenant-id (str tenant-id-1)
                                       :id        (str (UUID/randomUUID))})
          response (handler request)]
      (is (= 404 (:status response)))))

  (testing "400 for invalid membership UUID"
    (let [handler  (sut/get-membership-handler *mock-service*)
          request  (make-request :get {:tenant-id (str tenant-id-1)
                                       :id        "not-a-uuid"})
          response (handler request)]
      (is (= 400 (:status response))))))

;; =============================================================================
;; update-membership-handler
;; =============================================================================

(deftest ^:contract update-membership-handler-test
  (testing "200 when updating role"
    (let [handler  (sut/update-membership-handler *mock-service*)
          request  (make-request :put
                                 {:tenant-id (str tenant-id-1)
                                  :id        (str member-id-1)}
                                 {:role "admin"})
          response (handler request)
          body     (parse-body response)]
      (is (= 200 (:status response)))
      (is (= "admin" (name (:role body))))))

  (testing "200 when suspending via status"
    (let [handler  (sut/update-membership-handler *mock-service*)
          request  (make-request :put
                                 {:tenant-id (str tenant-id-1)
                                  :id        (str member-id-2)}
                                 {:status "suspended"})
          response (handler request)
          body     (parse-body response)]
      (is (= 200 (:status response)))
      (is (= "suspended" (name (:status body))))))

  (testing "400 for invalid role"
    (let [handler  (sut/update-membership-handler *mock-service*)
          request  (make-request :put
                                 {:tenant-id (str tenant-id-1)
                                  :id        (str member-id-1)}
                                 {:role "owner"})
          response (handler request)]
      (is (= 400 (:status response))))))

;; =============================================================================
;; revoke-member-handler
;; =============================================================================

(deftest ^:contract revoke-member-handler-test
  (testing "200 on successful revoke"
    (let [handler  (sut/revoke-member-handler *mock-service*)
          request  (make-request :delete {:tenant-id (str tenant-id-1)
                                          :id        (str member-id-1)})
          response (handler request)
          body     (parse-body response)]
      (is (= 200 (:status response)))
      (is (= "Membership revoked successfully" (:message body)))))

  (testing "404 for non-existent membership"
    (let [handler  (sut/revoke-member-handler *mock-service*)
          request  (make-request :delete {:tenant-id (str tenant-id-1)
                                          :id        (str (UUID/randomUUID))})
          response (handler request)]
      (is (= 404 (:status response))))))

;; =============================================================================
;; accept-invitation-handler
;; =============================================================================

(deftest ^:contract accept-invitation-handler-test
  (testing "200 on accepting an invitation"
    (let [handler  (sut/accept-invitation-handler *mock-service*)
          request  (as-invitee (make-request :post {:id (str member-id-1)}))
          response (handler request)
          body     (parse-body response)]
      (is (= 200 (:status response)))
      (is (= "active" (name (:status body))))))

  (testing "400 when membership is not in :invited status"
    (let [handler  (sut/accept-invitation-handler *mock-service*)
          request  (as-invitee (make-request :post {:id (str member-id-2)}))
          response (handler request)]
      (is (= 400 (:status response)))))

  (testing "404 for non-existent membership"
    (let [handler  (sut/accept-invitation-handler *mock-service*)
          request  (make-request :post {:id (str (UUID/randomUUID))})
          response (handler request)]
      (is (= 404 (:status response)))))

  (testing "400 for invalid UUID"
    (let [handler  (sut/accept-invitation-handler *mock-service*)
          request  (make-request :post {:id "bad-uuid"})
          response (handler request)]
      (is (= 400 (:status response))))))

;; =============================================================================
;; Routes structure
;; =============================================================================

(deftest ^:contract membership-routes-test
  (testing "returns a contribution with an :api key"
    (let [routes (sut/membership-routes *mock-service*)]
      (is (map? routes))
      (is (contains? routes :api))
      (is (= 3 (count (:api routes))))))

  (testing "every route is Reitit data with real handlers"
    ;; [path {method {:handler f}}] — ADR-037.
    (doseq [route (:api (sut/membership-routes *mock-service*))]
      (is (vector? route))
      (is (string? (first route)))
      (doseq [[_method config] (second route)]
        (is (fn? (:handler config)))))))

;; =============================================================================
;; Authorization (BOU-568)
;; =============================================================================

(def other-tenant #uuid "10000000-0000-0000-0000-000000000009")
(def stranger     #uuid "20000000-0000-0000-0000-000000000009")
(def tenant-admin #uuid "20000000-0000-0000-0000-000000000002")

(defn- through-route
  "`request` answered by the route at `path`/`method`, its middleware included."
  [path method request]
  (let [data     (some (fn [[p d]] (when (= p path) d))
                       (:api (sut/membership-routes *mock-service*)))
        endpoint (get data method)
        handler  (reduce (fn [h mw] (mw h))
                         (:handler endpoint)
                         (reverse (concat (:middleware data) (:middleware endpoint))))]
    (handler (assoc request :request-method method :uri (str "/api/v1" path)))))

(defn- add-admin! []
  (swap! (:memberships *mock-service*) assoc (UUID/randomUUID)
         {:id (UUID/randomUUID) :tenant-id tenant-id-1 :user-id tenant-admin
          :role :admin :status :active}))

(deftest ^:contract ^:security only-the-invitee-accepts
  (let [handler  (sut/accept-invitation-handler *mock-service*)
        response (handler (assoc (make-request :post {:id (str member-id-1)})
                                 :user {:id stranger}))]
    (is (= 403 (:status response)))
    (is (= :invited (:status (ports/get-membership *mock-service* member-id-1)))
        "and the invitation is still open")))

(deftest ^:contract ^:security changing-memberships-takes-a-tenant-admin
  (add-admin!)
  (let [invite (fn [user]
                 (through-route "/tenants/:tenant-id/memberships" :post
                                (assoc (make-request :post {:tenant-id (str tenant-id-1)}
                                                     {:userId (str (UUID/randomUUID)) :role "admin"})
                                       :user user)))]
    (testing "a signed-in stranger cannot invite"
      (is (= 403 (:status (invite {:id stranger :role :user})))))
    (testing "a plain member cannot invite"
      (is (= 403 (:status (invite {:id user-id-1 :role :user})))))
    (testing "the tenant's admin can"
      (is (= 201 (:status (invite {:id tenant-admin :role :user})))))
    (testing "and so can a global admin"
      (is (= 201 (:status (invite {:id stranger :role :admin}))))))

  (doseq [method [:put :delete]]
    (testing (str (name method) " by a plain member is refused")
      (let [response (through-route "/tenants/:tenant-id/memberships/:id" method
                                    (assoc (make-request method {:tenant-id (str tenant-id-1)
                                                                 :id        (str member-id-1)}
                                                         {:role "admin"})
                                           :user {:id user-id-1 :role :user}))]
        (is (= 403 (:status response)))
        (is (= :member (:role (ports/get-membership *mock-service* member-id-1))))))))

(deftest ^:contract ^:security reading-memberships-takes-a-member
  (let [list-as (fn [user]
                  (through-route "/tenants/:tenant-id/memberships" :get
                                 (assoc (make-request :get {:tenant-id (str tenant-id-1)})
                                        :user user)))]
    (is (= 403 (:status (list-as {:id stranger :role :user}))))
    (is (= 200 (:status (list-as {:id user-id-1 :role :user}))))))

(deftest ^:contract ^:security a-tenant-admin-cannot-reach-another-tenants-memberships
  ;; Naming your own tenant in the path must not open another tenant's rows.
  (swap! (:memberships *mock-service*) assoc (UUID/randomUUID)
         {:id (UUID/randomUUID) :tenant-id other-tenant :user-id tenant-admin
          :role :admin :status :active})
  (let [response (through-route "/tenants/:tenant-id/memberships/:id" :delete
                                (assoc (make-request :delete {:tenant-id (str other-tenant)
                                                              :id        (str member-id-1)})
                                       :user {:id tenant-admin :role :user}))]
    (is (= 404 (:status response)))
    (is (= :invited (:status (ports/get-membership *mock-service* member-id-1))))))
