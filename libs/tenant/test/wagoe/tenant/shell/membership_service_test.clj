(ns wagoe.tenant.shell.membership-service-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [wagoe.tenant.shell.membership-service :as sut]
            [wagoe.tenant.ports :as ports]
            [wagoe.observability.errors.ports :as error-ports]
            [wagoe.observability.metrics.ports :as metrics-ports]
            [wagoe.observability.logging.shell.adapters.no-op :as no-op-logging])
  (:import (java.util UUID)))

^{:kaocha.testable/meta {:unit true :tenant true}}

;; =============================================================================
;; Mock observability services
;; =============================================================================

;; Use the NoOpLoggingComponent which implements ILogger, IAuditLogger, and
;; ILoggingContext — required because service-audit-logging calls audit-event
;; whenever :user-id appears in operation params (e.g. invite-user).
(def mock-logger (no-op-logging/create-logging-component {}))

(def mock-metrics-emitter
  (reify
    metrics-ports/IMetricsEmitter
    (inc-counter! [_ _metric-handle] nil)
    (inc-counter! [_ _metric-handle _value] nil)
    (inc-counter! [_ _metric-handle _value _tags] nil)
    (set-gauge! [_ _metric-handle _value] nil)
    (set-gauge! [_ _metric-handle _value _tags] nil)
    (observe-histogram! [_ _metric-handle _value] nil)
    (observe-histogram! [_ _metric-handle _value _tags] nil)
    (observe-summary! [_ _metric-handle _value] nil)
    (observe-summary! [_ _metric-handle _value _tags] nil)
    (time-histogram! [_ _metric-handle f] (f))
    (time-histogram! [_ _metric-handle _tags f] (f))
    (time-summary! [_ _metric-handle f] (f))
    (time-summary! [_ _metric-handle _tags f] (f))
    Object
    (toString [_] "MockMetricsEmitter")))

(def mock-error-reporter
  (reify
    error-ports/IErrorContext
    (add-breadcrumb! [_ _breadcrumb] nil)
    (with-context [_ _context-map f] (f))
    (clear-breadcrumbs! [_] nil)
    (set-user! [_ _user-info] nil)
    (set-tags! [_ _tags] nil)
    (set-extra! [_ _extra] nil)
    (current-context [_] {})
    Object
    (toString [_] "MockErrorReporter")))

;; =============================================================================
;; Mock Repository
;; =============================================================================

(defrecord MockMembershipRepository [state]
  ports/ITenantMembershipRepository

  (find-membership-by-id [_ membership-id]
    (get @state membership-id))

  (find-membership-by-user-and-tenant [_ user-id tenant-id]
    (->> (vals @state)
         (filter #(and (= user-id (:user-id %))
                       (= tenant-id (:tenant-id %))))
         first))

  (find-memberships-by-tenant [_ tenant-id {:keys [limit offset status]
                                            :or   {limit 50 offset 0}}]
    (->> (vals @state)
         (filter #(= tenant-id (:tenant-id %)))
         (filter #(or (nil? status) (= status (:status %))))
         (drop offset)
         (take limit)
         vec))

  (find-memberships-by-user [_ user-id]
    (->> (vals @state)
         (filter #(= user-id (:user-id %)))
         vec))

  (create-membership [_ membership-entity]
    (swap! state assoc (:id membership-entity) membership-entity)
    membership-entity)

  (update-membership [_ membership-entity]
    (swap! state assoc (:id membership-entity) membership-entity)
    membership-entity)

  (membership-exists? [_ user-id tenant-id]
    (boolean (some #(and (= user-id (:user-id %))
                         (= tenant-id (:tenant-id %)))
                   (vals @state)))))

(def ^:dynamic *membership-repository* nil)

(defn setup-mock-repository []
  (alter-var-root #'*membership-repository*
                  (constantly (->MockMembershipRepository (atom {})))))

(defn teardown-mock-repository []
  (alter-var-root #'*membership-repository* (constantly nil)))

(use-fixtures :each
  (fn [f]
    (setup-mock-repository)
    (f)
    (teardown-mock-repository)))

(defn make-service []
  (sut/create-membership-service *membership-repository* mock-logger mock-metrics-emitter mock-error-reporter))

;; =============================================================================
;; Tests
;; =============================================================================

(deftest ^:unit invite-user-test
  (testing "creates a membership in :invited status"
    (let [service   (make-service)
          tenant-id (UUID/randomUUID)
          user-id   (UUID/randomUUID)
          result    (ports/invite-user service tenant-id user-id :member)]
      (is (uuid? (:id result)))
      (is (= tenant-id (:tenant-id result)))
      (is (= user-id (:user-id result)))
      (is (= :member (:role result)))
      (is (= :invited (:status result)))
      (is (some? (:invited-at result)))))

  (testing "rejects duplicate membership"
    (let [service   (make-service)
          tenant-id (UUID/randomUUID)
          user-id   (UUID/randomUUID)]
      (ports/invite-user service tenant-id user-id :member)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Membership already exists"
                            (ports/invite-user service tenant-id user-id :admin))))))

(deftest ^:unit bootstrap-first-member-test
  (testing "creates the first membership directly in :active status"
    (let [service   (make-service)
          tenant-id (UUID/randomUUID)
          user-id   (UUID/randomUUID)
          result    (ports/bootstrap-first-member service tenant-id user-id :admin)]
      (is (uuid? (:id result)))
      (is (= tenant-id (:tenant-id result)))
      (is (= user-id (:user-id result)))
      (is (= :admin (:role result)))
      (is (= :active (:status result)))
      (is (some? (:accepted-at result)))))

  (testing "rejects bootstrap when the tenant already has an active admin"
    (let [service   (make-service)
          tenant-id (UUID/randomUUID)]
      (ports/bootstrap-first-member service tenant-id (UUID/randomUUID) :admin)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Tenant already has an active admin"
                            (ports/bootstrap-first-member service tenant-id (UUID/randomUUID) :admin))))))

(deftest ^:unit bootstrap-open-test
  (testing "returns true when tenant has no active admin"
    (let [service   (make-service)
          tenant-id (UUID/randomUUID)]
      (is (true? (ports/bootstrap-open? service tenant-id)))))

  (testing "returns true when tenant only has non-admin memberships"
    (let [service   (make-service)
          tenant-id (UUID/randomUUID)]
      (ports/invite-user service tenant-id (UUID/randomUUID) :member)
      (is (true? (ports/bootstrap-open? service tenant-id)))))

  (testing "returns false when tenant already has an active admin"
    (let [service   (make-service)
          tenant-id (UUID/randomUUID)]
      (ports/bootstrap-first-member service tenant-id (UUID/randomUUID) :admin)
      (is (false? (ports/bootstrap-open? service tenant-id))))))

(deftest ^:unit accept-invitation-test
  (testing "transitions membership to :active"
    (let [service      (make-service)
          tenant-id    (UUID/randomUUID)
          user-id      (UUID/randomUUID)
          invited      (ports/invite-user service tenant-id user-id :member)
          accepted     (ports/accept-invitation service (:id invited))]
      (is (= :active (:status accepted)))
      (is (some? (:accepted-at accepted)))))

  (testing "throws when membership not found"
    (let [service (make-service)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Membership not found"
                            (ports/accept-invitation service (UUID/randomUUID))))))

  (testing "throws when membership is not in :invited status"
    (let [service   (make-service)
          tenant-id (UUID/randomUUID)
          user-id   (UUID/randomUUID)
          invited   (ports/invite-user service tenant-id user-id :member)
          _accepted (ports/accept-invitation service (:id invited))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"not in invited status"
                            (ports/accept-invitation service (:id invited)))))))

(deftest ^:unit update-member-role-test
  (testing "changes role on an existing membership"
    (let [service   (make-service)
          tenant-id (UUID/randomUUID)
          user-id   (UUID/randomUUID)
          invited   (ports/invite-user service tenant-id user-id :member)
          _accepted (ports/accept-invitation service (:id invited))
          result    (ports/update-member-role service (:id invited) :admin)]
      (is (= :admin (:role result)))))

  (testing "throws when membership not found"
    (let [service (make-service)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Membership not found"
                            (ports/update-member-role service (UUID/randomUUID) :admin))))))

(deftest ^:unit suspend-member-test
  (testing "suspends an active membership"
    (let [service   (make-service)
          tenant-id (UUID/randomUUID)
          user-id   (UUID/randomUUID)
          invited   (ports/invite-user service tenant-id user-id :member)
          _accepted (ports/accept-invitation service (:id invited))
          result    (ports/suspend-member service (:id invited))]
      (is (= :suspended (:status result)))))

  (testing "cannot suspend a revoked membership"
    (let [service   (make-service)
          tenant-id (UUID/randomUUID)
          user-id   (UUID/randomUUID)
          invited   (ports/invite-user service tenant-id user-id :member)
          _revoked  (ports/revoke-member service (:id invited))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Cannot suspend a revoked"
                            (ports/suspend-member service (:id invited)))))))

(deftest ^:unit revoke-member-test
  (testing "revokes a membership"
    (let [service   (make-service)
          tenant-id (UUID/randomUUID)
          user-id   (UUID/randomUUID)
          invited   (ports/invite-user service tenant-id user-id :member)
          result    (ports/revoke-member service (:id invited))]
      (is (= :revoked (:status result))))))

(deftest ^:unit get-membership-test
  (testing "retrieves existing membership"
    (let [service   (make-service)
          tenant-id (UUID/randomUUID)
          user-id   (UUID/randomUUID)
          invited   (ports/invite-user service tenant-id user-id :member)
          result    (ports/get-membership service (:id invited))]
      (is (= (:id invited) (:id result)))))

  (testing "throws for non-existent membership"
    (let [service (make-service)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Membership not found"
                            (ports/get-membership service (UUID/randomUUID)))))))

(deftest ^:unit get-active-membership-test
  (testing "returns active membership for user+tenant"
    (let [service   (make-service)
          tenant-id (UUID/randomUUID)
          user-id   (UUID/randomUUID)
          invited   (ports/invite-user service tenant-id user-id :member)
          _accepted (ports/accept-invitation service (:id invited))
          result    (ports/get-active-membership service user-id tenant-id)]
      (is (= :active (:status result)))))

  (testing "returns nil when membership is not active"
    (let [service   (make-service)
          tenant-id (UUID/randomUUID)
          user-id   (UUID/randomUUID)
          _invited  (ports/invite-user service tenant-id user-id :member)
          result    (ports/get-active-membership service user-id tenant-id)]
      (is (nil? result))))

  (testing "returns nil when no membership exists"
    (let [service   (make-service)
          result    (ports/get-active-membership service (UUID/randomUUID) (UUID/randomUUID))]
      (is (nil? result)))))

(deftest ^:unit list-tenant-members-test
  (testing "lists all members of a tenant"
    (let [service   (make-service)
          tenant-id (UUID/randomUUID)]
      (ports/invite-user service tenant-id (UUID/randomUUID) :member)
      (ports/invite-user service tenant-id (UUID/randomUUID) :admin)
      (let [results (ports/list-tenant-members service tenant-id {})]
        (is (= 2 (count results))))))

  (testing "does not include members from other tenants"
    (let [service    (make-service)
          tenant-a   (UUID/randomUUID)
          tenant-b   (UUID/randomUUID)]
      (ports/invite-user service tenant-a (UUID/randomUUID) :member)
      (ports/invite-user service tenant-b (UUID/randomUUID) :admin)
      (let [results (ports/list-tenant-members service tenant-a {})]
        (is (= 1 (count results)))))))
