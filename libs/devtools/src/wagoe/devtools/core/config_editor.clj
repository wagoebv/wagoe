(ns wagoe.devtools.core.config-editor
  "Pure functions for config diffing, dependency analysis, and formatting.
   FC/IS: no I/O, no logging."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [integrant.core :as ig]))

(def ^:private secret-key-patterns
  [#"(?i)password" #"(?i)secret" #"(?i)api[-_]?key" #"(?i)token" #"(?i)credential"])

(defn- secret-key? [k]
  (let [kname (if (keyword? k) (name k) (str k))]
    (some #(re-find % kname) secret-key-patterns)))

(defn redact-secrets
  "Recursively replace values of sensitive keys with \"********\"."
  [m]
  (cond
    (map? m) (reduce-kv (fn [acc k v]
                          (assoc acc k (if (secret-key? k) "********" (redact-secrets v))))
                        {} m)
    (sequential? m) (mapv redact-secrets m)
    :else m))

(defn config-diff
  "Compute diff between two config maps.
   Returns {:changed {key {:old v1 :new v2}} :added {key val} :removed {key val}}."
  [old-config new-config]
  (let [old-keys (set (keys old-config))
        new-keys (set (keys new-config))
        added-keys (set/difference new-keys old-keys)
        removed-keys (set/difference old-keys new-keys)
        common-keys (set/intersection old-keys new-keys)
        changed (reduce (fn [acc k]
                          (let [ov (get old-config k)
                                nv (get new-config k)]
                            (if (= ov nv) acc (assoc acc k {:old ov :new nv}))))
                        {} common-keys)]
    {:changed changed
     :added   (select-keys new-config added-keys)
     :removed (select-keys old-config removed-keys)}))

(defn affected-components
  "Given a config diff, return the set of component keys that would restart."
  [diff]
  (into #{} (concat (keys (:changed diff)) (keys (:added diff)) (keys (:removed diff)))))

(defn contains-refs?
  "Check if a config value contains any ig/ref instances."
  [v]
  (cond
    (ig/ref? v) true
    (map? v) (some contains-refs? (vals v))
    (sequential? v) (some contains-refs? v)
    :else false))

(defn strip-refs
  "Replace ig/ref values with a keyword placeholder for serialization.
   Encodes the full qualified key (namespace + name) so restore-refs can
   round-trip refs with any namespace, not just :boundary/*."
  [v]
  (cond
    (ig/ref? v) (let [k (ig/ref-key v)]
                  (keyword "integrant.ref"
                           (if (namespace k)
                             (str (namespace k) "/" (name k))
                             (name k))))
    (map? v) (reduce-kv (fn [m k val] (assoc m k (strip-refs val))) {} v)
    (sequential? v) (mapv strip-refs v)
    :else v))

(defn restore-refs
  "Restore :integrant.ref/* placeholders back to ig/ref values.
   Decodes the full qualified key preserved by strip-refs."
  [v]
  (cond
    (and (keyword? v) (= "integrant.ref" (namespace v)))
    (let [encoded (name v)
          slash-idx (str/index-of encoded "/")]
      (if slash-idx
        (ig/ref (keyword (subs encoded 0 slash-idx)
                         (subs encoded (inc slash-idx))))
        (ig/ref (keyword encoded))))

    (map? v) (reduce-kv (fn [m k val] (assoc m k (restore-refs val))) {} v)
    (sequential? v) (mapv restore-refs v)
    :else v))

(defn format-config-tree
  "Format a config map as an indented string tree for display."
  ([cfg] (format-config-tree cfg 0))
  ([cfg indent]
   (let [pad (apply str (repeat indent "  "))]
     (if (map? cfg)
       (str/join "\n" (for [[k v] (sort-by str cfg)]
                        (if (map? v)
                          (str pad (pr-str k) "\n" (format-config-tree v (inc indent)))
                          (str pad (pr-str k) " " (pr-str v)))))
       (str pad (pr-str cfg))))))
