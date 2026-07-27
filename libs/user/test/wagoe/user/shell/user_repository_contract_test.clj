(ns wagoe.user.shell.user-repository-contract-test
  "Contract tests: real H2 round-trips through DatabaseUserRepository and
   DatabaseUserSessionRepository.

   These guard the two AGENTS.md pitfalls that live in the persistence boundary:
   - Pitfall #1 (snake/kebab): the historical `:password_hash` auth bug — a value
     written under a kebab key must survive the snake round-trip and come back
     under `:password-hash`.
   - Pitfall #6 (schema/DB mismatch): optional, instant, enum, boolean and
     JSON-vector fields must survive a real create→read cycle (nil-in→nil-out,
     instant fidelity, keyword enums, MFA backup-code vectors).

   The schema is built from the production Malli definitions via
   `initialize-user-schema!`, so the tables cannot drift from the insert/select
   column set the repository actually uses."
  (:require [wagoe.user.ports :as ports]
            [wagoe.user.shell.persistence :as persistence]
            [wagoe.platform.shell.adapters.database.h2.core :as h2]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [next.jdbc.connection :as connection])
  (:import [java.time Instant]
           [java.util UUID]
           [com.zaxxer.hikari HikariDataSource]))

;; =============================================================================
;; H2 harness
;; =============================================================================

(def ^:private db-ctx (atom nil))
(def ^:private user-repo (atom nil))
(def ^:private session-repo (atom nil))

(defn- setup-db []
  (let [^HikariDataSource ds
        (connection/->pool
         com.zaxxer.hikari.HikariDataSource
         ;; Unique DB name per setup: this ns is collected by BOTH the :unit
         ;; aggregate suite and the :user suite, so it runs twice in one JVM. A
         ;; fixed name + DB_CLOSE_DELAY=-1 would keep the first DB alive and the
         ;; second run would re-init schema on top of it (initialize-user-schema!
         ;; is not idempotent for CHECK constraints), corrupting them.
         {:jdbcUrl (str "jdbc:h2:mem:user-contract-" (UUID/randomUUID) ";MODE=PostgreSQL"
                        ";DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
          :username "sa" :password ""})
        ctx {:datasource ds :adapter (h2/new-adapter)}]
    (persistence/initialize-user-schema! ctx)
    (reset! db-ctx ctx)
    (reset! user-repo (persistence/create-user-repository ctx))
    (reset! session-repo (persistence/create-session-repository ctx))))

(defn- teardown-db []
  (when-let [^HikariDataSource ds (:datasource @db-ctx)]
    (.close ds))
  (reset! db-ctx nil)
  (reset! user-repo nil)
  (reset! session-repo nil))

(use-fixtures :once (fn [f] (setup-db) (try (f) (finally (teardown-db)))))

;; =============================================================================
;; Entity builders
;; =============================================================================

(defn- full-user-entity
  "A user entity with every persisted field set to a distinctive non-nil value."
  []
  {:email                 (str "ada-" (UUID/randomUUID) "@example.com")
   :password-hash         "$2a$10$O9k8Jd2fPq7bHfWnZ4xYeu"   ; Pitfall #1 sentinel
   :active                true
   :mfa-enabled           true
   :mfa-secret            "JBSWY3DPEHPK3PXP"
   :mfa-backup-codes      ["code-1" "code-2" "code-3"]
   :mfa-backup-codes-used ["code-0"]
   :mfa-enabled-at        (Instant/parse "2026-01-02T03:04:05Z")
   :failed-login-count    2
   :lockout-until         (Instant/parse "2026-06-01T00:00:00Z")
   :tenant-id             (UUID/randomUUID)
   :name                  "Ada Lovelace"
   :role                  :admin
   :avatar-url            "https://example.com/ada.png"
   :login-count           7
   :last-login            (Instant/parse "2026-05-05T05:05:05Z")
   :notifications-email   true
   :notifications-push    false
   :notifications-sms     true
   :theme                 :dark
   :language              "en"
   :timezone              "UTC"
   :date-format           :iso
   :time-format           :24h})

(defn- minimal-user-entity
  "Only the NOT-NULL fields plus the credential; every optional left unset so a
   round-trip must return nil (Pitfall #6 nil-in→nil-out)."
  []
  {:email         (str "min-" (UUID/randomUUID) "@example.com")
   :password-hash "$2a$10$minimalHashValueForTest01"
   :active        true
   :name          "Min User"
   :role          :user})

;; =============================================================================
;; User repository round-trips
;; =============================================================================

(deftest ^:contract create-and-find-by-id-round-trip
  (testing "create-user → find-user-by-id preserves credential + typed fields"
    (let [entity   (full-user-entity)
          created  (ports/create-user @user-repo entity)
          fetched  (ports/find-user-by-id @user-repo (:id created))]
      (is (some? (:id created)) "create-user assigns an id")
      (is (some? fetched) "user is found by id")
      ;; Pitfall #1: the credential written under :password-hash survives.
      (is (= (:password-hash entity) (:password-hash fetched))
          "password-hash survives the snake/kebab DB round-trip")
      (is (= (:email entity) (:email fetched)))
      (is (= (:name entity) (:name fetched)))
      ;; typed fields come back as domain types, not raw strings
      (is (= :admin (:role fetched)))
      (is (true? (:active fetched)))
      (is (false? (:notifications-push fetched)))
      (is (= :dark (:theme fetched)))
      (is (= :iso (:date-format fetched)))
      (is (= :24h (:time-format fetched))))))

(deftest ^:contract create-and-find-by-email-round-trip
  (testing "find-user-by-email returns the same credential"
    (let [entity  (full-user-entity)
          _       (ports/create-user @user-repo entity)
          fetched (ports/find-user-by-email @user-repo (:email entity))]
      (is (some? fetched) "user is found by email")
      (is (= (:password-hash entity) (:password-hash fetched))
          "password-hash survives find-by-email"))))

(deftest ^:contract optional-instant-and-json-fields-round-trip
  (testing "instants, enums and MFA JSON vectors survive create→read (Pitfall #6)"
    (let [entity  (full-user-entity)
          created (ports/create-user @user-repo entity)
          fetched (ports/find-user-by-id @user-repo (:id created))]
      (is (= (:last-login entity) (:last-login fetched)) "instant field fidelity")
      (is (= (:lockout-until entity) (:lockout-until fetched)))
      (is (= (:mfa-enabled-at entity) (:mfa-enabled-at fetched)))
      (is (= (:mfa-backup-codes entity) (:mfa-backup-codes fetched))
          "MFA backup-code vector survives JSON round-trip")
      (is (= (:mfa-backup-codes-used entity) (:mfa-backup-codes-used fetched)))
      (is (= (:tenant-id entity) (:tenant-id fetched)) "UUID field fidelity"))))

(deftest ^:contract nil-optionals-round-trip-as-nil
  (testing "unset optional fields come back nil, not empty strings or crashes"
    (let [entity  (minimal-user-entity)
          created (ports/create-user @user-repo entity)
          fetched (ports/find-user-by-id @user-repo (:id created))]
      (is (some? fetched))
      (is (= (:password-hash entity) (:password-hash fetched)))
      (is (nil? (:last-login fetched)))
      (is (nil? (:lockout-until fetched)))
      (is (nil? (:mfa-secret fetched)))
      (is (nil? (:mfa-backup-codes fetched)))
      (is (nil? (:avatar-url fetched)))
      (is (nil? (:tenant-id fetched))))))

(deftest ^:contract update-user-round-trip
  (testing "update-user persists changes to both credential and profile"
    (let [created (ports/create-user @user-repo (full-user-entity))
          updated (ports/update-user @user-repo (assoc created
                                                       :name "Ada B. Lovelace"
                                                       :password-hash "$2a$10$rotatedHashValue000001"
                                                       :role :user))
          fetched (ports/find-user-by-id @user-repo (:id created))]
      (is (some? updated))
      (is (= "Ada B. Lovelace" (:name fetched)) "profile change persisted")
      (is (= "$2a$10$rotatedHashValue000001" (:password-hash fetched))
          "credential change persisted")
      (is (= :user (:role fetched))))))

(deftest ^:contract soft-delete-excludes-from-finds
  (testing "soft-delete-user hides the user from find-user-by-id"
    (let [created (ports/create-user @user-repo (full-user-entity))]
      (ports/soft-delete-user @user-repo (:id created))
      (is (nil? (ports/find-user-by-id @user-repo (:id created)))
          "soft-deleted user is excluded"))))

(deftest ^:contract hard-delete-removes-user
  (testing "hard-delete-user removes the user from both tables"
    (let [created (ports/create-user @user-repo (full-user-entity))]
      (ports/hard-delete-user @user-repo (:id created))
      (is (nil? (ports/find-user-by-id @user-repo (:id created)))
          "hard-deleted user is gone")
      (is (nil? (ports/find-user-by-email @user-repo (:email created)))
          "hard-deleted user is gone by email too"))))

;; =============================================================================
;; Session repository round-trips (zero H2 coverage before this)
;; =============================================================================

(defn- new-user-id []
  (:id (ports/create-user @user-repo (full-user-entity))))

(deftest ^:contract session-create-and-find-by-token-round-trip
  (testing "create-session → find-session-by-token preserves fields"
    (let [uid     (new-user-id)
          token   (str "tok-" (UUID/randomUUID))
          _       (ports/create-session @session-repo
                                        {:user-id     uid
                                         :session-token token
                                         :expires-at  (.plusSeconds (Instant/now) 3600)
                                         :user-agent  "contract-agent/1.0"
                                         :ip-address  "203.0.113.7"})
          fetched (ports/find-session-by-token @session-repo token)]
      (is (some? fetched) "active session found by token")
      (is (= uid (:user-id fetched)) "user-id survives round-trip")
      (is (= token (:session-token fetched)))
      (is (= "203.0.113.7" (:ip-address fetched)))
      (is (some? (:last-accessed-at fetched)) "find bumps last-accessed-at"))))

(deftest ^:contract session-invalidate-hides-from-find
  (testing "invalidate-session makes the token un-findable"
    (let [uid   (new-user-id)
          token (str "tok-" (UUID/randomUUID))]
      (ports/create-session @session-repo
                            {:user-id uid :session-token token
                             :expires-at (.plusSeconds (Instant/now) 3600)})
      (is (some? (ports/find-session-by-token @session-repo token)))
      (is (true? (ports/invalidate-session @session-repo token)))
      (is (nil? (ports/find-session-by-token @session-repo token))
          "revoked session is excluded"))))

(deftest ^:contract expired-session-not-returned
  (testing "find-session-by-token ignores expired sessions"
    (let [uid   (new-user-id)
          token (str "tok-" (UUID/randomUUID))]
      (ports/create-session @session-repo
                            {:user-id uid :session-token token
                             :expires-at (.minusSeconds (Instant/now) 60)})
      (is (nil? (ports/find-session-by-token @session-repo token))
          "expired session is excluded"))))

(deftest ^:contract find-sessions-by-user-returns-active
  (testing "find-sessions-by-user returns the user's active sessions"
    (let [uid    (new-user-id)
          token  (str "tok-" (UUID/randomUUID))]
      (ports/create-session @session-repo
                            {:user-id uid :session-token token
                             :expires-at (.plusSeconds (Instant/now) 3600)})
      (let [sessions (ports/find-sessions-by-user @session-repo uid)]
        (is (= 1 (count sessions)))
        (is (= token (:session-token (first sessions))))))))
