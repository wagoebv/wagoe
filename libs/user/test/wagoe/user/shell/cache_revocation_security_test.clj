(ns wagoe.user.shell.cache-revocation-security-test
  "BOU-663: with :wagoe/cache on, authentication must still see revocation,
   deactivation and role changes on the very next request."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.cache.shell.adapters.in-memory :as in-memory]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.in-memory-repository-test :as mem]
            [wagoe.user.shell.middleware :as middleware]
            [wagoe.user.shell.module-wiring :as wiring]
            [wagoe.user.shell.service :as service])
  (:import [java.time Instant]
           [java.util UUID]))

(defn- replica
  "A user service on the shared stores, with its own in-memory cache — one
   process of a deployment."
  [{:keys [users sessions audit]}]
  (service/create-user-service users sessions audit
                               {:password-policy {:min-length 12}}
                               nil
                               (in-memory/create-in-memory-cache {})))

(defn- stores []
  {:users    (mem/->MemoryUserRepository (atom {}))
   :sessions (mem/->MemorySessionRepository (atom {}))
   :audit    (mem/->MemoryAuditRepository (atom []))})

(defn- sign-in!
  "Registers a user and opens a session for them. Returns [user token]."
  [svc {:keys [sessions]}]
  (let [user  (ports/register-user svc {:email "ada@example.com"
                                        :name "Ada Lovelace"
                                        :role :user
                                        :password "Correct-horse-battery-9"})
        token (str (UUID/randomUUID))]
    (ports/create-session sessions {:user-id (:id user)
                                    :session-token token
                                    :created-at (Instant/now)
                                    :expires-at (.plusSeconds (Instant/now) 3600)})
    [user token]))

(defn- request!
  "Sends one request through session authentication. Returns the response;
   a 200 carries the authenticated user's role in :body."
  [svc token]
  ((middleware/session-authentication-middleware
    svc (fn [req] {:status 200 :body (get-in req [:user :role])}))
   {:uri "/x" :headers {"x-session-token" token}}))

(deftest ^:security ^:unit revoke-all-rejects-the-next-request
  (let [st (stores) svc (replica st)
        [user token] (sign-in! svc st)]
    (is (= 200 (:status (request! svc token))))
    (ports/logout-user-everywhere svc (:id user))
    (is (= 401 (:status (request! svc token))))))

(deftest ^:security ^:unit revocation-on-another-replica-rejects-the-next-request
  (let [st (stores) a (replica st) b (replica st)
        [user token] (sign-in! a st)]
    (is (= 200 (:status (request! b token))))
    (ports/logout-user-everywhere a (:id user))
    (is (= 401 (:status (request! b token))))))

(deftest ^:security ^:unit deactivated-user-is-rejected-on-the-next-request
  (testing "same replica"
    (let [st (stores) svc (replica st)
          [user token] (sign-in! svc st)]
      (is (= 200 (:status (request! svc token))))
      (ports/deactivate-user svc (:id user))
      (is (= 401 (:status (request! svc token))))))
  (testing "another replica"
    (let [st (stores) a (replica st) b (replica st)
          [user token] (sign-in! a st)]
      (is (= 200 (:status (request! b token))))
      (ports/deactivate-user a (:id user))
      (is (= 401 (:status (request! b token)))))))

(deftest ^:security ^:unit role-change-is-seen-on-the-next-request
  (let [st (stores) svc (replica st)
        [user token] (sign-in! svc st)]
    (is (= :user (:body (request! svc token))))
    ;; Written straight to the store, as another replica or an operator would.
    (ports/update-user (:users st) (assoc (ports/find-user-by-id (:users st) (:id user))
                                          :role :admin))
    (is (= :admin (:body (request! svc token))))))

(deftest ^:security ^:unit validate-session-keeps-updating-last-access
  (let [st (stores) svc (replica st)
        [_ token] (sign-in! svc st)
        first-seen (:last-accessed-at (ports/validate-session svc token))]
    (Thread/sleep 5)
    (is (.isAfter ^Instant (:last-accessed-at (ports/validate-session svc token))
                  ^Instant first-seen))))


(deftest ^:security ^:unit the-user-service-is-not-handed-the-cache
  (is (not (contains? (-> (wiring/ig-config nil {:config {} :enabled #{:wagoe/cache}})
                          (get-in [:components :wagoe/user-service]))
                      :cache))))
