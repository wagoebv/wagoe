(ns wagoe.tenant.shell.invite-service-test
  (:require [wagoe.observability.errors.ports :as error-ports]
            [wagoe.observability.logging.shell.adapters.no-op :as no-op-logging]
            [wagoe.observability.metrics.ports :as metrics-ports]
            [wagoe.tenant.ports :as ports]
            [wagoe.tenant.shell.invite-service :as sut]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import (java.time Instant)
           (java.util UUID)))

^{:kaocha.testable/meta {:unit true :tenant true}}

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

(defrecord MockInviteRepository [state]
  ports/ITenantInviteRepository

  (find-invite-by-id [_ invite-id]
    (get @state invite-id))

  (find-pending-invite-by-token-hash [_ token-hash]
    (->> (vals @state)
         (filter #(and (= :pending (:status %))
                       (= token-hash (:token-hash %))))
         first))

  (find-pending-invite-by-email-and-tenant [_ tenant-id email]
    (->> (vals @state)
         (filter #(and (= :pending (:status %))
                       (= tenant-id (:tenant-id %))
                       (= email (:email %))))
         first))

  (find-invites-by-tenant [_ tenant-id {:keys [limit offset status]
                                        :or {limit 50 offset 0}}]
    (->> (vals @state)
         (filter #(= tenant-id (:tenant-id %)))
         (filter #(or (nil? status) (= status (:status %))))
         (drop offset)
         (take limit)
         vec))

  (create-invite [_ invite-entity]
    (swap! state assoc (:id invite-entity) invite-entity)
    invite-entity)

  (update-invite [_ invite-entity]
    (swap! state assoc (:id invite-entity) invite-entity)
    invite-entity))

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
                                            :or {limit 50 offset 0}}]
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

(def ^:dynamic *invite-repository* nil)
(def ^:dynamic *membership-repository* nil)

(defn setup-mock-repository []
  (alter-var-root #'*invite-repository*
                  (constantly (->MockInviteRepository (atom {}))))
  (alter-var-root #'*membership-repository*
                  (constantly (->MockMembershipRepository (atom {})))))

(defn teardown-mock-repository []
  (alter-var-root #'*invite-repository* (constantly nil))
  (alter-var-root #'*membership-repository* (constantly nil)))

(use-fixtures :each
  (fn [f]
    (setup-mock-repository)
    (f)
    (teardown-mock-repository)))

(defn make-service []
  (sut/create-invite-service
   *invite-repository*
   *membership-repository*
   mock-logger
   mock-metrics-emitter
   mock-error-reporter))

(deftest ^:unit invite-external-member-test
  (testing "creates a pending invite with a delivery token"
    (let [service (make-service)
          tenant-id (UUID/randomUUID)
          invite-id (UUID/fromString "dddddddd-dddd-dddd-dddd-dddddddddddd")
          fixed-now (Instant/parse "2026-04-10T12:00:00Z")]
      (with-redefs [wagoe.tenant.shell.invite-service/generate-invite-id (fn [] invite-id)
                    wagoe.tenant.shell.invite-service/generate-invite-token (fn [] "raw-token")
                    wagoe.tenant.shell.invite-service/current-timestamp (fn [] fixed-now)]
        (let [result (ports/invite-external-member service tenant-id "Contractor@Example.NL" :contractor {})]
          (is (= invite-id (:id result)))
          (is (= tenant-id (:tenant-id result)))
          (is (= "contractor@example.nl" (:email result)))
          (is (= :contractor (:role result)))
          (is (= :pending (:status result)))
          (is (= "raw-token" (:invite-token result)))
          (is (not= (:invite-token result) (:token-hash result)))
          (is (= fixed-now (:created-at result)))
          (is (some? (:expires-at result)))))))

  (testing "rejects duplicate non-expired invite for the same tenant and email"
    (let [service (make-service)
          tenant-id (UUID/randomUUID)]
      (ports/invite-external-member service tenant-id "contractor@example.nl" :contractor {})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Pending invite already exists"
                            (ports/invite-external-member service tenant-id "contractor@example.nl" :contractor {}))))))

(deftest ^:unit get-and-accept-external-invite-test
  (testing "resolves invite by raw token and accepts it for a user"
    (let [service (make-service)
          tenant-id (UUID/randomUUID)
          user-id (UUID/randomUUID)
          created (ports/invite-external-member service tenant-id "contractor@example.nl" :contractor {})
          invite (ports/get-external-invite-by-token service (:invite-token created))
          accepted (ports/accept-external-invite service (:invite-token created) user-id)]
      (is (= (:id created) (:id invite)))
      (is (= :accepted (get-in accepted [:invite :status])))
      (is (= user-id (get-in accepted [:invite :accepted-by-user-id])))
      (is (some? (get-in accepted [:invite :accepted-at])))
      (is (= :active (get-in accepted [:membership :status])))
      (is (= tenant-id (get-in accepted [:membership :tenant-id])))
      (is (= user-id (get-in accepted [:membership :user-id])))))

  (testing "rejects missing invites"
    (let [service (make-service)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invite not found"
                            (ports/get-external-invite-by-token service "missing-token")))))

  (testing "resolves invites by invite id"
    (let [service (make-service)
          tenant-id (UUID/randomUUID)
          created (ports/invite-external-member service tenant-id "contractor@example.nl" :contractor {})]
      (is (= (:id created)
             (:id (ports/get-external-invite service (:id created)))))))

  (testing "rejects accept when membership already exists"
    (let [service (make-service)
          tenant-id (UUID/randomUUID)
          user-id (UUID/randomUUID)
          created (ports/invite-external-member service tenant-id "contractor@example.nl" :contractor {})
          _first (ports/accept-external-invite service (:invite-token created) user-id)
          second (ports/invite-external-member service tenant-id "contractor@example.nl" :contractor {})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Membership already exists"
                            (ports/accept-external-invite service (:invite-token second) user-id))))))

(deftest ^:unit acceptance-api-test
  (testing "load-external-invite-for-acceptance resolves the same pending invite"
    (let [service (make-service)
          tenant-id (UUID/randomUUID)
          created (ports/invite-external-member service tenant-id "contractor@example.nl" :contractor {})
          invite (ports/load-external-invite-for-acceptance service {:token (:invite-token created)})]
      (is (= (:id created) (:id invite)))
      (is (= :pending (:status invite)))))

  (testing "accept-external-invite! runs the transactional hook and returns effect output"
    (let [service (make-service)
          tenant-id (UUID/randomUUID)
          user-id (UUID/randomUUID)
          created (ports/invite-external-member service tenant-id "contractor@example.nl" :contractor {})
          hook-calls (atom [])
          accepted (ports/accept-external-invite! service
                                                  {:token (:invite-token created)
                                                   :accepted-by-user-id user-id
                                                   :hooks {:after-accept-tx
                                                           (fn [{:keys [invite membership]}]
                                                             (swap! hook-calls conj {:invite-id (:id invite)
                                                                                     :membership-id (:id membership)})
                                                             :hook-ran)}})]
      (is (= :accepted (get-in accepted [:invite :status])))
      (is (= :hook-ran (get-in accepted [:effects :after-accept-tx])))
      (is (= [{:invite-id (:id created)
               :membership-id (get-in accepted [:membership :id])}]
             @hook-calls))))

  (testing "load-external-invite-for-acceptance rejects expired invites"
    (let [service (make-service)
          tenant-id (UUID/randomUUID)
          created (ports/invite-external-member service tenant-id "contractor@example.nl" :contractor
                                                {:expires-at (.minusSeconds (Instant/now) 60)})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invite has expired"
                            (ports/load-external-invite-for-acceptance service {:token (:invite-token created)})))))

  (testing "accept-external-invite! preserves explicit transaction context for hooks"
    (let [service (make-service)
          tenant-id (UUID/randomUUID)
          user-id (UUID/randomUUID)
          created (ports/invite-external-member service tenant-id "contractor@example.nl" :contractor {})
          tx-context {:tx true}
          seen-tx (atom nil)]
      (with-redefs [wagoe.tenant.shell.invite-service/with-shared-transaction
                    (fn [_invite-repository _membership-repository incoming-tx-context f]
                      (f *invite-repository* *membership-repository* incoming-tx-context))]
        (ports/accept-external-invite! service
                                       {:token (:invite-token created)
                                        :accepted-by-user-id user-id
                                        :tx-context tx-context
                                        :hooks {:after-accept-tx (fn [{:keys [tx-context]}]
                                                                   (reset! seen-tx tx-context)
                                                                   :ok)}}))
      (is (= tx-context @seen-tx)))))

(deftest ^:unit revoke-external-invite-test
  (testing "revokes a pending invite"
    (let [service (make-service)
          tenant-id (UUID/randomUUID)
          created (ports/invite-external-member service tenant-id "contractor@example.nl" :contractor {})
          revoked (ports/revoke-external-invite service (:id created))]
      (is (= :revoked (:status revoked)))
      (is (some? (:revoked-at revoked)))))

  (testing "rejects revoking non-pending invites"
    (let [service (make-service)
          tenant-id (UUID/randomUUID)
          user-id (UUID/randomUUID)
          created (ports/invite-external-member service tenant-id "contractor@example.nl" :contractor {})
          _accepted (ports/accept-external-invite service (:invite-token created) user-id)
          ex (is (thrown? clojure.lang.ExceptionInfo
                          (ports/revoke-external-invite service (:id created))))]
      (is (= :validation-error (:type (ex-data ex))))))

  (testing "rejects revoking missing invites"
    (let [service (make-service)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invite not found"
                            (ports/revoke-external-invite service (UUID/randomUUID)))))))

(deftest ^:unit expired-invites-are-not-accepted-test
  (testing "expired invites are rejected when resolving by token"
    (let [service (make-service)
          tenant-id (UUID/randomUUID)
          created (ports/invite-external-member service tenant-id "contractor@example.nl" :contractor
                                                {:expires-at (.minusSeconds (Instant/now) 60)})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invite has expired"
                            (ports/get-external-invite-by-token service (:invite-token created)))))))

(deftest ^:unit list-tenant-invites-test
  (testing "lists and filters invites by tenant and status"
    (let [service (make-service)
          tenant-a (UUID/randomUUID)
          tenant-b (UUID/randomUUID)
          pending-a (ports/invite-external-member service tenant-a "a@example.nl" :member {})
          pending-b (ports/invite-external-member service tenant-a "b@example.nl" :viewer {})
          _other (ports/invite-external-member service tenant-b "c@example.nl" :viewer {})
          _revoked (ports/revoke-external-invite service (:id pending-b))
          all-a (ports/list-tenant-invites service tenant-a {})
          revoked-a (ports/list-tenant-invites service tenant-a {:status :revoked})]
      (is (= #{(:id pending-a) (:id pending-b)}
             (set (map :id all-a))))
      (is (= [(:id pending-b)]
             (mapv :id revoked-a))))))
