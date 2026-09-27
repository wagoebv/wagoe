(ns wagoe.admin-entity-schema-parity-test
  "BOU-567: `bb ai admin-entity` validates against a copy of the admin's entity
   schema, because wagoe-ai cannot depend on wagoe-admin. This keeps the copy
   from drifting."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [wagoe.admin.schema :as admin]
            [wagoe.ai.schema :as ai]))

(defn- enum-values [schema] (set (m/children (m/schema schema))))

(defn- map-keys [schema] (set (map first (m/children (m/schema schema)))))

(deftest ^:unit field-types-match-the-admin
  (is (= (enum-values admin/FieldType) (enum-values ai/AdminFieldType))))

(deftest ^:unit every-admin-entity-key-is-known-to-the-generator
  (is (empty? (remove (map-keys ai/AdminEntityConfig) (map-keys admin/EntityConfig)))))
