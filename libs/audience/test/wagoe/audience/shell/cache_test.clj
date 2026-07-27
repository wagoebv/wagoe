(ns wagoe.audience.shell.cache-test
  "Contract tests for AudienceCache (L1 DB-backed) against H2.

   Uses the same H2 setup as persistence_test. Each test runs in its own
   fixture cycle so the DB is fully reset between tests."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [wagoe.audience.ports :as ports]
            [wagoe.audience.shell.cache :as cache]
            [wagoe.audience.shell.persistence :as persistence]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as connection])
  (:import [java.util UUID]
           [com.zaxxer.hikari HikariDataSource]))

;; =============================================================================
;; Test database setup  (same DDL as persistence_test)
;; =============================================================================

(def ^:private test-datasource (atom nil))
(def ^:private test-store      (atom nil))
(def ^:private test-cache      (atom nil))

(defn- setup-test-db []
  (let [^HikariDataSource ds
        (connection/->pool
         com.zaxxer.hikari.HikariDataSource
         {:jdbcUrl  "jdbc:h2:mem:audience-cache-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
          :username "sa"
          :password ""})]
    (reset! test-datasource ds)

    (jdbc/execute! ds
                   ["CREATE TABLE IF NOT EXISTS audience_segments (
                      id            UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
                      audience_id   VARCHAR(255) NOT NULL UNIQUE,
                      label         VARCHAR(255) NOT NULL,
                      description   TEXT,
                      filters       TEXT NOT NULL,
                      composition   TEXT,
                      cache_config  TEXT,
                      tags          TEXT,
                      member_count  INTEGER DEFAULT 0,
                      cached_at     TIMESTAMP,
                      source        VARCHAR(50) DEFAULT 'dynamic',
                      created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )"])

    (jdbc/execute! ds
                   ["CREATE TABLE IF NOT EXISTS audience_memberships (
                      audience_id   UUID REFERENCES audience_segments(id) ON DELETE CASCADE,
                      user_id       UUID NOT NULL,
                      entered_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      PRIMARY KEY (audience_id, user_id)
                    )"])

    (jdbc/execute! ds
                   ["CREATE INDEX IF NOT EXISTS idx_audience_memberships_user
                      ON audience_memberships(user_id)"])

    (reset! test-store (persistence/create-audience-store ds))
    (reset! test-cache (cache/create-audience-cache ds))))

(defn- teardown-test-db []
  (when-let [ds @test-datasource]
    (try
      (jdbc/execute! ds ["DROP ALL OBJECTS"])
      (.close ^HikariDataSource ds)
      (catch Exception _e nil))
    (reset! test-datasource nil)
    (reset! test-store nil)
    (reset! test-cache nil)))

(defn- db-fixture [test-fn]
  (setup-test-db)
  (try
    (test-fn)
    (finally
      (teardown-test-db))))

(use-fixtures :each db-fixture)

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- save-segment!
  "Persist a minimal audience segment so FK constraints are satisfied."
  [audience-id]
  (ports/save-audience @test-store {:id      audience-id
                                    :label   (name audience-id)
                                    :filters []}))

(defn- make-result
  "Build a minimal SegmentResult for caching."
  [user-ids]
  {:user-ids     (set user-ids)
   :count        (count user-ids)
   :cached?      false
   :evaluated-at (java.time.Instant/now)})

;; =============================================================================
;; put-cached + get-cached-with-ttl
;; =============================================================================

(deftest ^:integration put-and-get-cached
  (testing "put result, then get within TTL returns cached result"
    (save-segment! :seg-put)
    (let [u1      (UUID/randomUUID)
          u2      (UUID/randomUUID)
          result  (make-result [u1 u2])]
      (ports/put-cached @test-cache :seg-put result 60)
      (let [hit (cache/get-cached-with-ttl @test-cache :seg-put 60)]
        (is (some? hit))
        (is (true? (:cached? hit)))
        (is (= #{u1 u2} (:user-ids hit)))
        (is (= 2 (:count hit)))
        (is (inst? (:evaluated-at hit))))))

  (testing "put result, get with 0-minute TTL returns nil (immediately stale)"
    (save-segment! :seg-stale)
    (let [u (UUID/randomUUID)]
      (ports/put-cached @test-cache :seg-stale (make-result [u]) 60)
      (let [hit (cache/get-cached-with-ttl @test-cache :seg-stale 0)]
        (is (nil? hit))))))

;; =============================================================================
;; get-cached-with-ttl — unknown segment
;; =============================================================================

(deftest ^:integration get-cached-unknown-segment
  (testing "get for unknown audience-id returns nil"
    (let [hit (cache/get-cached-with-ttl @test-cache :no-such-segment 60)]
      (is (nil? hit)))))

;; =============================================================================
;; IAudienceCache 1-arity get-cached
;; =============================================================================

(deftest ^:integration get-cached-protocol-arity
  (testing "get-cached (1-arity) returns result when cached_at is set"
    (save-segment! :seg-proto)
    (let [u (UUID/randomUUID)]
      (ports/put-cached @test-cache :seg-proto (make-result [u]) 60)
      (let [hit (ports/get-cached @test-cache :seg-proto)]
        (is (some? hit))
        (is (true? (:cached? hit))))))

  (testing "get-cached (1-arity) returns nil when no stamp set"
    (save-segment! :seg-no-stamp)
    (let [hit (ports/get-cached @test-cache :seg-no-stamp)]
      (is (nil? hit)))))

;; =============================================================================
;; invalidate — single segment
;; =============================================================================

(deftest ^:integration invalidate-single-segment
  (testing "invalidate clears cached_at and memberships"
    (save-segment! :seg-inv)
    (let [u (UUID/randomUUID)]
      (ports/put-cached @test-cache :seg-inv (make-result [u]) 60)
      ;; Verify it is cached first
      (is (some? (cache/get-cached-with-ttl @test-cache :seg-inv 60)))
      ;; Invalidate
      (ports/invalidate @test-cache :seg-inv)
      ;; Now should be nil
      (is (nil? (cache/get-cached-with-ttl @test-cache :seg-inv 60)))
      ;; Memberships should be gone
      (is (empty? (persistence/get-memberships @test-datasource :seg-inv))))))

;; =============================================================================
;; invalidate-all
;; =============================================================================

(deftest ^:integration invalidate-all-segments
  (testing "invalidate-all clears cached_at and memberships for every segment"
    (save-segment! :seg-all-a)
    (save-segment! :seg-all-b)
    (let [u1 (UUID/randomUUID)
          u2 (UUID/randomUUID)]
      (ports/put-cached @test-cache :seg-all-a (make-result [u1]) 60)
      (ports/put-cached @test-cache :seg-all-b (make-result [u2]) 60)
      ;; Both should be cached
      (is (some? (cache/get-cached-with-ttl @test-cache :seg-all-a 60)))
      (is (some? (cache/get-cached-with-ttl @test-cache :seg-all-b 60)))
      ;; Invalidate all
      (ports/invalidate-all @test-cache)
      ;; Both should be nil now
      (is (nil? (cache/get-cached-with-ttl @test-cache :seg-all-a 60)))
      (is (nil? (cache/get-cached-with-ttl @test-cache :seg-all-b 60))))))

;; =============================================================================
;; TTL freshness boundary
;; =============================================================================

(deftest ^:integration ttl-freshness
  (testing "result is stale immediately when TTL is 0 minutes"
    (save-segment! :seg-ttl0)
    (let [u (UUID/randomUUID)]
      (ports/put-cached @test-cache :seg-ttl0 (make-result [u]) 60)
      (is (nil? (cache/get-cached-with-ttl @test-cache :seg-ttl0 0)))))

  (testing "result is fresh with a large TTL"
    (save-segment! :seg-ttl-big)
    (let [u (UUID/randomUUID)]
      (ports/put-cached @test-cache :seg-ttl-big (make-result [u]) 1440)
      (is (some? (cache/get-cached-with-ttl @test-cache :seg-ttl-big 1440))))))
