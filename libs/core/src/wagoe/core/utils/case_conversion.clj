(ns wagoe.core.utils.case-conversion
  "Generic case conversion utilities for API and data transformations.
   
   This namespace provides reusable case conversion utilities that handle
   transformations between different naming conventions commonly used in APIs:
   - camelCase (JavaScript/JSON APIs)
   - kebab-case (Clojure idioms)
   - snake_case (Database/SQL)
   
   These functions are designed to be nil-safe and handle nested data structures
   where appropriate.
   
   Usage:
   (:require [wagoe.core.utils.case-conversion :as case-conversion])
   
   (case-conversion/camel-case->kebab-case-map {:userId \"123\" :tenantId \"456\"})
   ;; => {:user-id \"123\" :tenant-id \"456\"}"
  (:require [clojure.string :as str]))

;; =============================================================================
;; camelCase <-> kebab-case Conversions
;; =============================================================================

(defn camel-case->kebab-case-map
  "Transform camelCase API keys to kebab-case internal keys (nil-safe).
   
   This is a generic version that handles common API field transformations.
   For domain-specific transformations, compose this with additional transforms.
   
   Args:
     m: Map with camelCase keyword keys, or nil
     
   Returns:
     Map with kebab-case keyword keys, or nil if input is nil
     
   Example:
     (camel-case->kebab-case-map {:userId \"123\" :tenantId \"456\"})
     ;; => {:user-id \"123\" :tenant-id \"456\"}"
  [m]
  (when m
    (reduce-kv (fn [acc k v]
                 (let [kebab-key (-> (name k)
                                     (str/replace #"([a-z])([A-Z])" "$1-$2")
                                     str/lower-case
                                     keyword)]
                   (assoc acc kebab-key v)))
               {} m)))

(defn kebab-case->camel-case-map
  "Transform kebab-case internal keys to camelCase API keys (nil-safe).
   
   This is a generic version that handles basic case conversion.
   For domain-specific transformations (like type conversions), 
   compose this with additional transforms.
   
   Args:
     m: Map with kebab-case keyword keys, or nil
     
   Returns:
     Map with camelCase keyword keys, or nil if input is nil
     
   Example:
     (kebab-case->camel-case-map {:user-id \"123\" :tenant-id \"456\"})
     ;; => {:userId \"123\" :tenantId \"456\"}"
  [m]
  (when m
    (reduce-kv (fn [acc k v]
                 (let [camel-key (-> (name k)
                                     (str/replace #"-(.)" #(str/upper-case (second %1)))
                                     keyword)]
                   (assoc acc camel-key v)))
               {} m)))

;; =============================================================================
;; String Case Conversions
;; =============================================================================

(defn camel-case->kebab-case-string
  "Convert a camelCase string to kebab-case string.
   
   Args:
     s: camelCase string
     
   Returns:
     kebab-case string
     
   Example:
     (camel-case->kebab-case-string \"userId\") ;; => \"user-id\""
  [s]
  (when s
    (-> s
        (str/replace #"([a-z])([A-Z])" "$1-$2")
        str/lower-case)))

(defn kebab-case->camel-case-string
  "Convert a kebab-case string to camelCase string.
   
   Args:
     s: kebab-case string
     
   Returns:
     camelCase string
     
   Example:
     (kebab-case->camel-case-string \"user-id\") ;; => \"userId\""
  [s]
  (when s
    (str/replace s #"-(.)" #(str/upper-case (second %1)))))

;; =============================================================================
;; Deep Transformation Utilities
;; =============================================================================

(defn deep-transform-keys
  "Recursively transform all keys in a nested data structure using transform-fn.
   
   Args:
     transform-fn: Function to transform each key
     data: Nested data structure (maps, vectors, lists)
     
   Returns:
     Data structure with all keys transformed
     
   Example:
     (deep-transform-keys camel-case->kebab-case-string
                         {:userId \"123\" :userInfo {:firstName \"John\"}})"
  [transform-fn data]
  (cond
    (map? data)
    (reduce-kv (fn [acc k v]
                 (let [new-key (if (keyword? k)
                                 (-> k name transform-fn keyword)
                                 k)]
                   (assoc acc new-key (deep-transform-keys transform-fn v))))
               {} data)

    (vector? data)
    (mapv #(deep-transform-keys transform-fn %) data)

    (list? data)
    (map #(deep-transform-keys transform-fn %) data)

    :else data))

;; =============================================================================
;; kebab-case <-> snake_case Conversions (Database)
;; =============================================================================

(defn kebab-case->snake-case-string
  "Convert a kebab-case string to snake_case string.
   
   Args:
     s: kebab-case string
     
   Returns:
     snake_case string
     
   Example:
     (kebab-case->snake-case-string \"created-at\") ;; => \"created_at\""
  [s]
  (when s
    (str/replace s "-" "_")))

(defn kebab-case->snake-case-keyword
  "Convert a kebab-case keyword to snake_case keyword.
   
   Args:
     kw: kebab-case keyword
     
   Returns:
     snake_case keyword
     
   Example:
     (kebab-case->snake-case-keyword :created-at) ;; => :created_at"
  [kw]
  (when kw
    (if (keyword? kw)
      (keyword (kebab-case->snake-case-string (name kw)))
      kw)))

(defn snake-case->kebab-case-string
  "Convert a snake_case string to kebab-case string.
   
   Args:
     s: snake_case string
     
   Returns:
     kebab-case string
     
   Example:
     (snake-case->kebab-case-string \"created_at\") ;; => \"created-at\""
  [s]
  (when s
    (str/replace s "_" "-")))

(defn kebab-case->snake-case-map
  "Transform kebab-case internal keys to snake_case database keys (nil-safe).
   
   Args:
     m: Map with kebab-case keyword keys, or nil
     
   Returns:
     Map with snake_case keyword keys, or nil if input is nil
     
   Example:
     (kebab-case->snake-case-map {:created-at \"2024-01-01\" :user-id \"123\"})
     ;; => {:created_at \"2024-01-01\" :user_id \"123\"}"
  [m]
  (when m
    (reduce-kv (fn [acc k v]
                 (let [snake-key (-> (name k)
                                     kebab-case->snake-case-string
                                     keyword)]
                   (assoc acc snake-key v)))
               {} m)))

(defn snake-case->kebab-case-map
  "Transform snake_case database keys to kebab-case internal keys (nil-safe).
   
   Args:
     m: Map with snake_case keyword keys, or nil
     
   Returns:
     Map with kebab-case keyword keys, or nil if input is nil
     
   Example:
     (snake-case->kebab-case-map {:created_at \"2024-01-01\" :user_id \"123\"})
     ;; => {:created-at \"2024-01-01\" :user-id \"123\"}"
  [m]
  (when m
    (reduce-kv (fn [acc k v]
                 (let [kebab-key (-> (name k)
                                     snake-case->kebab-case-string
                                     keyword)]
                   (assoc acc kebab-key v)))
               {} m)))