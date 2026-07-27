(ns wagoe.platform.shell.pagination.cursor-test
  "Integration tests for cursor encoding/decoding.
   
   These tests verify I/O operations (Base64, JSON) and round-trip encoding."
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.platform.shell.pagination.cursor :as cursor]
            [cheshire.core :as json])
  (:import [java.util UUID]
           [java.time Instant]))

;; =============================================================================
;; Encode/Decode Round-Trip Tests
;; =============================================================================

(deftest ^:unit encode-decode-round-trip-test
  (testing "Round-trip with UUID and string sort value"
    (let [cursor-data {:id (UUID/fromString "123e4567-e89b-12d3-a456-426614174000")
                       :sort-value "user@example.com"
                       :sort-field "email"
                       :sort-direction :asc}
          encoded (cursor/encode-cursor cursor-data)
          decoded (cursor/decode-cursor encoded)]
      (is (string? encoded))
      (is (pos? (count encoded)))
      (is (= (:id cursor-data) (:id decoded)))
      (is (= (:sort-value cursor-data) (:sort-value decoded)))
      (is (= (:sort-field cursor-data) (:sort-field decoded)))
      (is (= (:sort-direction cursor-data) (:sort-direction decoded)))))

  (testing "Round-trip with UUID and Instant sort value"
    (let [cursor-data {:id (UUID/fromString "123e4567-e89b-12d3-a456-426614174000")
                       :sort-value (Instant/parse "2024-01-04T10:00:00Z")
                       :sort-field "created-at"
                       :sort-direction :desc}
          encoded (cursor/encode-cursor cursor-data)
          decoded (cursor/decode-cursor encoded)]
      (is (= (:id cursor-data) (:id decoded)))
      (is (= (:sort-value cursor-data) (:sort-value decoded)))
      (is (= (:sort-field cursor-data) (:sort-field decoded)))
      (is (= (:sort-direction cursor-data) (:sort-direction decoded)))))

  (testing "Round-trip with UUID and integer sort value"
    (let [cursor-data {:id (UUID/fromString "123e4567-e89b-12d3-a456-426614174000")
                       :sort-value 42
                       :sort-field "score"
                       :sort-direction :asc}
          encoded (cursor/encode-cursor cursor-data)
          decoded (cursor/decode-cursor encoded)]
      (is (= (:id cursor-data) (:id decoded)))
      (is (= (:sort-value cursor-data) (:sort-value decoded)))
      (is (= (:sort-field cursor-data) (:sort-field decoded)))
      (is (= (:sort-direction cursor-data) (:sort-direction decoded)))))

  (testing "Round-trip with UUID and double sort value"
    (let [cursor-data {:id (UUID/fromString "123e4567-e89b-12d3-a456-426614174000")
                       :sort-value 99.99
                       :sort-field "price"
                       :sort-direction :desc}
          encoded (cursor/encode-cursor cursor-data)
          decoded (cursor/decode-cursor encoded)]
      (is (= (:id cursor-data) (:id decoded)))
      (is (= (:sort-value cursor-data) (:sort-value decoded)))
      (is (= (:sort-field cursor-data) (:sort-field decoded)))
      (is (= (:sort-direction cursor-data) (:sort-direction decoded)))))

  (testing "Round-trip with timestamp"
    (let [cursor-data {:id (UUID/fromString "123e4567-e89b-12d3-a456-426614174000")
                       :sort-value "test"
                       :sort-field "name"
                       :sort-direction :asc
                       :timestamp (Instant/parse "2024-01-04T12:00:00Z")}
          encoded (cursor/encode-cursor cursor-data)
          decoded (cursor/decode-cursor encoded)]
      (is (= (:id cursor-data) (:id decoded)))
      (is (= (:sort-value cursor-data) (:sort-value decoded)))
      (is (= (:sort-field cursor-data) (:sort-field decoded)))
      (is (= (:sort-direction cursor-data) (:sort-direction decoded)))
      (is (= (:timestamp cursor-data) (:timestamp decoded))))))

;; =============================================================================
;; Encoding Tests
;; =============================================================================

(deftest ^:unit encode-cursor-test
  (testing "Encode produces non-empty Base64 string"
    (let [cursor-data {:id (UUID/randomUUID)
                       :sort-value "test"
                       :sort-field "field"
                       :sort-direction :asc}
          encoded (cursor/encode-cursor cursor-data)]
      (is (string? encoded))
      (is (pos? (count encoded)))
      ;; Base64 string should only contain valid Base64 characters
      (is (re-matches #"[A-Za-z0-9+/=]+" encoded))))

  (testing "Encode same data produces same cursor"
    (let [cursor-data {:id (UUID/fromString "123e4567-e89b-12d3-a456-426614174000")
                       :sort-value "test"
                       :sort-field "field"
                       :sort-direction :asc}
          encoded1 (cursor/encode-cursor cursor-data)
          encoded2 (cursor/encode-cursor cursor-data)]
      (is (= encoded1 encoded2))))

  (testing "Encode different data produces different cursors"
    (let [cursor-data1 {:id (UUID/fromString "123e4567-e89b-12d3-a456-426614174000")
                        :sort-value "test1"
                        :sort-field "field"
                        :sort-direction :asc}
          cursor-data2 {:id (UUID/fromString "123e4567-e89b-12d3-a456-426614174000")
                        :sort-value "test2"
                        :sort-field "field"
                        :sort-direction :asc}
          encoded1 (cursor/encode-cursor cursor-data1)
          encoded2 (cursor/encode-cursor cursor-data2)]
      (is (not= encoded1 encoded2))))

  (testing "Encode with all field types"
    (let [cursor-data {:id (UUID/randomUUID)
                       :sort-value (Instant/now)
                       :sort-field "created-at"
                       :sort-direction :desc
                       :timestamp (Instant/now)}
          encoded (cursor/encode-cursor cursor-data)]
      (is (string? encoded))
      (is (pos? (count encoded))))))

;; =============================================================================
;; Decoding Tests
;; =============================================================================

(deftest ^:unit decode-cursor-test
  (testing "Decode returns structured data"
    (let [cursor-data {:id (UUID/fromString "123e4567-e89b-12d3-a456-426614174000")
                       :sort-value "test"
                       :sort-field "field"
                       :sort-direction :asc}
          encoded (cursor/encode-cursor cursor-data)
          decoded (cursor/decode-cursor encoded)]
      (is (map? decoded))
      (is (uuid? (:id decoded)))
      (is (contains? decoded :sort-value))
      (is (contains? decoded :sort-field))
      (is (keyword? (:sort-direction decoded)))))

  (testing "Decode invalid Base64 throws exception"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid Base64 cursor"
         (cursor/decode-cursor "!!!invalid-base64!!!"))))

  (testing "Decode invalid JSON throws exception"
    (let [invalid-json-cursor (.encodeToString
                               (java.util.Base64/getEncoder)
                               (.getBytes "{invalid json}" "UTF-8"))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid JSON in cursor"
           (cursor/decode-cursor invalid-json-cursor)))))

  (testing "Decode missing required fields throws exception"
    (let [incomplete-data {:id "123e4567-e89b-12d3-a456-426614174000"}
          json-str (json/generate-string incomplete-data)
          encoded (.encodeToString
                   (java.util.Base64/getEncoder)
                   (.getBytes json-str "UTF-8"))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Missing required cursor fields"
           (cursor/decode-cursor encoded)))))

  (testing "Decode invalid UUID throws exception"
    (let [invalid-data {:id "not-a-uuid"
                        :sort-value "test"
                        :sort-field "field"
                        :sort-direction "asc"}
          json-str (json/generate-string invalid-data)
          encoded (.encodeToString
                   (java.util.Base64/getEncoder)
                   (.getBytes json-str "UTF-8"))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Failed to parse cursor data"
           (cursor/decode-cursor encoded))))))

;; =============================================================================
;; Validation Tests
;; =============================================================================

(deftest ^:unit valid-cursor-test
  (testing "Valid cursor returns true"
    (let [cursor-data {:id (UUID/randomUUID)
                       :sort-value "test"
                       :sort-field "field"
                       :sort-direction :asc}
          encoded (cursor/encode-cursor cursor-data)]
      (is (true? (cursor/valid-cursor? encoded)))))

  (testing "Invalid Base64 returns false"
    (is (false? (cursor/valid-cursor? "!!!invalid!!!"))))

  (testing "Invalid JSON returns false"
    (let [invalid-json-cursor (.encodeToString
                               (java.util.Base64/getEncoder)
                               (.getBytes "{invalid}" "UTF-8"))]
      (is (false? (cursor/valid-cursor? invalid-json-cursor)))))

  (testing "Missing fields returns false"
    (let [incomplete-data {:id "123e4567-e89b-12d3-a456-426614174000"}
          json-str (json/generate-string incomplete-data)
          encoded (.encodeToString
                   (java.util.Base64/getEncoder)
                   (.getBytes json-str "UTF-8"))]
      (is (false? (cursor/valid-cursor? encoded)))))

  (testing "Empty string returns false"
    (is (false? (cursor/valid-cursor? ""))))

  (testing "Nil returns false"
    (is (false? (cursor/valid-cursor? nil)))))

;; =============================================================================
;; Cursor Expiry Tests
;; =============================================================================

(deftest ^:unit cursor-expired-test
  (testing "Cursor with recent timestamp is not expired"
    (let [cursor-data {:id (UUID/randomUUID)
                       :sort-value "test"
                       :timestamp (Instant/now)}
          ttl-seconds 3600]  ; 1 hour
      (is (false? (cursor/cursor-expired? cursor-data ttl-seconds)))))

  (testing "Cursor with old timestamp is expired"
    (let [old-time (.minusSeconds (Instant/now) 7200)  ; 2 hours ago
          cursor-data {:id (UUID/randomUUID)
                       :sort-value "test"
                       :timestamp old-time}
          ttl-seconds 3600]  ; 1 hour
      (is (true? (cursor/cursor-expired? cursor-data ttl-seconds)))))

  (testing "Cursor without timestamp is not expired"
    (let [cursor-data {:id (UUID/randomUUID)
                       :sort-value "test"}
          ttl-seconds 3600]
      (is (false? (cursor/cursor-expired? cursor-data ttl-seconds)))))

  (testing "Cursor at exact TTL boundary"
    (let [exact-time (.minusSeconds (Instant/now) 3600)
          cursor-data {:id (UUID/randomUUID)
                       :sort-value "test"
                       :timestamp exact-time}
          ttl-seconds 3600]
      ;; At exact boundary, cursor should be expired (>=)
      (is (true? (cursor/cursor-expired? cursor-data ttl-seconds))))))

;; =============================================================================
;; Edge Cases and Error Handling
;; =============================================================================

(deftest ^:unit edge-cases-test
  (testing "Encode/decode with very long string"
    (let [long-string (apply str (repeat 1000 "a"))
          cursor-data {:id (UUID/randomUUID)
                       :sort-value long-string
                       :sort-field "description"
                       :sort-direction :asc}
          encoded (cursor/encode-cursor cursor-data)
          decoded (cursor/decode-cursor encoded)]
      (is (= long-string (:sort-value decoded)))))

  (testing "Encode/decode with special characters"
    (let [special-chars "🔥 特殊字符 émoji ñ ü"
          cursor-data {:id (UUID/randomUUID)
                       :sort-value special-chars
                       :sort-field "name"
                       :sort-direction :asc}
          encoded (cursor/encode-cursor cursor-data)
          decoded (cursor/decode-cursor encoded)]
      (is (= special-chars (:sort-value decoded)))))

  (testing "Encode/decode with nil sort-value"
    (let [cursor-data {:id (UUID/randomUUID)
                       :sort-value nil
                       :sort-field "optional-field"
                       :sort-direction :asc}
          encoded (cursor/encode-cursor cursor-data)
          decoded (cursor/decode-cursor encoded)]
      (is (nil? (:sort-value decoded)))))

  (testing "Encode/decode with boolean sort-value"
    (let [cursor-data {:id (UUID/randomUUID)
                       :sort-value true
                       :sort-field "active"
                       :sort-direction :asc}
          encoded (cursor/encode-cursor cursor-data)
          decoded (cursor/decode-cursor encoded)]
      (is (= true (:sort-value decoded)))))

  (testing "Encode/decode with zero values"
    (let [cursor-data {:id (UUID/randomUUID)
                       :sort-value 0
                       :sort-field "count"
                       :sort-direction :asc}
          encoded (cursor/encode-cursor cursor-data)
          decoded (cursor/decode-cursor encoded)]
      (is (= 0 (:sort-value decoded)))))

  (testing "Multiple round-trips preserve data"
    (let [cursor-data {:id (UUID/randomUUID)
                       :sort-value "test"
                       :sort-field "field"
                       :sort-direction :asc}
          encoded1 (cursor/encode-cursor cursor-data)
          decoded1 (cursor/decode-cursor encoded1)
          encoded2 (cursor/encode-cursor decoded1)
          decoded2 (cursor/decode-cursor encoded2)]
      (is (= (:id decoded1) (:id decoded2)))
      (is (= (:sort-value decoded1) (:sort-value decoded2)))
      (is (= (:sort-field decoded1) (:sort-field decoded2)))
      (is (= (:sort-direction decoded1) (:sort-direction decoded2))))))

;; =============================================================================
;; Data Type Preservation Tests
;; =============================================================================

(deftest ^:unit data-type-preservation-test
  (testing "UUID type is preserved"
    (let [uuid (UUID/randomUUID)
          cursor-data {:id uuid
                       :sort-value "test"
                       :sort-field "field"
                       :sort-direction :asc}
          encoded (cursor/encode-cursor cursor-data)
          decoded (cursor/decode-cursor encoded)]
      (is (instance? UUID (:id decoded)))
      (is (= uuid (:id decoded)))))

  (testing "Instant type is preserved"
    (let [instant (Instant/now)
          cursor-data {:id (UUID/randomUUID)
                       :sort-value instant
                       :sort-field "created-at"
                       :sort-direction :asc}
          encoded (cursor/encode-cursor cursor-data)
          decoded (cursor/decode-cursor encoded)]
      (is (instance? Instant (:sort-value decoded)))
      (is (= instant (:sort-value decoded)))))

  (testing "Keyword type is preserved for sort-direction"
    (let [cursor-data {:id (UUID/randomUUID)
                       :sort-value "test"
                       :sort-field "field"
                       :sort-direction :desc}
          encoded (cursor/encode-cursor cursor-data)
          decoded (cursor/decode-cursor encoded)]
      (is (keyword? (:sort-direction decoded)))
      (is (= :desc (:sort-direction decoded)))))

  (testing "String type is preserved"
    (let [cursor-data {:id (UUID/randomUUID)
                       :sort-value "test-value"
                       :sort-field "field"
                       :sort-direction :asc}
          encoded (cursor/encode-cursor cursor-data)
          decoded (cursor/decode-cursor encoded)]
      (is (string? (:sort-value decoded)))
      (is (= "test-value" (:sort-value decoded)))))

  (testing "Integer type is preserved"
    (let [cursor-data {:id (UUID/randomUUID)
                       :sort-value 12345
                       :sort-field "count"
                       :sort-direction :asc}
          encoded (cursor/encode-cursor cursor-data)
          decoded (cursor/decode-cursor encoded)]
      (is (integer? (:sort-value decoded)))
      (is (= 12345 (:sort-value decoded)))))

  (testing "Double type is preserved"
    (let [cursor-data {:id (UUID/randomUUID)
                       :sort-value 123.45
                       :sort-field "price"
                       :sort-direction :asc}
          encoded (cursor/encode-cursor cursor-data)
          decoded (cursor/decode-cursor encoded)]
      (is (double? (:sort-value decoded)))
      (is (== 123.45 (:sort-value decoded))))))

;; =============================================================================
;; Concurrent Encoding Tests
;; =============================================================================

(deftest ^:unit concurrent-encoding-test
  (testing "Multiple threads can encode/decode concurrently"
    (let [cursor-data-fn (fn [i]
                           {:id (UUID/randomUUID)
                            :sort-value (str "value-" i)
                            :sort-field "field"
                            :sort-direction (if (even? i) :asc :desc)})
          cursors (vec (pmap (fn [i]
                               (let [data (cursor-data-fn i)
                                     encoded (cursor/encode-cursor data)]
                                 {:original data :encoded encoded}))
                             (range 100)))
          decoded-cursors (pmap (fn [{:keys [encoded]}]
                                  (cursor/decode-cursor encoded))
                                cursors)]
      ;; All cursors should decode successfully
      (is (= 100 (count decoded-cursors)))
      ;; All decoded cursors should have valid UUIDs
      (is (every? #(instance? UUID (:id %)) decoded-cursors))
      ;; All decoded cursors should have sort-direction as keyword
      (is (every? #(keyword? (:sort-direction %)) decoded-cursors)))))
