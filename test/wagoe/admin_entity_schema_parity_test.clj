(ns wagoe.admin-entity-schema-parity-test
  "BOU-567: `bb ai admin-entity` validates against a copy of the admin's entity
   schema, because wagoe-ai cannot depend on wagoe-admin. This keeps the copy
   from drifting — in keys, in value shape and in optionality."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [malli.core :as m]
            [wagoe.admin.schema :as admin]
            [wagoe.ai.schema :as ai]))

(defn- shape
  "A schema's form without its documentation properties."
  [schema]
  (walk/postwalk
   (fn [x]
     (cond
       (map? x)    (dissoc x :title :description)
       (vector? x) (vec (remove #(and (map? %) (empty? %)) x))
       :else       x))
   (m/form schema)))

(defn- entries
  "{key {:optional? bool :shape form}} for a :map schema."
  [schema]
  (into {}
        (map (fn [[k props child]]
               [k {:optional? (boolean (:optional props)) :shape (shape child)}]))
        (m/children (m/schema schema))))

(deftest ^:unit field-types-match-the-admin
  (is (= (shape admin/FieldType) (shape ai/AdminFieldType))))

(deftest ^:unit every-admin-entity-key-is-known-to-the-generator
  (is (empty? (remove (set (keys (entries ai/AdminEntityConfig)))
                      (keys (entries admin/EntityConfig))))))

(deftest ^:unit shared-entity-keys-have-the-admins-shape
  ;; :fields differs on purpose: the admin's FieldConfig describes a merged
  ;; field, which requires :name and :widget that a file of overrides lacks.
  (let [theirs (dissoc (entries admin/EntityConfig) :fields)
        ours   (entries ai/AdminEntityConfig)]
    (doseq [[k v] theirs]
      (testing (str k)
        (is (= v (get ours k)))))))

(deftest ^:unit field-overrides-are-a-slice-of-field-config
  (let [theirs (entries admin/FieldConfig)
        ours   (entries ai/AdminFieldOverride)]
    (is (empty? (remove (set (keys theirs)) (keys ours))))
    (doseq [[k v] ours]
      (testing (str k)
        ;; Every override is optional; the shape is what must agree.
        (is (= (:shape (get theirs k)) (:shape v)))))))
