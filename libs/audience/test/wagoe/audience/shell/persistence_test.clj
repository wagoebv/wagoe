(ns wagoe.audience.shell.persistence-test
  "Contract tests for AudienceStore persistence layer against H2.

   Uses H2 in-memory database (PostgreSQL compatibility mode) to verify
   all IAudienceRepository operations:
   - save-audience (upsert)
   - find-audience
   - list-audiences
   - delete-audience
   - save-memberships! / get-memberships / clear-memberships!"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [wagoe.audience.ports :as ports]
            [wagoe.audience.shell.persistence :as persistence]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as connection])
  (:import [java.util UUID]
           [com.zaxxer.hikari HikariDataSource]))

;; =============================================================================
;; Test database setup
;; =============================================================================

(def ^:private test-datasource (atom nil))
(def ^:private test-store      (atom nil))

(defn- setup-test-db []
  (let [^HikariDataSource ds
        (connection/->pool
         com.zaxxer.hikari.HikariDataSource
         {:jdbcUrl  "jdbc:h2:mem:audience-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
          :username "sa"
          :password ""})]
    (reset! test-datasource ds)

    ;; H2-compatible DDL: RANDOM_UUID() replaces gen_random_uuid(), TEXT replaces JSONB.
    ;; In H2 PostgreSQL mode DEFAULT must appear before PRIMARY KEY.
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

    (reset! test-store (persistence/create-audience-store ds))))

(defn- teardown-test-db []
  (when-let [ds @test-datasource]
    (try
      (jdbc/execute! ds ["DROP ALL OBJECTS"])
      (.close ^HikariDataSource ds)
      (catch Exception _e nil))
    (reset! test-datasource nil)
    (reset! test-store nil)))

(defn- db-fixture [test-fn]
  (setup-test-db)
  (try
    (test-fn)
    (finally
      (teardown-test-db))))

(use-fixtures :each db-fixture)

;; =============================================================================
;; save-audience / find-audience
;; =============================================================================

(deftest ^:contract save-and-find-audience
  (testing "save audience then find by keyword id"
    (let [definition {:id          :test-segment
                      :label       "Test Segment"
                      :description "For testing"
                      :filters     [{:type :demographics :field :plan :op :eq :value "premium"}]
                      :tags        [:test]}]
      (ports/save-audience @test-store definition)
      (let [found (ports/find-audience @test-store :test-segment)]
        (is (= :test-segment (:id found)))
        (is (= "Test Segment" (:label found)))
        (is (= "For testing" (:description found)))
        (is (= 1 (count (:filters found)))))))

  (testing "returns nil for unknown audience-id"
    (is (nil? (ports/find-audience @test-store :does-not-exist))))

  (testing "upsert overwrites existing audience on re-save"
    (let [definition {:id    :upsert-test
                      :label "Original"
                      :filters []}]
      (ports/save-audience @test-store definition)
      (ports/save-audience @test-store (assoc definition :label "Updated"))
      (let [found (ports/find-audience @test-store :upsert-test)]
        (is (= "Updated" (:label found)))))))

;; =============================================================================
;; list-audiences
;; =============================================================================

(deftest ^:contract list-audiences
  (testing "list returns all saved audiences"
    (ports/save-audience @test-store {:id :seg-a :label "A" :filters [] :tags [:test]})
    (ports/save-audience @test-store {:id :seg-b :label "B" :filters [] :tags [:test]})
    (let [all (ports/list-audiences @test-store)]
      (is (= 2 (count all)))
      (is (= #{:seg-a :seg-b} (set (map :id all)))))))

(deftest ^:contract list-audiences-empty
  (testing "list returns empty vector when no audiences exist"
    (is (empty? (ports/list-audiences @test-store)))))

;; =============================================================================
;; delete-audience
;; =============================================================================

(deftest ^:contract delete-audience
  (testing "delete removes audience"
    (ports/save-audience @test-store {:id :del-test :label "Del" :filters []})
    (ports/delete-audience @test-store :del-test)
    (is (nil? (ports/find-audience @test-store :del-test))))

  (testing "delete of non-existent audience is a no-op"
    (is (nil? (ports/delete-audience @test-store :nonexistent)))))

;; =============================================================================
;; membership operations
;; =============================================================================

(deftest ^:contract membership-operations
  (testing "save and query membership records"
    (let [user-id-1 (UUID/randomUUID)
          user-id-2 (UUID/randomUUID)]
      (ports/save-audience @test-store {:id :mem-test :label "Mem" :filters []})
      (persistence/save-memberships! @test-datasource :mem-test #{user-id-1 user-id-2})
      (let [members (persistence/get-memberships @test-datasource :mem-test)]
        (is (= #{user-id-1 user-id-2} (set members))))))

  (testing "clear-memberships! removes all members for a segment"
    (let [user-id (UUID/randomUUID)]
      (ports/save-audience @test-store {:id :clear-test :label "Clear" :filters []})
      (persistence/save-memberships! @test-datasource :clear-test #{user-id})
      (persistence/clear-memberships! @test-datasource :clear-test)
      (is (empty? (persistence/get-memberships @test-datasource :clear-test)))))

  (testing "save-memberships! is idempotent"
    (let [user-id (UUID/randomUUID)]
      (ports/save-audience @test-store {:id :idem-test :label "Idem" :filters []})
      (persistence/save-memberships! @test-datasource :idem-test #{user-id})
      ;; Saving the same set again should not throw
      (persistence/save-memberships! @test-datasource :idem-test #{user-id})
      (is (= #{user-id} (set (persistence/get-memberships @test-datasource :idem-test))))))

  (testing "memberships cascade-delete when audience is deleted"
    (let [user-id (UUID/randomUUID)]
      (ports/save-audience @test-store {:id :cascade-test :label "Cascade" :filters []})
      (persistence/save-memberships! @test-datasource :cascade-test #{user-id})
      (ports/delete-audience @test-store :cascade-test)
      ;; After delete, get-memberships returns empty (segment gone)
      (is (empty? (persistence/get-memberships @test-datasource :cascade-test))))))
