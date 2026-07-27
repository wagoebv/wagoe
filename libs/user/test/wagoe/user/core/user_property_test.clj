(ns wagoe.user.core.user-property-test
  "Property-based tests for user validation using test.check.
   
   These tests verify validation invariants hold across a wide range of generated inputs:
   - Valid emails always pass validation
   - Invalid emails always fail validation
   - Names within bounds always pass
   - Business rules consistently enforced
   
   Uses generative testing to discover edge cases and ensure robustness."
  (:require [wagoe.user.core.user :as user-core]
            [wagoe.user.schema :as schema]
            [clojure.string :as str]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]
            [support.validation-helpers :as vh])
  (:import (java.time Instant)
           (java.util UUID)))

;; =============================================================================
;; Generator Utilities
;; =============================================================================

(def uuid-gen
  "Generator for UUIDs."
  (gen/fmap (fn [_] (UUID/randomUUID)) gen/nat))

(def instant-gen
  "Generator for Instant timestamps."
  (gen/fmap (fn [ms] (Instant/ofEpochMilli ms))
            (gen/choose 0 1735689600000))) ; Up to 2025

(def email-local-part-gen
  "Generator for valid email local parts."
  (gen/fmap
   (fn [parts]
     (str/join "" parts))
   (gen/vector
    (gen/frequency
     [[80 (gen/fmap char (gen/choose 97 122))] ; lowercase letters (weighted higher)
      [10 (gen/fmap char (gen/choose 65 90))] ; uppercase letters
      [5 (gen/fmap char (gen/choose 48 57))] ; digits
      [3 (gen/return \.)]
      [1 (gen/return \_)]
      [1 (gen/return \%)]])
    1 20)))

(def email-domain-gen
  "Generator for valid email domains."
  (gen/fmap
   (fn [[subdomain domain tld]]
     (if subdomain
       (str subdomain "." domain "." tld)
       (str domain "." tld)))
   (gen/tuple
    (gen/one-of [(gen/return nil)
                 (gen/fmap (fn [chars] (apply str chars))
                           (gen/vector (gen/fmap char (gen/choose 97 122)) 2 10))])
    (gen/fmap (fn [chars] (apply str chars))
              (gen/vector (gen/fmap char (gen/choose 97 122)) 3 15))
    (gen/elements ["com" "org" "net" "edu" "io" "dev" "co"]))))

(def valid-email-gen
  "Generator for valid email addresses."
  (gen/fmap
   (fn [[local domain]] (str local "@" domain))
   (gen/tuple email-local-part-gen email-domain-gen)))

(def invalid-email-gen
  "Generator for invalid email addresses."
  (gen/frequency
   [[1 (gen/return "")] ; empty
    [1 (gen/return "notanemail")] ; no @
    [1 (gen/return "@example.com")] ; no local part
    [1 (gen/return "user@")] ; no domain
    [1 (gen/return "user@@example.com")] ; double @
    [1 (gen/return "user@.com")] ; domain starts with dot
    [1 (gen/return "user@example")] ; no TLD
    [1 (gen/return "user name@example.com")] ; space in local
    [1 (gen/return "user@exam ple.com")] ; space in domain
    [1 (gen/fmap #(str % "@example..com") gen/string-alphanumeric)] ; consecutive dots
    [1 (gen/return "user@example.c")]])) ; TLD too short

(def valid-name-gen
  "Generator for valid user names (1-255 characters)."
  (gen/fmap
   (fn [parts]
     (str/join " " parts))
   (gen/vector
    (gen/fmap (fn [chars] (apply str chars))
              (gen/vector (gen/fmap char (gen/choose 65 122)) 1 20))
    1 5)))

(def invalid-name-gen
  "Generator for invalid user names."
  (gen/frequency
   [[1 (gen/return "")] ; empty
    [1 (gen/fmap (fn [_] (apply str (repeat 256 \a))) gen/nat)]])) ; too long

(def role-gen
  "Generator for valid user roles."
  (gen/elements [:admin :user :viewer]))

(def valid-user-data-gen
  "Generator for valid user creation data."
  (gen/fmap
   (fn [[email name role password]]
     {:email email
      :name name
      :role role
      :active true
      :password password})
   (gen/tuple
    valid-email-gen
    valid-name-gen
    role-gen
    (gen/fmap #(str "password-" %) (gen/large-integer* {:min 1})))))

(def user-entity-gen
  "Generator for complete user entities."
  (gen/fmap
   (fn [[id email name role created-at]]
     {:id id
      :email email
      :name name
      :role role
      :active true
      :login-count 0
      :created-at created-at
      :updated-at nil
      :deleted-at nil})
   (gen/tuple
    uuid-gen
    valid-email-gen
    valid-name-gen
    role-gen
    instant-gen)))

;; =============================================================================
;; Property-Based Tests: Email Validation
;; =============================================================================

(defspec valid-emails-pass-schema-validation 100
  (prop/for-all [email valid-email-gen]
                (m/validate [:re schema/email-regex] email)))

(defspec invalid-emails-fail-schema-validation 100
  (prop/for-all [email invalid-email-gen]
                (not (m/validate [:re schema/email-regex] email))))

;; =============================================================================
;; Property-Based Tests: Name Validation
;; =============================================================================

(defspec valid-names-pass-schema-validation 100
  (prop/for-all [name valid-name-gen]
                (and
                 (m/validate [:string {:min 1 :max 255}] name)
                 (>= (count name) 1)
                 (<= (count name) 255))))

(defspec invalid-names-fail-schema-validation 100
  (prop/for-all [name invalid-name-gen]
                (not (m/validate [:string {:min 1 :max 255}] name))))

;; =============================================================================
;; Property-Based Tests: User Creation Validation
;; =============================================================================

(defspec valid-user-data-passes-creation-validation 100
  (prop/for-all [user-data valid-user-data-gen]
                (let [result (user-core/validate-user-creation-request user-data vh/test-validation-config)]
                  (true? (:valid? result)))))

(defspec user-creation-preserves-email 100
  (prop/for-all [user-data valid-user-data-gen
                 user-id uuid-gen
                 timestamp instant-gen]
                (let [prepared (user-core/prepare-user-for-creation user-data timestamp user-id)]
                  (= (:email user-data) (:email prepared)))))

(defspec user-creation-sets-timestamps 100
  (prop/for-all [user-data valid-user-data-gen
                 user-id uuid-gen
                 timestamp instant-gen]
                (let [prepared (user-core/prepare-user-for-creation user-data timestamp user-id)]
                  (and
                   (= timestamp (:created-at prepared))
                   (nil? (:updated-at prepared))
                   (nil? (:deleted-at prepared))))))

(defspec user-creation-sets-active-by-default 100
  (prop/for-all [user-data valid-user-data-gen
                 user-id uuid-gen
                 timestamp instant-gen]
                (let [prepared (user-core/prepare-user-for-creation user-data timestamp user-id)]
                  (true? (:active prepared)))))

;; =============================================================================
;; Property-Based Tests: Duplicate Detection
;; =============================================================================

(defspec duplicate-user-check-rejects-existing 100
  (prop/for-all [user-data valid-user-data-gen
                 existing-user user-entity-gen]
                (let [result (user-core/check-duplicate-user-decision user-data existing-user)]
                  (and
                   (= :reject (:decision result))
                   (= :duplicate-email (:reason result))
                   (= (:email user-data) (:email result))))))

(defspec duplicate-user-check-proceeds-when-nil 100
  (prop/for-all [user-data valid-user-data-gen]
                (let [result (user-core/check-duplicate-user-decision user-data nil)]
                  (= :proceed (:decision result)))))

;; =============================================================================
;; Property-Based Tests: Business Rules
;; =============================================================================

(defspec email-change-always-fails 100
  (prop/for-all [user-entity user-entity-gen
                 new-email valid-email-gen]
                (let [updated-user (assoc user-entity :email new-email)
                      changes (user-core/calculate-user-changes user-entity updated-user)
                      validation (user-core/validate-user-business-rules updated-user changes)]
                  (if (not= (:email user-entity) new-email)
        ;; If email actually changed, validation should fail
                    (false? (:valid? validation))
        ;; If email didn't change, validation passes
                    true))))

;; =============================================================================
;; Property-Based Tests: Change Calculation
;; =============================================================================

(defspec unchanged-fields-not-in-changes 100
  (prop/for-all [user-entity user-entity-gen]
                (let [changes (user-core/calculate-user-changes user-entity user-entity)]
                  (empty? changes))))

(defspec changed-fields-detected 100
  (prop/for-all [user-entity user-entity-gen
                 new-name valid-name-gen]
                (let [updated-user (assoc user-entity :name new-name)
                      changes (user-core/calculate-user-changes user-entity updated-user)]
                  (if (not= (:name user-entity) new-name)
        ;; If name changed, should be in changes
                    (contains? changes :name)
        ;; If name didn't change, shouldn't be in changes
                    (not (contains? changes :name))))))

;; =============================================================================
;; Property-Based Tests: User Filtering
;; =============================================================================

(defspec active-user-filter-excludes-deleted 100
  (prop/for-all [users (gen/vector user-entity-gen 0 20)]
                (let [active-users (user-core/filter-active-users users)]
                  (every? #(nil? (:deleted-at %)) active-users))))

(defspec role-filter-only-returns-matching-role 100
  (prop/for-all [users (gen/vector user-entity-gen 1 20)
                 role role-gen]
                (let [filtered (user-core/apply-user-filters users {:role role})]
                  (every? #(= role (:role %)) filtered))))

;; =============================================================================
;; Property-Based Tests: Soft Deletion
;; =============================================================================

(defspec soft-deletion-sets-timestamps 100
  (prop/for-all [user-entity user-entity-gen
                 timestamp instant-gen]
                (let [deleted (user-core/prepare-user-for-soft-deletion user-entity timestamp)]
                  (and
                   (= timestamp (:deleted-at deleted))
                   (= timestamp (:updated-at deleted))
                   (false? (:active deleted))))))

(defspec soft-deletion-preserves-identity 100
  (prop/for-all [user-entity user-entity-gen
                 timestamp instant-gen]
                (let [deleted (user-core/prepare-user-for-soft-deletion user-entity timestamp)]
                  (and
                   (= (:id user-entity) (:id deleted))
                   (= (:email user-entity) (:email deleted))))))
