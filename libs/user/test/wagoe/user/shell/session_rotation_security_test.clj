(ns wagoe.user.shell.session-rotation-security-test
  "^:security tests for BOU-191 — session rotation on credential/privilege change.

   A password change (credential rotation) must invalidate every session for the
   user, and a role change (privilege change) must invalidate the user's sessions
   so a live session cannot retain old privileges. An ordinary profile edit must
   NOT churn sessions."
  (:require [wagoe.user.ports :as ports]
            [wagoe.user.shell.service :as sut]
            [wagoe.user.shell.auth :as auth-shell]
            [clojure.test :refer [deftest is testing]])
  (:import [java.time Instant]
           [java.util UUID]))

(defn- user-repo [state]
  (reify ports/IUserRepository
    (find-user-by-id [_ id] (get @state id))
    (update-user [_ u] (swap! state assoc (:id u) u) u)))

(defn- session-repo
  "Records every invalidate-all-user-sessions call into `calls`."
  [calls]
  (reify ports/IUserSessionRepository
    (invalidate-all-user-sessions [_ user-id] (swap! calls conj user-id) 1)))

(defn- audit-repo []
  (reify ports/IUserAuditRepository
    (create-audit-log [_ _] nil)))

(defn- service [state calls]
  (sut/->UserService (user-repo state) (session-repo calls) (audit-repo)
                     {:password-policy {:min-length 8}} nil nil))

(def ^:private uid (UUID/randomUUID))
(def ^:private hash-60 (apply str (repeat 60 "x"))) ; schema requires a 60-char hash

(defn- base-user []
  {:id uid :email "u@example.io" :name "Test User" :password-hash hash-60
   :role :user :active true
   :created-at (Instant/parse "2026-03-20T09:00:00Z") :updated-at nil :deleted-at nil})

(deftest ^:security ^:unit password-change-invalidates-all-sessions
  (testing "changing the password kills every session for the user"
    (let [state (atom {uid (base-user)})
          calls (atom [])]
      (with-redefs [auth-shell/verify-password (fn [p h] (and (= "Current123!" p) (= hash-60 h)))
                    auth-shell/hash-password    (fn [_] (apply str (repeat 60 "y")))]
        (is (true? (ports/change-password (service state calls) uid "Current123!" "BetterPass123!")))
        (is (= [uid] @calls)
            "invalidate-all-user-sessions must be called for the user on password change")))))

(deftest ^:security ^:unit role-change-invalidates-sessions
  (testing "a privilege change forces existing sessions to re-establish"
    (let [state (atom {uid (base-user)})
          calls (atom [])]
      (is (some? (ports/update-user-profile (service state calls) (assoc (base-user) :role :admin))))
      (is (= [uid] @calls) "sessions invalidated when :role changes"))))

(deftest ^:security ^:unit non-privilege-update-does-not-invalidate-sessions
  (testing "an ordinary profile edit does not churn sessions"
    (let [state (atom {uid (base-user)})
          calls (atom [])]
      (is (some? (ports/update-user-profile (service state calls) (assoc (base-user) :email "changed@example.io"))))
      (is (empty? @calls) "no session invalidation when the role is unchanged"))))
