(ns wagoe.platform.core.pagination.versioning-test
  "Unit tests for versioning core functions.
   
   Tests are pure - no I/O, no mocks, just function verification."
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.platform.core.pagination.versioning :as versioning]))

;; =============================================================================
;; Version Parsing Tests
;; =============================================================================

(deftest ^:unit parse-version-test
  (testing "Parse simple major version"
    (let [result (versioning/parse-version "v1")]
      (is (= 1 (:major result)))
      (is (= 0 (:minor result)))
      (is (= 0 (:patch result)))
      (is (= "v1" (:original result)))))

  (testing "Parse major.minor version"
    (let [result (versioning/parse-version "v2.1")]
      (is (= 2 (:major result)))
      (is (= 1 (:minor result)))
      (is (= 0 (:patch result)))
      (is (= "v2.1" (:original result)))))

  (testing "Parse major.minor.patch version"
    (let [result (versioning/parse-version "v1.2.3")]
      (is (= 1 (:major result)))
      (is (= 2 (:minor result)))
      (is (= 3 (:patch result)))
      (is (= "v1.2.3" (:original result)))))

  (testing "Parse version without 'v' prefix"
    (let [result (versioning/parse-version "2.1.0")]
      (is (= 2 (:major result)))
      (is (= 1 (:minor result)))
      (is (= 0 (:patch result)))))

  (testing "Parse experimental version (v0)"
    (let [result (versioning/parse-version "v0")]
      (is (= 0 (:major result)))
      (is (= 0 (:minor result)))
      (is (= 0 (:patch result)))))

  (testing "Parse keyword version"
    (let [result (versioning/parse-version :v1)]
      (is (= 1 (:major result)))
      (is (= "v1" (:original result)))))

  (testing "Parse invalid version format"
    (is (nil? (versioning/parse-version "invalid")))
    (is (nil? (versioning/parse-version "vX")))
    (is (nil? (versioning/parse-version "")))
    (is (nil? (versioning/parse-version "v")))))

(deftest ^:unit version-string-test
  (testing "Convert simple version to string"
    (is (= "v1" (versioning/version-string {:major 1 :minor 0 :patch 0}))))

  (testing "Convert version with minor to string"
    (is (= "v2.1" (versioning/version-string {:major 2 :minor 1 :patch 0}))))

  (testing "Convert full version to string"
    (is (= "v1.2.3" (versioning/version-string {:major 1 :minor 2 :patch 3}))))

  (testing "Convert experimental version to string"
    (is (= "v0" (versioning/version-string {:major 0 :minor 0 :patch 0})))))

;; =============================================================================
;; Version Comparison Tests
;; =============================================================================

(deftest ^:unit compare-versions-test
  (testing "Compare major versions"
    (is (= -1 (versioning/compare-versions "v1" "v2")))
    (is (= 1 (versioning/compare-versions "v2" "v1")))
    (is (= 0 (versioning/compare-versions "v1" "v1"))))

  (testing "Compare minor versions"
    (is (= -1 (versioning/compare-versions "v1.1" "v1.2")))
    (is (= 1 (versioning/compare-versions "v1.2" "v1.1")))
    (is (= 0 (versioning/compare-versions "v1.1" "v1.1"))))

  (testing "Compare patch versions"
    (is (= -1 (versioning/compare-versions "v1.2.3" "v1.2.4")))
    (is (= 1 (versioning/compare-versions "v1.2.4" "v1.2.3")))
    (is (= 0 (versioning/compare-versions "v1.2.3" "v1.2.3"))))

  (testing "Compare with implicit zeros"
    (is (= 0 (versioning/compare-versions "v1" "v1.0")))
    (is (= 0 (versioning/compare-versions "v1.0" "v1.0.0"))))

  (testing "Compare experimental with stable"
    (is (= -1 (versioning/compare-versions "v0" "v1")))
    (is (= 1 (versioning/compare-versions "v1" "v0"))))

  (testing "Compare invalid versions"
    (is (nil? (versioning/compare-versions "invalid" "v1")))
    (is (nil? (versioning/compare-versions "v1" "invalid")))))

(deftest ^:unit version-comparison-predicates-test
  (testing "version-greater-than?"
    (is (versioning/version-greater-than? "v2" "v1"))
    (is (not (versioning/version-greater-than? "v1" "v2")))
    (is (not (versioning/version-greater-than? "v1" "v1"))))

  (testing "version-less-than?"
    (is (versioning/version-less-than? "v1" "v2"))
    (is (not (versioning/version-less-than? "v2" "v1")))
    (is (not (versioning/version-less-than? "v1" "v1"))))

  (testing "version-equal?"
    (is (versioning/version-equal? "v1" "v1"))
    (is (versioning/version-equal? "v1" "v1.0"))
    (is (not (versioning/version-equal? "v1" "v2")))))

;; =============================================================================
;; Version Lifecycle Tests
;; =============================================================================

(deftest ^:unit is-experimental-test
  (testing "Experimental version (v0)"
    (is (versioning/is-experimental? "v0"))
    (is (versioning/is-experimental? :v0))
    (is (versioning/is-experimental? "v0.1"))
    (is (versioning/is-experimental? "v0.9.9")))

  (testing "Non-experimental versions"
    (is (not (versioning/is-experimental? "v1")))
    (is (not (versioning/is-experimental? "v2")))
    (is (not (versioning/is-experimental? "v1.2.3")))))

(deftest ^:unit is-stable-test
  (testing "Stable version"
    (let [config {:deprecated-versions #{:v0}}]
      (is (versioning/is-stable? "v1" config))
      (is (versioning/is-stable? "v2" config))
      (is (versioning/is-stable? :v1 config))))

  (testing "Experimental version is not stable"
    (let [config {:deprecated-versions #{}}]
      (is (not (versioning/is-stable? "v0" config)))))

  (testing "Deprecated version is not stable"
    (let [config {:deprecated-versions #{:v1}}]
      (is (not (versioning/is-stable? "v1" config)))
      (is (not (versioning/is-stable? :v1 config)))))

  (testing "Non-deprecated stable version"
    (let [config {:deprecated-versions #{:v1}}]
      (is (versioning/is-stable? "v2" config)))))

(deftest ^:unit is-deprecated-test
  (testing "Deprecated version"
    (let [config {:deprecated-versions #{:v1 :v2}}]
      (is (versioning/is-deprecated? "v1" config))
      (is (versioning/is-deprecated? :v1 config))
      (is (versioning/is-deprecated? "v2" config))))

  (testing "Non-deprecated version"
    (let [config {:deprecated-versions #{:v1}}]
      (is (not (versioning/is-deprecated? "v2" config)))
      (is (not (versioning/is-deprecated? :v2 config))))))

(deftest ^:unit get-sunset-date-test
  (testing "Get sunset date for version"
    (let [config {:sunset-dates {:v1 "2026-06-01"
                                 :v2 "2027-01-01"}}]
      (is (= "2026-06-01" (versioning/get-sunset-date :v1 config)))
      (is (= "2027-01-01" (versioning/get-sunset-date :v2 config)))))

  (testing "No sunset date for version"
    (let [config {:sunset-dates {:v1 "2026-06-01"}}]
      (is (nil? (versioning/get-sunset-date :v2 config)))
      (is (nil? (versioning/get-sunset-date :v3 config))))))

(deftest ^:unit is-sunset-test
  (testing "Version is sunset"
    (let [config {:sunset-dates {:v1 "2024-01-01"}}]
      (is (versioning/is-sunset? :v1 config "2024-01-02"))
      (is (versioning/is-sunset? :v1 config "2024-01-01"))
      (is (versioning/is-sunset? :v1 config "2025-12-31"))))

  (testing "Version is not sunset"
    (let [config {:sunset-dates {:v1 "2026-06-01"}}]
      (is (not (versioning/is-sunset? :v1 config "2024-01-01")))
      (is (not (versioning/is-sunset? :v1 config "2026-05-31")))))

  (testing "No sunset date"
    (let [config {:sunset-dates {}}]
      (is (not (versioning/is-sunset? :v1 config "2024-01-01"))))))

;; =============================================================================
;; Version Validation Tests
;; =============================================================================

(deftest ^:unit is-valid-version-test
  (testing "Valid version formats"
    (is (versioning/is-valid-version? "v1"))
    (is (versioning/is-valid-version? "v2.1"))
    (is (versioning/is-valid-version? "v1.2.3"))
    (is (versioning/is-valid-version? "v0")))

  (testing "Invalid version formats"
    (is (not (versioning/is-valid-version? "invalid")))
    (is (not (versioning/is-valid-version? "vX")))
    (is (not (versioning/is-valid-version? "")))
    (is (not (versioning/is-valid-version? "v")))))

(deftest ^:unit is-supported-version-test
  (testing "Supported version"
    (let [config {:supported-versions #{:v1 :v2 :v3}}]
      (is (versioning/is-supported-version? :v1 config))
      (is (versioning/is-supported-version? "v2" config))
      (is (versioning/is-supported-version? :v3 config))))

  (testing "Unsupported version"
    (let [config {:supported-versions #{:v1 :v2}}]
      (is (not (versioning/is-supported-version? :v3 config)))
      (is (not (versioning/is-supported-version? "v4" config))))))

(deftest ^:unit validate-version-test
  (testing "Valid supported version"
    (let [config {:supported-versions #{:v1 :v2}}
          result (versioning/validate-version* "v1" config "2026-04-10")]
      (is (:valid? result))
      (is (= "v1" (:version result)))
      (is (empty? (:errors result)))))

  (testing "Invalid version format"
    (let [config {:supported-versions #{:v1}}
          result (versioning/validate-version* "invalid" config "2026-04-10")]
      (is (not (:valid? result)))
      (is (= "invalid" (:version result)))
      (is (some #(re-find #"Invalid version format" %) (:errors result)))))

  (testing "Unsupported version"
    (let [config {:supported-versions #{:v1 :v2}}
          result (versioning/validate-version* "v3" config "2026-04-10")]
      (is (not (:valid? result)))
      (is (= "v3" (:version result)))
      (is (some #(re-find #"not supported" %) (:errors result)))))

  (testing "Keyword version"
    (let [config {:supported-versions #{:v1 :v2}}
          result (versioning/validate-version* :v1 config "2026-04-10")]
      (is (:valid? result))
      (is (= "v1" (:version result)))))

  (testing "Sunset version becomes invalid at explicit current date"
    (let [config {:supported-versions #{:v1}
                  :sunset-dates {:v1 "2026-04-10"}}
          result (versioning/validate-version* "v1" config "2026-04-10")]
      (is (not (:valid? result)))
      (is (some #(re-find #"has been sunset" %) (:errors result))))))

;; =============================================================================
;; Version Resolution Tests
;; =============================================================================

(deftest ^:unit resolve-default-version-test
  (testing "Resolve default version from config"
    (let [config {:default-version :v1}]
      (is (= :v1 (versioning/resolve-default-version config)))))

  (testing "Resolve default when not specified"
    (let [config {}]
      (is (= :v1 (versioning/resolve-default-version config))))))

(deftest ^:unit resolve-latest-version-test
  (testing "Resolve latest stable from config"
    (let [config {:latest-stable :v2}]
      (is (= :v2 (versioning/resolve-latest-version config)))))

  (testing "Fallback to default version"
    (let [config {:default-version :v1}]
      (is (= :v1 (versioning/resolve-latest-version config)))))

  (testing "Fallback to v1"
    (let [config {}]
      (is (= :v1 (versioning/resolve-latest-version config))))))

(deftest ^:unit extract-version-from-path-test
  (testing "Extract version from API path"
    (is (= :v1 (versioning/extract-version-from-path "/api/v1/users")))
    (is (= :v2 (versioning/extract-version-from-path "/api/v2/items")))
    (is (= :v1 (versioning/extract-version-from-path "/api/v1/users/123"))))

  (testing "Extract version with minor"
    (is (= :v2.1 (versioning/extract-version-from-path "/api/v2.1/users"))))

  (testing "Extract version with patch"
    (is (= :v1.2.3 (versioning/extract-version-from-path "/api/v1.2.3/users"))))

  (testing "No version in path"
    (is (nil? (versioning/extract-version-from-path "/api/users")))
    (is (nil? (versioning/extract-version-from-path "/users")))
    (is (nil? (versioning/extract-version-from-path "/web/users"))))

  (testing "Edge cases"
    (is (nil? (versioning/extract-version-from-path "")))
    (is (nil? (versioning/extract-version-from-path "/")))
    (is (nil? (versioning/extract-version-from-path "/api/invalid/users")))))

(deftest ^:unit extract-version-from-header-test
  (testing "Extract version from custom header"
    (let [headers {"x-api-version" "v1"}]
      (is (= :v1 (versioning/extract-version-from-header headers "x-api-version")))))

  (testing "Extract version from different header name"
    (let [headers {"api-version" "v2"}]
      (is (= :v2 (versioning/extract-version-from-header headers "api-version")))))

  (testing "No version header"
    (let [headers {}]
      (is (nil? (versioning/extract-version-from-header headers "x-api-version")))))

  (testing "Default header name"
    (let [headers {"x-api-version" "v1"}]
      (is (= :v1 (versioning/extract-version-from-header headers nil))))))

(deftest ^:unit resolve-version-test
  (testing "Resolve from URL path (highest priority)"
    (let [request {:uri "/api/v2/users"
                   :headers {"x-api-version" "v1"}}
          config {:default-version :v0}]
      (is (= :v2 (versioning/resolve-version request config)))))

  (testing "Resolve from header (second priority)"
    (let [request {:uri "/api/users"
                   :headers {"x-api-version" "v2"}}
          config {:default-version :v1}]
      (is (= :v2 (versioning/resolve-version request config)))))

  (testing "Resolve from config default (lowest priority)"
    (let [request {:uri "/api/users"
                   :headers {}}
          config {:default-version :v1}]
      (is (= :v1 (versioning/resolve-version request config)))))

  (testing "Resolve with no version anywhere"
    (let [request {:uri "/api/users"
                   :headers {}}
          config {}]
      (is (= :v1 (versioning/resolve-version request config))))))

;; =============================================================================
;; Version Metadata Tests
;; =============================================================================

(deftest ^:unit create-version-metadata-test
  (testing "Current version is latest"
    (let [config {:latest-stable :v1}
          result (versioning/create-version-metadata :v1 config)]
      (is (= "v1" (:version result)))
      (is (nil? (:latest-version result)))
      (is (nil? (:deprecated result)))
      (is (nil? (:sunset-date result)))))

  (testing "Current version is not latest"
    (let [config {:latest-stable :v2}
          result (versioning/create-version-metadata :v1 config)]
      (is (= "v1" (:version result)))
      (is (= "v2" (:latest-version result)))))

  (testing "Deprecated version"
    (let [config {:deprecated-versions #{:v1}}
          result (versioning/create-version-metadata :v1 config)]
      (is (= "v1" (:version result)))
      (is (true? (:deprecated result)))))

  (testing "Version with sunset date"
    (let [config {:sunset-dates {:v1 "2026-06-01"}}
          result (versioning/create-version-metadata :v1 config)]
      (is (= "v1" (:version result)))
      (is (= "2026-06-01" (:sunset-date result)))))

  (testing "Deprecated version with sunset date and latest different"
    (let [config {:latest-stable :v2
                  :deprecated-versions #{:v1}
                  :sunset-dates {:v1 "2026-06-01"}}
          result (versioning/create-version-metadata :v1 config)]
      (is (= "v1" (:version result)))
      (is (= "v2" (:latest-version result)))
      (is (true? (:deprecated result)))
      (is (= "2026-06-01" (:sunset-date result))))))

(deftest ^:unit version-headers-test
  (testing "Basic version headers"
    (let [config {:latest-stable :v1}
          headers (versioning/version-headers :v1 config)]
      (is (= "v1" (get headers "X-API-Version")))))

  (testing "Headers with latest version"
    (let [config {:latest-stable :v2}
          headers (versioning/version-headers :v1 config)]
      (is (= "v1" (get headers "X-API-Version")))
      (is (= "v2" (get headers "X-API-Version-Latest")))))

  (testing "Deprecated version headers"
    (let [config {:deprecated-versions #{:v1}}
          headers (versioning/version-headers :v1 config)]
      (is (= "v1" (get headers "X-API-Version")))
      (is (= "true" (get headers "X-API-Deprecated")))))

  (testing "Sunset date headers"
    (let [config {:sunset-dates {:v1 "2026-06-01"}}
          headers (versioning/version-headers :v1 config)]
      (is (= "v1" (get headers "X-API-Version")))
      (is (= "2026-06-01" (get headers "X-API-Sunset")))))

  (testing "All headers combined"
    (let [config {:latest-stable :v2
                  :deprecated-versions #{:v1}
                  :sunset-dates {:v1 "2026-06-01"}}
          headers (versioning/version-headers :v1 config)]
      (is (= "v1" (get headers "X-API-Version")))
      (is (= "v2" (get headers "X-API-Version-Latest")))
      (is (= "true" (get headers "X-API-Deprecated")))
      (is (= "2026-06-01" (get headers "X-API-Sunset"))))))

;; =============================================================================
;; Edge Cases and Error Handling
;; =============================================================================

(deftest ^:unit edge-cases-test
  (testing "Nil inputs"
    (is (nil? (versioning/parse-version nil)))
    (is (nil? (versioning/extract-version-from-path nil)))
    (is (nil? (versioning/extract-version-from-header nil "x-api-version"))))

  (testing "Empty strings"
    (is (nil? (versioning/parse-version "")))
    (is (nil? (versioning/extract-version-from-path ""))))

  (testing "Very large version numbers"
    (let [result (versioning/parse-version "v999.999.999")]
      (is (= 999 (:major result)))
      (is (= 999 (:minor result)))
      (is (= 999 (:patch result)))))

  (testing "Comparing with nil"
    (is (nil? (versioning/compare-versions nil "v1")))
    (is (nil? (versioning/compare-versions "v1" nil)))))
