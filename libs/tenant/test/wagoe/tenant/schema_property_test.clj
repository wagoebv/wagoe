(ns wagoe.tenant.schema-property-test
  "Property test: every value malli generates from a tenant schema validates
   against that schema. Exercises the slug regex, :uuid, inst? and enum
   generators — a real check that the schemas are self-consistent."
  (:require [wagoe.tenant.schema :as schema]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]
            [malli.generator :as mg]))

(defn- generates-valid
  [s]
  (prop/for-all [v (mg/generator s)]
                (m/validate s v)))

(defspec ^:property tenant-generates-valid 100
  (generates-valid schema/Tenant))

(defspec ^:property tenant-input-generates-valid 100
  (generates-valid schema/TenantInput))

(defspec ^:property tenant-settings-generates-valid 100
  (generates-valid schema/TenantSettings))

(defspec ^:property tenant-membership-generates-valid 100
  (generates-valid schema/TenantMembership))
