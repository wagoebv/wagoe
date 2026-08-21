(ns wagoe.user.shell.auth-lockout-fixation-security-test
  "End-to-end ^:security tests for two auth defenses that had no security-tagged
   coverage (BOU-168):

   - Brute-force lockout: after :max-failed-attempts wrong passwords the account
     locks, and even the CORRECT password is rejected until :lockout-until.
   - Session fixation: session tokens are server-minted with SecureRandom, so a
     client can neither predict nor fix them; every login yields a fresh token.

   Drives the real `wagoe.user.shell.auth/authenticate-user` against an
   atom-backed repository so the pure lockout logic and its shell enforcement are
   exercised together (not the pure fns in isolation)."
  (:require [wagoe.user.ports :as ports]
            [wagoe.user.shell.auth :as auth]
            [clojure.test :refer [deftest is testing]])
  (:import [java.time Instant]
           [java.util UUID]))

;; =============================================================================
;; Atom-backed harness
;; =============================================================================

(def ^:private correct-password "Correct-Horse-1!")

(defn- seed-user []
  {:id                 (UUID/randomUUID)
   :email              "victim@example.com"
   :password-hash      (auth/hash-password correct-password)
   :role               :user
   :active             true
   :deleted-at         nil
   :mfa-enabled        false
   :failed-login-count 0
   :lockout-until      nil})

(defn- auth-service
  "Build an auth-service whose repository reads/writes a single user held in
   `state`. update-user merges, mirroring the real persistence contract."
  [state config]
  {:auth-config        config
   :user-repository    (reify ports/IUserRepository
                         (find-user-by-email [_ email]
                           (when (= email (:email @state)) @state))
                         (update-user [_ entity]
                           (swap! state merge entity)
                           @state))
   :session-repository (reify ports/IUserSessionRepository
                         (find-sessions-by-user [_ _] [])
                         (create-session [_ s] s))
   :mfa-service        nil})

(defn- login [svc password]
  (auth/authenticate-user svc "victim@example.com" password
                          {:ip-address "203.0.113.9" :user-agent "test"}))

;; =============================================================================
;; Brute-force lockout
;; =============================================================================

(deftest ^:security ^:unit lockout-blocks-correct-password-after-max-attempts
  (testing "N wrong passwords lock the account, then the correct password is refused"
    (let [state  (atom (seed-user))
          config {:max-failed-attempts 3 :lockout-duration-minutes 15}
          svc    (auth-service state config)]
      (testing "each wrong attempt fails as a plain credential error (no lockout signal yet)"
        (dotimes [_ 3]
          (let [result (login svc "wrong-password")]
            (is (false? (:success? result)))
            ;; :retry-after is lockout-specific — proving it is absent here is
            ;; what lets the locked assertion below distinguish lockout from a
            ;; mere wrong password.
            (is (nil? (get-in result [:error :retry-after]))))))
      (testing "the account is now locked in persisted state"
        (is (= 3 (:failed-login-count @state)))
        (is (some? (:lockout-until @state)) "lockout-until is set")
        (is (.isAfter ^Instant (:lockout-until @state) (Instant/now))
            "lockout is in the future"))
      (testing "the CORRECT password is rejected while locked (brute-force defense)"
        (let [result (login svc correct-password)]
          (is (false? (:success? result)) "correct password still refused")
          (is (= :authentication-failed (get-in result [:error :type])))
          (is (some? (get-in result [:error :retry-after])) "response tells the client when to retry"))))))

(deftest ^:security ^:unit successful-login-resets-failed-count
  (testing "a correct password before the threshold clears accrued failures"
    (let [state  (atom (seed-user))
          config {:max-failed-attempts 5 :lockout-duration-minutes 15}
          svc    (auth-service state config)]
      (is (false? (:success? (login svc "wrong-password"))))
      (is (= 1 (:failed-login-count @state)))
      (is (true? (:success? (login svc correct-password))) "correct password authenticates")
      (is (= 0 (:failed-login-count @state)) "failure count reset on success")
      (is (nil? (:lockout-until @state)) "no lockout after a clean login"))))

;; =============================================================================
;; Session fixation
;; =============================================================================

(deftest ^:security ^:unit session-tokens-are-unpredictable
  (testing "generate-session-token yields unique, URL-safe, high-entropy tokens"
    (let [tokens (repeatedly 1000 auth/generate-session-token)]
      (is (= 1000 (count (set tokens))) "no collisions across 1000 tokens")
      (is (every? #(>= (count %) 40) tokens) "≥ 32 bytes of entropy")
      (is (every? #(re-matches #"[A-Za-z0-9_-]+" %) tokens)
          "URL-safe base64, no padding/reserved chars a client could exploit"))))

(deftest ^:security ^:unit login-mints-a-fresh-server-side-token
  (testing "each successful login issues a new server-generated session token"
    (let [config {:max-failed-attempts 5 :lockout-duration-minutes 15}
          t1     (:session-token (:session (login (auth-service (atom (seed-user)) config) correct-password)))
          t2     (:session-token (:session (login (auth-service (atom (seed-user)) config) correct-password)))]
      (is (some? t1) "login establishes a session token")
      (is (not= t1 t2) "tokens are freshly minted per login — a fixed value cannot be reused"))))
