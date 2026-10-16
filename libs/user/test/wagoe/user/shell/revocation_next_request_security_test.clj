(ns wagoe.user.shell.revocation-next-request-security-test
  "Revocation, deactivation and role changes are seen on the very next request
   (BOU-663)."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.auth :as auth]
            [wagoe.user.shell.in-memory-repository-test :as mem]
            [wagoe.user.shell.middleware :as middleware]
            [wagoe.user.shell.module-wiring :as wiring]
            [wagoe.user.shell.service :as service]
            [wagoe.user.shell.web-handlers :as web-handlers])
  (:import [java.time Instant]
           [java.util UUID]))

(defn- replica
  "A user service on the shared stores — one process of a deployment."
  [{:keys [users sessions audit]}]
  (service/create-user-service users sessions audit
                               {:password-policy {:min-length 12}}
                               nil))

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
      (is (= 401 (:status (request! b token))))))
  (testing "inactive without a soft delete, written straight to the store"
    (let [st (stores) svc (replica st)
          [user token] (sign-in! svc st)]
      (is (= 200 (:status (request! svc token))))
      (ports/update-user (:users st) (assoc (ports/find-user-by-id (:users st) (:id user))
                                            :active false))
      (is (= 401 (:status (request! svc token)))))))

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

;; BOU-668: every auth path refuses an inactive user, not only the session
;; middleware.

(defn- deactivate-in-store! [{:keys [users]} user]
  (ports/update-user users (assoc (ports/find-user-by-id users (:id user)) :active false)))

(deftest ^:security ^:unit an-inactive-users-session-does-not-validate
  (let [st (stores) svc (replica st)
        [user token] (sign-in! svc st)]
    (is (= (:id user) (get-in (ports/validate-session svc token) [:user :id]))
        "a valid session carries its user")
    (deactivate-in-store! st user)
    (is (= [:session-invalid :user-inactive]
           (try (ports/validate-session svc token) nil
                (catch clojure.lang.ExceptionInfo e ((juxt :type :reason) (ex-data e))))))))

(deftest ^:security ^:unit the-web-root-does-not-send-an-inactive-user-to-the-dashboard
  (let [st (stores) svc (replica st)
        [user token] (sign-in! svc st)
        root (web-handlers/web-root-page-handler svc {})
        hit  #(root {:uri "/web" :cookies {"session-token" {:value token}}})]
    (is (= 302 (:status (hit))))
    (deactivate-in-store! st user)
    (is (= 200 (:status (hit))) "the landing page, not a redirect")))

(deftest ^:security ^:unit a-deactivated-users-jwt-is-refused
  (let [st (stores) svc (replica st)
        [user _] (sign-in! svc st)
        token (auth/create-jwt-token user 1)
        call  #((middleware/jwt-authentication-middleware svc (constantly {:status 200}))
                {:uri "/x" :headers {"authorization" (str "Bearer " token)}})]
    (is (= 200 (:status (call))))
    (ports/deactivate-user svc (:id user))
    (is (= 401 (:status (call))))))

;; BOU-681: deactivation is not a delete.

(deftest ^:unit a-deactivated-user-stays-listed-and-can-be-reactivated
  (let [st (stores) svc (replica st)
        [user _] (sign-in! svc st)]
    (is (true? (ports/deactivate-user svc (:id user))))
    (let [listed (first (:users (ports/list-users svc {})))]
      (is (= (:id user) (:id listed)) "still listed")
      (is (false? (:active listed)))
      (is (nil? (:deleted-at listed))))
    (ports/update-user-profile svc {:id (:id user) :active true})
    (is (true? (:active (ports/get-user-by-id svc (:id user)))))))

(deftest ^:security ^:unit a-deleted-user-leaves-the-list-and-loses-their-session
  (let [st (stores) svc (replica st)
        [user token] (sign-in! svc st)]
    (is (true? (ports/delete-user svc (:id user))))
    (is (zero? (:total-count (ports/list-users svc {}))) "hidden from the list")
    (is (nil? (ports/get-user-by-id svc (:id user))))
    (is (= 401 (:status (request! svc token))))))

(deftest ^:security ^:unit the-last-admin-cannot-be-deleted
  (let [st    (stores) svc (replica st)
        admin (ports/register-user svc {:email "root@example.com" :name "Root" :role :admin
                                        :password "Correct-horse-battery-9"})]
    (is (= :conflict
           (try (ports/delete-user svc (:id admin)) nil
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
    (is (some? (ports/get-user-by-id svc (:id admin))))))
