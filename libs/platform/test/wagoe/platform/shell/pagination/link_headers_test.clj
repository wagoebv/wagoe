(ns wagoe.platform.shell.pagination.link-headers-test
  "Integration tests for RFC 5988 Link header generation.
   
   These tests verify Link header construction for both offset and cursor
   pagination, including URL building, query parameter handling, and RFC
   compliance."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [wagoe.platform.shell.pagination.link-headers :as link-headers]))

;; =============================================================================
;; URL Building Tests
;; =============================================================================

(deftest ^:unit build-query-string-test
  (testing "Build query string with multiple parameters"
    (let [params {:limit 20 :offset 40 :sort "name"}
          result (link-headers/build-query-string params)]
      (is (string? result))
      (is (str/includes? result "limit=20"))
      (is (str/includes? result "offset=40"))
      (is (str/includes? result "sort=name"))))

  (testing "Build query string with nil values filtered out"
    (let [params {:limit 20 :offset nil :sort "name"}
          result (link-headers/build-query-string params)]
      (is (string? result))
      (is (str/includes? result "limit=20"))
      (is (str/includes? result "sort=name"))
      (is (not (str/includes? result "offset")))))

  (testing "Build query string with URL-encoded values"
    (let [params {:sort "first name" :filter "status=active"}
          result (link-headers/build-query-string params)]
      (is (string? result))
      ;; Space should be encoded
      (is (or (str/includes? result "first+name")
              (str/includes? result "first%20name")))
      ;; Equals sign should be encoded
      (is (or (str/includes? result "status%3Dactive")
              (str/includes? result "status=active")))))

  (testing "Build query string with empty params"
    (let [result (link-headers/build-query-string {})]
      (is (string? result))
      (is (str/blank? result))))

  (testing "Build query string with all nil values"
    (let [params {:limit nil :offset nil}
          result (link-headers/build-query-string params)]
      (is (string? result))
      (is (str/blank? result)))))

(deftest ^:unit build-url-test
  (testing "Build URL with query parameters"
    (let [base-path "/api/v1/users"
          params {:limit 20 :offset 40}
          result (link-headers/build-url base-path params)]
      (is (string? result))
      (is (str/starts-with? result "/api/v1/users?"))
      (is (str/includes? result "limit=20"))
      (is (str/includes? result "offset=40"))))

  (testing "Build URL with no query parameters"
    (let [base-path "/api/v1/users"
          params {}
          result (link-headers/build-url base-path params)]
      (is (= "/api/v1/users" result))
      (is (not (str/includes? result "?")))))

  (testing "Build URL with filtered nil parameters"
    (let [base-path "/api/v1/users"
          params {:limit 20 :offset nil}
          result (link-headers/build-url base-path params)]
      (is (str/starts-with? result "/api/v1/users?"))
      (is (str/includes? result "limit=20"))
      (is (not (str/includes? result "offset")))))

  (testing "Build URL with absolute URL base"
    (let [base-path "https://api.example.com/v1/users"
          params {:limit 20}
          result (link-headers/build-url base-path params)]
      (is (str/starts-with? result "https://api.example.com/v1/users?"))
      (is (str/includes? result "limit=20")))))

;; =============================================================================
;; Link Header Format Tests
;; =============================================================================

(deftest ^:unit build-link-header-test
  (testing "Build header with single link"
    (let [links [{:url "/api/v1/users?offset=20&limit=20" :rel :next}]
          result (link-headers/build-link-header links)]
      (is (string? result))
      (is (str/includes? result "</api/v1/users?offset=20&limit=20>"))
      (is (str/includes? result "rel=\"next\""))))

  (testing "Build header with multiple links"
    (let [links [{:url "/api/v1/users?offset=0&limit=20" :rel :first}
                 {:url "/api/v1/users?offset=20&limit=20" :rel :next}
                 {:url "/api/v1/users?offset=980&limit=20" :rel :last}]
          result (link-headers/build-link-header links)]
      (is (string? result))
      ;; Should contain all three links separated by ", "
      (is (str/includes? result "rel=\"first\""))
      (is (str/includes? result "rel=\"next\""))
      (is (str/includes? result "rel=\"last\""))
      ;; Links should be separated by ", " (comma space)
      (is (>= (count (str/split result #", ")) 3))))

  (testing "Build header with empty links returns nil"
    (let [result (link-headers/build-link-header [])]
      (is (nil? result))))

  (testing "Build header with nil links returns nil"
    (let [result (link-headers/build-link-header nil)]
      (is (nil? result))))

  (testing "RFC 5988 format compliance"
    (let [links [{:url "/test" :rel :next}]
          result (link-headers/build-link-header links)]
      ;; Must have angle brackets around URL
      (is (re-find #"<[^>]+>" result))
      ;; Must have quoted rel value
      (is (re-find #"rel=\"[^\"]+\"" result))
      ;; Must follow pattern: <url>; rel="relation"
      (is (re-matches #"<[^>]+>;\s*rel=\"[^\"]+\"" result)))))

;; =============================================================================
;; Offset Pagination Link Tests
;; =============================================================================

(deftest ^:unit build-offset-links-first-page-test
  (testing "First page of offset pagination"
    (let [base-path "/api/v1/users"
          params {:limit 20 :offset 0 :sort "name"}
          pagination-meta {:type "offset"
                           :total 100
                           :offset 0
                           :limit 20
                           :has-next true
                           :has-prev false
                           :next-offset 20
                           :prev-offset nil}
          links (link-headers/build-offset-links base-path params pagination-meta)]
      ;; Should have first, self, next, last
      (is (= 4 (count links)))
      (is (some #(= :first (:rel %)) links))
      (is (some #(= :self (:rel %)) links))
      (is (some #(= :next (:rel %)) links))
      (is (some #(= :last (:rel %)) links))
      ;; Should NOT have prev
      (is (not (some #(= :prev (:rel %)) links)))
      ;; First and self should point to offset=0
      (let [first-link (first (filter #(= :first (:rel %)) links))
            self-link (first (filter #(= :self (:rel %)) links))]
        (is (str/includes? (:url first-link) "offset=0"))
        (is (str/includes? (:url self-link) "offset=0")))
      ;; Next should point to offset=20
      (let [next-link (first (filter #(= :next (:rel %)) links))]
        (is (str/includes? (:url next-link) "offset=20"))))))

(deftest ^:unit build-offset-links-middle-page-test
  (testing "Middle page of offset pagination"
    (let [base-path "/api/v1/users"
          params {:limit 20 :offset 40 :sort "name"}
          pagination-meta {:type "offset"
                           :total 100
                           :offset 40
                           :limit 20
                           :has-next true
                           :has-prev true
                           :next-offset 60
                           :prev-offset 20}
          links (link-headers/build-offset-links base-path params pagination-meta)]
      ;; Should have all five: first, prev, self, next, last
      (is (= 5 (count links)))
      (is (some #(= :first (:rel %)) links))
      (is (some #(= :prev (:rel %)) links))
      (is (some #(= :self (:rel %)) links))
      (is (some #(= :next (:rel %)) links))
      (is (some #(= :last (:rel %)) links))
      ;; Verify correct offsets
      (let [prev-link (first (filter #(= :prev (:rel %)) links))
            self-link (first (filter #(= :self (:rel %)) links))
            next-link (first (filter #(= :next (:rel %)) links))]
        (is (str/includes? (:url prev-link) "offset=20"))
        (is (str/includes? (:url self-link) "offset=40"))
        (is (str/includes? (:url next-link) "offset=60"))))))

(deftest ^:unit build-offset-links-last-page-test
  (testing "Last page of offset pagination"
    (let [base-path "/api/v1/users"
          params {:limit 20 :offset 80 :sort "name"}
          pagination-meta {:type "offset"
                           :total 100
                           :offset 80
                           :limit 20
                           :has-next false
                           :has-prev true
                           :next-offset nil
                           :prev-offset 60}
          links (link-headers/build-offset-links base-path params pagination-meta)]
      ;; Should have first, prev, self, last
      (is (= 4 (count links)))
      (is (some #(= :first (:rel %)) links))
      (is (some #(= :prev (:rel %)) links))
      (is (some #(= :self (:rel %)) links))
      (is (some #(= :last (:rel %)) links))
      ;; Should NOT have next
      (is (not (some #(= :next (:rel %)) links)))
      ;; Self and last should point to offset=80
      (let [self-link (first (filter #(= :self (:rel %)) links))
            last-link (first (filter #(= :last (:rel %)) links))]
        (is (str/includes? (:url self-link) "offset=80"))
        (is (str/includes? (:url last-link) "offset=80"))))))

(deftest ^:unit build-offset-links-single-page-test
  (testing "Single page (total <= limit)"
    (let [base-path "/api/v1/users"
          params {:limit 20 :offset 0}
          pagination-meta {:type "offset"
                           :total 15
                           :offset 0
                           :limit 20
                           :has-next false
                           :has-prev false
                           :next-offset nil
                           :prev-offset nil}
          links (link-headers/build-offset-links base-path params pagination-meta)]
      ;; Should have first, self, last only (no prev/next)
      (is (= 3 (count links)))
      (is (some #(= :first (:rel %)) links))
      (is (some #(= :self (:rel %)) links))
      (is (some #(= :last (:rel %)) links))
      (is (not (some #(= :prev (:rel %)) links)))
      (is (not (some #(= :next (:rel %)) links))))))

(deftest ^:unit build-offset-links-preserves-query-params-test
  (testing "Offset links preserve non-pagination query parameters"
    (let [base-path "/api/v1/users"
          params {:limit 20 :offset 40 :sort "name" :filter "active" :search "john"}
          pagination-meta {:type "offset"
                           :total 100
                           :offset 40
                           :limit 20
                           :has-next true
                           :has-prev true
                           :next-offset 60
                           :prev-offset 20}
          links (link-headers/build-offset-links base-path params pagination-meta)]
      ;; All links should preserve sort, filter, search params
      (doseq [link links]
        (is (str/includes? (:url link) "sort=name"))
        (is (str/includes? (:url link) "filter=active"))
        (is (str/includes? (:url link) "search=john"))))))

;; =============================================================================
;; Cursor Pagination Link Tests
;; =============================================================================

(deftest ^:unit build-cursor-links-with-both-cursors-test
  (testing "Cursor pagination with both prev and next cursors"
    (let [base-path "/api/v1/users"
          params {:limit 20 :cursor "current123" :sort "name"}
          pagination-meta {:type "cursor"
                           :limit 20
                           :has-next true
                           :has-prev true
                           :next-cursor "next456"
                           :prev-cursor "prev789"}
          links (link-headers/build-cursor-links base-path params pagination-meta)]
      ;; Should have prev, self, next (no first/last in cursor pagination)
      (is (= 3 (count links)))
      (is (some #(= :prev (:rel %)) links))
      (is (some #(= :self (:rel %)) links))
      (is (some #(= :next (:rel %)) links))
      ;; Should NOT have first/last
      (is (not (some #(= :first (:rel %)) links)))
      (is (not (some #(= :last (:rel %)) links)))
      ;; Verify cursor values
      (let [prev-link (first (filter #(= :prev (:rel %)) links))
            self-link (first (filter #(= :self (:rel %)) links))
            next-link (first (filter #(= :next (:rel %)) links))]
        (is (str/includes? (:url prev-link) "cursor=prev789"))
        (is (str/includes? (:url self-link) "cursor=current123"))
        (is (str/includes? (:url next-link) "cursor=next456"))))))

(deftest ^:unit build-cursor-links-first-page-test
  (testing "First page of cursor pagination (no prev cursor)"
    (let [base-path "/api/v1/users"
          params {:limit 20 :sort "name"}
          pagination-meta {:type "cursor"
                           :limit 20
                           :has-next true
                           :has-prev false
                           :next-cursor "next456"
                           :prev-cursor nil}
          links (link-headers/build-cursor-links base-path params pagination-meta)]
      ;; Should only have next (no prev, no self without cursor)
      (is (= 1 (count links)))
      (is (some #(= :next (:rel %)) links))
      (is (not (some #(= :prev (:rel %)) links)))
      (is (not (some #(= :self (:rel %)) links))))))

(deftest ^:unit build-cursor-links-last-page-test
  (testing "Last page of cursor pagination (no next cursor)"
    (let [base-path "/api/v1/users"
          params {:limit 20 :cursor "current123" :sort "name"}
          pagination-meta {:type "cursor"
                           :limit 20
                           :has-next false
                           :has-prev true
                           :next-cursor nil
                           :prev-cursor "prev789"}
          links (link-headers/build-cursor-links base-path params pagination-meta)]
      ;; Should have prev and self only
      (is (= 2 (count links)))
      (is (some #(= :prev (:rel %)) links))
      (is (some #(= :self (:rel %)) links))
      (is (not (some #(= :next (:rel %)) links))))))

(deftest ^:unit build-cursor-links-preserves-query-params-test
  (testing "Cursor links preserve non-pagination query parameters"
    (let [base-path "/api/v1/users"
          params {:limit 20 :cursor "current123" :sort "name" :filter "active"}
          pagination-meta {:type "cursor"
                           :limit 20
                           :has-next true
                           :has-prev true
                           :next-cursor "next456"
                           :prev-cursor "prev789"}
          links (link-headers/build-cursor-links base-path params pagination-meta)]
      ;; All links should preserve sort and filter params
      (doseq [link links]
        (is (str/includes? (:url link) "sort=name"))
        (is (str/includes? (:url link) "filter=active"))
        ;; Should NOT include offset (offset and cursor mutually exclusive)
        (is (not (str/includes? (:url link) "offset=")))))))

;; =============================================================================
;; High-Level API Tests
;; =============================================================================

(deftest ^:unit generate-link-header-offset-test
  (testing "Generate Link header for offset pagination"
    (let [base-path "/api/v1/users"
          params {:limit 20 :offset 40 :sort "name"}
          pagination-meta {:type "offset"
                           :total 100
                           :offset 40
                           :limit 20
                           :has-next true
                           :has-prev true
                           :next-offset 60
                           :prev-offset 20}
          result (link-headers/generate-link-header base-path params pagination-meta)]
      (is (string? result))
      ;; Should contain all relation types
      (is (str/includes? result "rel=\"first\""))
      (is (str/includes? result "rel=\"prev\""))
      (is (str/includes? result "rel=\"self\""))
      (is (str/includes? result "rel=\"next\""))
      (is (str/includes? result "rel=\"last\""))
      ;; Should be RFC 5988 compliant
      (is (re-find #"<[^>]+>;\s*rel=\"[^\"]+\"" result)))))

(deftest ^:unit generate-link-header-cursor-test
  (testing "Generate Link header for cursor pagination"
    (let [base-path "/api/v1/users"
          params {:limit 20 :cursor "current123" :sort "name"}
          pagination-meta {:type "cursor"
                           :limit 20
                           :has-next true
                           :has-prev true
                           :next-cursor "next456"
                           :prev-cursor "prev789"}
          result (link-headers/generate-link-header base-path params pagination-meta)]
      (is (string? result))
      ;; Should contain cursor relation types
      (is (str/includes? result "rel=\"prev\""))
      (is (str/includes? result "rel=\"self\""))
      (is (str/includes? result "rel=\"next\""))
      ;; Should NOT contain first/last (not available in cursor pagination)
      (is (not (str/includes? result "rel=\"first\"")))
      (is (not (str/includes? result "rel=\"last\""))))))

(deftest ^:unit generate-link-header-empty-results-test
  (testing "Generate Link header for empty results"
    (let [base-path "/api/v1/users"
          params {:limit 20 :offset 0}
          pagination-meta {:type "offset"
                           :total 0
                           :offset 0
                           :limit 20
                           :has-next false
                           :has-prev false
                           :next-offset nil
                           :prev-offset nil}
          result (link-headers/generate-link-header base-path params pagination-meta)]
      (is (string? result))
      ;; Even empty results should have first, self, last pointing to offset=0
      (is (str/includes? result "rel=\"first\""))
      (is (str/includes? result "rel=\"self\""))
      (is (str/includes? result "rel=\"last\"")))))

(deftest ^:unit generate-link-header-unknown-type-test
  (testing "Generate Link header with unknown pagination type"
    (let [base-path "/api/v1/users"
          params {:limit 20}
          pagination-meta {:type "unknown"}
          result (link-headers/generate-link-header base-path params pagination-meta)]
      ;; Should return nil for unknown type
      (is (nil? result)))))

;; =============================================================================
;; Edge Cases and Error Handling
;; =============================================================================

(deftest ^:unit edge-cases-test
  (testing "Empty base path"
    (let [pagination-meta {:type "offset"
                           :total 100
                           :offset 0
                           :limit 20
                           :has-next true
                           :has-prev false
                           :next-offset 20}
          result (link-headers/generate-link-header "" {} pagination-meta)]
      (is (string? result))
      ;; Should still generate links even with empty base
      (is (str/includes? result "rel=\"next\""))))

  (testing "Very large offset"
    (let [base-path "/api/v1/users"
          params {:limit 20 :offset 999980}
          pagination-meta {:type "offset"
                           :total 1000000
                           :offset 999980
                           :limit 20
                           :has-next false
                           :has-prev true
                           :next-offset nil
                           :prev-offset 999960}
          result (link-headers/generate-link-header base-path params pagination-meta)]
      (is (string? result))
      (is (str/includes? result "offset=999980"))))

  (testing "Special characters in query parameters"
    (let [base-path "/api/v1/users"
          params {:limit 20 :offset 0 :search "name with spaces"}
          pagination-meta {:type "offset"
                           :total 100
                           :offset 0
                           :limit 20
                           :has-next true
                           :has-prev false
                           :next-offset 20}
          result (link-headers/generate-link-header base-path params pagination-meta)]
      (is (string? result))
      ;; Spaces should be URL encoded
      (is (or (str/includes? result "name+with+spaces")
              (str/includes? result "name%20with%20spaces")))))

  (testing "Nil pagination metadata handles gracefully"
    ;; generate-link-header returns nil for nil metadata (no links to build)
    (let [result (link-headers/generate-link-header "/api/v1/users" {} nil)]
      (is (nil? result) "Nil metadata should produce nil (no Link header)")))

  (testing "Last page calculation with exact divisor"
    (let [base-path "/api/v1/users"
          params {:limit 20 :offset 0}
          pagination-meta {:type "offset"
                           :total 100
                           :offset 0
                           :limit 20
                           :has-next true
                           :has-prev false
                           :next-offset 20}
          links (link-headers/build-offset-links base-path params pagination-meta)
          last-link (first (filter #(= :last (:rel %)) links))]
      ;; Last page should be at offset 80 (100 - 20)
      (is (str/includes? (:url last-link) "offset=80"))))

  (testing "Last page calculation with remainder"
    (let [base-path "/api/v1/users"
          params {:limit 20 :offset 0}
          pagination-meta {:type "offset"
                           :total 95
                           :offset 0
                           :limit 20
                           :has-next true
                           :has-prev false
                           :next-offset 20}
          links (link-headers/build-offset-links base-path params pagination-meta)
          last-link (first (filter #(= :last (:rel %)) links))]
      ;; Last page should be at offset 80 (floor to nearest limit boundary before 95)
      (is (str/includes? (:url last-link) "offset=80")))))

;; =============================================================================
;; Integration with Real URLs
;; =============================================================================

(deftest ^:unit realistic-url-examples-test
  (testing "Production-like absolute URL with HTTPS"
    (let [base-path "https://api.example.com/v1/users"
          params {:limit 20 :offset 40 :sort "created_at" :filter "active"}
          pagination-meta {:type "offset"
                           :total 200
                           :offset 40
                           :limit 20
                           :has-next true
                           :has-prev true
                           :next-offset 60
                           :prev-offset 20}
          result (link-headers/generate-link-header base-path params pagination-meta)]
      (is (string? result))
      (is (str/includes? result "https://api.example.com/v1/users"))
      (is (str/includes? result "sort=created_at"))
      (is (str/includes? result "filter=active"))))

  (testing "Versioned API endpoint"
    (let [base-path "/api/v2/resources"
          params {:limit 50 :offset 100}
          pagination-meta {:type "offset"
                           :total 500
                           :offset 100
                           :limit 50
                           :has-next true
                           :has-prev true
                           :next-offset 150
                           :prev-offset 50}
          result (link-headers/generate-link-header base-path params pagination-meta)]
      (is (string? result))
      (is (str/includes? result "/api/v2/resources"))
      (is (str/includes? result "limit=50"))))

  (testing "Nested resource endpoint"
    (let [base-path "/api/v1/users/123/orders"
          params {:limit 10 :offset 0}
          pagination-meta {:type "offset"
                           :total 50
                           :offset 0
                           :limit 10
                           :has-next true
                           :has-prev false
                           :next-offset 10}
          result (link-headers/generate-link-header base-path params pagination-meta)]
      (is (string? result))
      (is (str/includes? result "/api/v1/users/123/orders")))))
