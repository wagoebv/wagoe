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
  "A schema's form without its documentation properties or closedness, which
   `key-tree` compares against the schema the admin validates at startup."
  [schema]
  (walk/postwalk
   (fn [x]
     (cond
       (map? x)    (dissoc x :title :description :closed)
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

(defn- key-tree
  "Every map in `schema`, collections unwrapped, as {:closed? :keys {k tree}}.
   Values that are not maps are nil."
  [schema]
  (let [s (m/schema schema)]
    (case (m/type s)
      :map                         {:closed? (boolean (:closed (m/properties s)))
                                    :keys    (into {} (for [[k _ c] (m/children s)]
                                                        [k (key-tree c)]))}
      (:vector :sequential :set :maybe) (key-tree (first (m/children s)))
      :map-of                      (key-tree (second (m/children s)))
      :and                         (some key-tree (m/children s))
      nil)))

(deftest ^:unit field-types-match-the-admin
  (is (= (shape admin/FieldType) (shape ai/AdminFieldType))))

(deftest ^:unit widgets-match-the-admin
  ;; BOU-572: an open field map let `:widget :workflow` through.
  (is (= (shape admin/FieldWidget) (shape ai/AdminFieldWidget))))

(deftest ^:unit the-generator-accepts-exactly-the-keys-the-admin-reads
  ;; EntityOverrides is what the admin checks :entities against at startup,
  ;; closed at every level. The copy must refuse what it refuses and accept
  ;; what it accepts, at every level (BOU-572).
  (is (= (key-tree admin/EntityOverrides)
         (key-tree ai/AdminEntityConfig))))

(deftest ^:unit the-entity-workflow-has-the-admins-shape
  (is (= (shape (get-in (entries admin/EntityOverrides) [:workflow :shape]))
         (get-in (entries ai/AdminEntityConfig) [:workflow :shape]))))

(deftest ^:unit shared-entity-keys-have-the-admins-shape
  ;; :fields differs on purpose: the admin's FieldConfig describes a merged
  ;; field, which requires :name and :widget that a file of overrides lacks.
  (let [theirs (dissoc (entries admin/EntityConfig) :fields)
        ours   (entries ai/AdminEntityConfig)]
    (doseq [[k v] theirs]
      (testing (str k)
        (is (= v (get ours k)))))))

(deftest ^:unit field-overrides-have-the-field-configs-shape
  (let [theirs (entries admin/FieldConfig)
        ours   (entries ai/AdminFieldOverride)]
    (doseq [[k v] ours
            :when (contains? theirs k)]
      (testing (str k)
        ;; Every override is optional; the shape is what must agree.
        (is (:optional? v))
        (is (= (:shape (get theirs k)) (:shape v)))))))
