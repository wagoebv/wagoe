(ns wagoe.admin.schema-property-test
  "Property test: every value malli generates from an admin config schema
   validates against that schema. Focuses on the constrained schemas (enums,
   int bounds, nested maps); the fully `:any`-typed config schemas are omitted
   as their generate->validate is tautological."
  (:require [wagoe.admin.schema :as schema]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]
            [malli.generator :as mg]))

(defn- generates-valid
  [s]
  (prop/for-all [v (mg/generator s)]
                (m/validate s v)))

(defspec ^:property pagination-config-generates-valid 100
  (generates-valid schema/PaginationConfig))

(defspec ^:property entity-discovery-config-generates-valid 100
  (generates-valid schema/EntityDiscoveryConfig))

(defspec ^:property field-config-generates-valid 100
  (generates-valid schema/FieldConfig))

(defspec ^:property entity-config-generates-valid 100
  (generates-valid schema/EntityConfig))
