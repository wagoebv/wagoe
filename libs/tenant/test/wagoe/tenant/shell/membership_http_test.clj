(ns wagoe.tenant.shell.membership-http-test
  "Contract tests for the membership HTTP endpoints, over the real service and
   repository on H2 — a mock can drift from the service it stands in for, and
   the tenant handlers' did (BOU-576)."
  (:require [wagoe.platform.shell.adapters.database.factory :as factory]
            [wagoe.tenant.shell.membership-http :as sut]
            [wagoe.tenant.shell.membership-persistence :as membership-persistence]
            [wagoe.tenant.shell.membership-service :as membership-service]
            [wagoe.tenant.shell.persistence :as tenant-persistence]
            [wagoe.tenant.ports :as ports]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import (java.util UUID)))

^{:kaocha.testable/meta {:contract true :tenant true}}

(def tenant-id-1  #uuid "10000000-0000-0000-0000-000000000001")
(def other-tenant #uuid "10000000-0000-0000-0000-000000000009")
(def user-id-1    #uuid "20000000-0000-0000-0000-000000000001")
(def active-user  #uuid "20000000-0000-0000-0000-000000000003")
(def stranger     #uuid "20000000-0000-0000-0000-000000000009")
(def tenant-admin #uuid "20000000-0000-0000-0000-000000000002")

(def ^:dynamic *service* nil)
(def ^:dynamic *invited-id* nil)
(def ^:dynamic *active-id* nil)

(use-fixtures :each
  (fn [f]
    (let [ctx (factory/db-context
               (factory/h2-config (str "mem:membership_http_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")))]
      (try
        (tenant-persistence/initialize-tenant-schema! ctx)
        (let [service (membership-service/create-membership-service
                       (membership-persistence/create-membership-repository ctx nil nil) nil nil nil)
              invited (ports/invite-user service tenant-id-1 user-id-1 :member)
              active  (ports/bootstrap-first-member service tenant-id-1 active-user :member)]
          (binding [*service*    service
                    *invited-id* (:id invited)
                    *active-id*  (:id active)]
            (f)))
        (finally (factory/close-db-context! ctx))))))

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

(defn- in-tenant [id]
  {:tenant-id (str tenant-id-1) :id (str id)})

(defn- as-invitee [request]
  (assoc request :user {:id user-id-1}))

;; =============================================================================
;; invite-user-handler
;; =============================================================================

(deftest ^:contract invite-user-handler-test
  (let [invite #((sut/invite-user-handler *service*)
                 (make-request :post {:tenant-id %1} %2))]
    (testing "201 with the invitation"
      (let [user     (UUID/randomUUID)
            response (invite (str tenant-id-1) {:userId (str user) :role "admin"})
            body     (parse-body response)]
        (is (= 201 (:status response)))
        (is (= ["invited" (str user)] [(:status body) (:user-id body)]))))
    (testing "400 for invalid tenant UUID"
      (is (= 400 (:status (invite "not-a-uuid" {})))))
    (testing "400 for missing userId"
      (is (= 400 (:status (invite (str tenant-id-1) {:role "member"})))))
    (testing "400 for invalid role"
      (is (= 400 (:status (invite (str tenant-id-1) {:userId (str (UUID/randomUUID)) :role "superuser"})))))
    (testing "409 when the user already has a membership, saying so"
      (let [response (invite (str tenant-id-1) {:userId (str user-id-1) :role "member"})]
        (is (= 409 (:status response)))
        (is (re-find #"already exists" (get-in (parse-body response) [:error :message])))))))

;; =============================================================================
;; list-members-handler
;; =============================================================================

(deftest ^:contract list-members-handler-test
  (let [list-in #((sut/list-members-handler *service*) (make-request :get {:tenant-id %}))]
    (testing "200 with the tenant's members"
      (let [response (list-in (str tenant-id-1))]
        (is (= 200 (:status response)))
        (is (= #{(str *invited-id*) (str *active-id*)} (set (map :id (parse-body response)))))))
    (testing "another tenant's list is empty"
      (is (= [] (parse-body (list-in (str other-tenant))))))
    (testing "400 for invalid tenant UUID"
      (is (= 400 (:status (list-in "bad-uuid")))))))

;; =============================================================================
;; get-membership-handler
;; =============================================================================

(deftest ^:contract get-membership-handler-test
  (let [get-m #((sut/get-membership-handler *service*) (make-request :get %))]
    (testing "200 for existing membership"
      (let [response (get-m (in-tenant *invited-id*))]
        (is (= 200 (:status response)))
        (is (= (str *invited-id*) (:id (parse-body response))))))
    (testing "404 for non-existent membership"
      (is (= 404 (:status (get-m (in-tenant (UUID/randomUUID)))))))
    (testing "400 for invalid membership UUID"
      (is (= 400 (:status (get-m (in-tenant "not-a-uuid"))))))))

;; =============================================================================
;; update-membership-handler
;; =============================================================================

(deftest ^:contract update-membership-handler-test
  (let [put #((sut/update-membership-handler *service*) (make-request :put (in-tenant %1) %2))]
    (testing "200 when updating role"
      (let [response (put *invited-id* {:role "admin"})]
        (is (= 200 (:status response)))
        (is (= "admin" (:role (parse-body response))))
        (is (= :admin (:role (ports/get-membership *service* *invited-id*))))))
    (testing "200 when suspending via status"
      (let [response (put *active-id* {:status "suspended"})]
        (is (= 200 (:status response)))
        (is (= "suspended" (:status (parse-body response))))))
    (testing "404 when suspending a revoked membership, which is deleted"
      (ports/revoke-member *service* *invited-id*)
      (is (= 404 (:status (put *invited-id* {:status "suspended"})))))
    (testing "400 for invalid role"
      (is (= 400 (:status (put *active-id* {:role "owner"})))))
    (testing "404 for non-existent membership"
      (is (= 404 (:status (put (UUID/randomUUID) {:role "admin"})))))))

;; =============================================================================
;; revoke-member-handler
;; =============================================================================

(deftest ^:contract revoke-member-handler-test
  (let [delete #((sut/revoke-member-handler *service*) (make-request :delete (in-tenant %)))]
    (testing "200 on successful revoke"
      (let [response (delete *invited-id*)]
        (is (= 200 (:status response)))
        (is (= "Membership revoked successfully" (:message (parse-body response))))
        (is (thrown? clojure.lang.ExceptionInfo (ports/get-membership *service* *invited-id*)))))
    (testing "404 for non-existent membership"
      (is (= 404 (:status (delete (UUID/randomUUID))))))))

;; =============================================================================
;; accept-invitation-handler
;; =============================================================================

(deftest ^:contract accept-invitation-handler-test
  (let [accept #((sut/accept-invitation-handler *service*) %)]
    (testing "200 on accepting an invitation"
      (let [response (accept (as-invitee (make-request :post {:id (str *invited-id*)})))]
        (is (= 200 (:status response)))
        (is (= "active" (:status (parse-body response))))))
    (testing "400 when membership is not in :invited status"
      (let [response (accept (as-invitee (make-request :post {:id (str *invited-id*)})))]
        (is (= 400 (:status response)))
        (is (re-find #"invited" (get-in (parse-body response) [:error :message])))))
    (testing "404 for non-existent membership"
      (is (= 404 (:status (accept (make-request :post {:id (str (UUID/randomUUID))}))))))
    (testing "400 for invalid UUID"
      (is (= 400 (:status (accept (make-request :post {:id "bad-uuid"}))))))))

;; =============================================================================
;; Routes structure
;; =============================================================================

(deftest ^:contract membership-routes-test
  (let [routes (sut/membership-routes *service*)]
    (is (= 3 (count (:api routes))))
    (testing "every route is Reitit data with real handlers"
      ;; [path {method {:handler f}}] — ADR-037.
      (doseq [[path data] (:api routes)]
        (is (string? path))
        (doseq [[_method config] data]
          (is (fn? (:handler config))))))))

;; =============================================================================
;; Authorization (BOU-568)
;; =============================================================================

(defn- through-route
  "`request` answered by the route at `path`/`method`, its middleware included."
  [path method request]
  (let [data     (some (fn [[p d]] (when (= p path) d))
                       (:api (sut/membership-routes *service*)))
        endpoint (get data method)
        handler  (reduce (fn [h mw] (mw h))
                         (:handler endpoint)
                         (reverse (concat (:middleware data) (:middleware endpoint))))]
    (handler (assoc request :request-method method :uri (str "/api/v1" path)))))

(deftest ^:contract ^:security only-the-invitee-accepts
  (let [handler  (sut/accept-invitation-handler *service*)
        response (handler (assoc (make-request :post {:id (str *invited-id*)})
                                 :user {:id stranger}))]
    (is (= 403 (:status response)))
    (is (= :invited (:status (ports/get-membership *service* *invited-id*)))
        "and the invitation is still open")))

(deftest ^:contract ^:security changing-memberships-takes-a-tenant-admin
  (ports/bootstrap-first-member *service* tenant-id-1 tenant-admin :admin)
  (let [invite (fn [user]
                 (through-route "/tenants/:tenant-id/memberships" :post
                                (assoc (make-request :post {:tenant-id (str tenant-id-1)}
                                                     {:userId (str (UUID/randomUUID)) :role "admin"})
                                       :user user)))]
    (testing "a signed-in stranger cannot invite"
      (is (= 403 (:status (invite {:id stranger :role :user})))))
    (testing "a plain member cannot invite"
      (is (= 403 (:status (invite {:id active-user :role :user})))))
    (testing "the tenant's admin can"
      (is (= 201 (:status (invite {:id tenant-admin :role :user})))))
    (testing "and so can a global admin"
      (is (= 201 (:status (invite {:id stranger :role :admin}))))))

  (doseq [method [:put :delete]]
    (testing (str (name method) " by a plain member is refused")
      (let [response (through-route "/tenants/:tenant-id/memberships/:id" method
                                    (assoc (make-request method (in-tenant *invited-id*) {:role "admin"})
                                           :user {:id active-user :role :user}))]
        (is (= 403 (:status response)))
        (is (= :member (:role (ports/get-membership *service* *invited-id*))))))))

(deftest ^:contract ^:security reading-memberships-takes-a-member
  (let [list-as (fn [user]
                  (through-route "/tenants/:tenant-id/memberships" :get
                                 (assoc (make-request :get {:tenant-id (str tenant-id-1)})
                                        :user user)))]
    (is (= 403 (:status (list-as {:id stranger :role :user}))))
    (is (= 403 (:status (list-as {:id user-id-1 :role :user})))
        "an open invitation is not a membership yet")
    (is (= 200 (:status (list-as {:id active-user :role :user}))))))

(deftest ^:contract ^:security a-tenant-admin-cannot-reach-another-tenants-memberships
  ;; Naming your own tenant in the path must not open another tenant's rows.
  (ports/bootstrap-first-member *service* other-tenant tenant-admin :admin)
  (let [response (through-route "/tenants/:tenant-id/memberships/:id" :delete
                                (assoc (make-request :delete {:tenant-id (str other-tenant)
                                                              :id        (str *invited-id*)})
                                       :user {:id tenant-admin :role :user}))]
    (is (= 404 (:status response)))
    (is (= :invited (:status (ports/get-membership *service* *invited-id*))))))
