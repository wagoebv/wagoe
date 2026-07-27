(ns wagoe.tenant.core.tenant-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.tenant.core.tenant :as sut])
  (:import (java.time Instant)
           (java.util UUID)))

(deftest ^:unit valid-slug?-test
  (testing "valid slugs"
    (is (true? (sut/valid-slug? "acme-corp")))
    (is (true? (sut/valid-slug? "company123")))
    (is (true? (sut/valid-slug? "my-tenant-name")))
    (is (true? (sut/valid-slug? "ab"))))

  (testing "invalid slugs"
    (is (false? (sut/valid-slug? "A")))
    (is (false? (sut/valid-slug? "ACME")))
    (is (false? (sut/valid-slug? "-acme")))
    (is (false? (sut/valid-slug? "acme-")))
    (is (false? (sut/valid-slug? "acme_corp")))
    (is (false? (sut/valid-slug? "acme corp")))
    (is (false? (sut/valid-slug? "a")))
    (is (false? (sut/valid-slug? (apply str (repeat 101 "a")))))))

(deftest ^:unit slug->schema-name-test
  (testing "converts slug to schema name"
    (is (= "tenant_acme_corp" (sut/slug->schema-name "acme-corp")))
    (is (= "tenant_my_company" (sut/slug->schema-name "my-company")))
    (is (= "tenant_test123" (sut/slug->schema-name "test123")))))

(deftest ^:unit ^:security valid-schema-name?-test
  (testing "accepts schema names produced by slug->schema-name"
    (is (true? (sut/valid-schema-name? "tenant_acme_corp")))
    (is (true? (sut/valid-schema-name? "tenant_company123")))
    (is (true? (sut/valid-schema-name? (sut/slug->schema-name "my-tenant-name")))))

  (testing "rejects names that could break out of a DDL identifier"
    (is (false? (sut/valid-schema-name? "public")))              ; wrong prefix
    (is (false? (sut/valid-schema-name? "tenant_")))             ; empty body
    (is (false? (sut/valid-schema-name? "tenant_acme; DROP TABLE users;--")))
    (is (false? (sut/valid-schema-name? "tenant_acme corp")))    ; space
    (is (false? (sut/valid-schema-name? "tenant_ACME")))         ; uppercase
    (is (false? (sut/valid-schema-name? "tenant_acme\"")))       ; quote
    (is (false? (sut/valid-schema-name? (str "tenant_" (apply str (repeat 64 "a")))))) ; > 63 chars
    (is (false? (sut/valid-schema-name? nil)))
    (is (false? (sut/valid-schema-name? :tenant_acme)))))

(deftest ^:unit prepare-tenant-test
  (testing "prepares tenant entity with all fields"
    (let [tenant-id (UUID/randomUUID)
          now (Instant/now)
          input {:slug "acme-corp"
                 :name "ACME Corporation"
                 :settings {:features {:mfa-enabled true}}}
          result (sut/prepare-tenant input tenant-id now)]
      (is (= tenant-id (:id result)))
      (is (= "acme-corp" (:slug result)))
      (is (= "ACME Corporation" (:name result)))
      (is (= "tenant_acme_corp" (:schema-name result)))
      (is (= :active (:status result)))
      (is (= {:features {:mfa-enabled true}} (:settings result)))
      (is (= now (:created-at result)))
      (is (= now (:updated-at result)))
      (is (nil? (:deleted-at result))))))

(deftest ^:unit create-tenant-decision-test
  (testing "accepts valid new tenant"
    (let [result (sut/create-tenant-decision "acme-corp" #{})]
      (is (true? (:valid? result)))
      (is (= "tenant_acme_corp" (:schema-name result)))))

  (testing "rejects invalid slug"
    (let [result (sut/create-tenant-decision "ACME" #{})]
      (is (false? (:valid? result)))
      (is (string? (:error result)))))

  (testing "rejects duplicate slug"
    (let [result (sut/create-tenant-decision "acme-corp" #{"acme-corp" "other-tenant"})]
      (is (false? (:valid? result)))
      (is (= "Tenant slug already exists" (:error result))))))

(deftest ^:unit update-tenant-decision-test
  (testing "accepts valid update for existing tenant"
    (let [existing {:id (UUID/randomUUID) :slug "acme-corp" :status :active}
          result (sut/update-tenant-decision existing {:name "New Name"})]
      (is (true? (:valid? result)))
      (is (= {:name "New Name"} (:changes result)))))

  (testing "rejects update for non-existent tenant"
    (let [result (sut/update-tenant-decision nil {:name "New Name"})]
      (is (false? (:valid? result)))
      (is (= "Tenant not found" (:error result)))))

  (testing "rejects invalid status"
    (let [existing {:id (UUID/randomUUID) :slug "acme-corp" :status :active}
          result (sut/update-tenant-decision existing {:status :invalid-status})]
      (is (false? (:valid? result)))
      (is (= "Invalid status" (:error result))))))

(deftest ^:unit prepare-tenant-update-test
  (testing "merges update data with existing tenant"
    (let [existing {:id (UUID/randomUUID)
                    :slug "acme-corp"
                    :name "Old Name"
                    :status :active
                    :created-at (Instant/parse "2024-01-01T00:00:00Z")
                    :updated-at (Instant/parse "2024-01-01T00:00:00Z")}
          now (Instant/now)
          result (sut/prepare-tenant-update existing {:name "New Name" :status :suspended} now)]
      (is (= "New Name" (:name result)))
      (is (= :suspended (:status result)))
      (is (= now (:updated-at result)))
      (is (= "acme-corp" (:slug result))))))

(deftest ^:unit tenant-status-predicates-test
  (testing "tenant-deleted?"
    (is (true? (sut/tenant-deleted? {:status :deleted :deleted-at (Instant/now)})))
    (is (true? (sut/tenant-deleted? {:status :deleted :deleted-at nil})))
    (is (true? (sut/tenant-deleted? {:status :active :deleted-at (Instant/now)})))
    (is (false? (sut/tenant-deleted? {:status :active :deleted-at nil}))))

  (testing "tenant-active?"
    (is (true? (sut/tenant-active? {:status :active :deleted-at nil})))
    (is (false? (sut/tenant-active? {:status :suspended :deleted-at nil})))
    (is (false? (sut/tenant-active? {:status :active :deleted-at (Instant/now)}))))

  (testing "tenant-suspended?"
    (is (true? (sut/tenant-suspended? {:status :suspended})))
    (is (false? (sut/tenant-suspended? {:status :active}))))

  (testing "can-delete-tenant?"
    (is (true? (sut/can-delete-tenant? {:status :active :deleted-at nil})))
    (is (false? (sut/can-delete-tenant? {:status :deleted :deleted-at (Instant/now)})))))

(deftest ^:unit prepare-tenant-deletion-test
  (testing "marks tenant as deleted"
    (let [existing {:id (UUID/randomUUID)
                    :slug "acme-corp"
                    :status :active
                    :deleted-at nil}
          now (Instant/now)
          result (sut/prepare-tenant-deletion existing now)]
      (is (= :deleted (:status result)))
      (is (= now (:deleted-at result)))
      (is (= now (:updated-at result))))))
