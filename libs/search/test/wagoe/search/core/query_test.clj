(ns wagoe.search.core.query-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.search.core.query :as query]))

;; =============================================================================
;; sanitize-query
;; =============================================================================

(deftest ^:unit sanitize-query-test
  (testing "passes through normal query strings unchanged"
    (is (= "widget pro" (query/sanitize-query "widget pro"))))

  (testing "removes FTS-unsafe characters"
    (is (= "hello world" (query/sanitize-query "hello | world")))
    (is (= "hello world" (query/sanitize-query "hello & world")))
    (is (= "hello world" (query/sanitize-query "hello (world)")))
    (is (= "hello world" (query/sanitize-query "hello 'world'"))))

  (testing "trims leading and trailing whitespace"
    (is (= "widget" (query/sanitize-query "  widget  "))))

  (testing "collapses multiple spaces"
    (is (= "widget pro max" (query/sanitize-query "widget   pro  max"))))

  (testing "returns nil for nil input"
    (is (nil? (query/sanitize-query nil))))

  (testing "returns trimmed string for whitespace-only input"
    (is (= "" (query/sanitize-query "   ")))))

;; =============================================================================
;; empty-query?
;; =============================================================================

(deftest ^:unit empty-query-test
  (testing "nil is empty"
    (is (query/empty-query? nil)))

  (testing "blank string is empty"
    (is (query/empty-query? "   ")))

  (testing "only unsafe chars is effectively empty"
    (is (query/empty-query? "| & ! < > ( ) ' \" @ \\")))

  (testing "non-empty query is not empty"
    (is (not (query/empty-query? "widget"))))

  (testing "whitespace-padded query is not empty"
    (is (not (query/empty-query? "  widget  ")))))

;; =============================================================================
;; filter-terms
;; =============================================================================

(deftest ^:unit filter-terms-test
  (testing "sorted by key, json keys, string values"
    (is (= [["status" ["active"]] ["tenant_id" ["42"]]]
           (query/filter-terms {:tenant-id 42 :status "active"}))))
  (testing "nil and empty are no terms"
    (is (= [] (query/filter-terms nil)))
    (is (= [] (query/filter-terms {}))))
  (testing "a set or vector gives its sorted distinct strings; empty stays empty"
    (is (= [["conversation_id" ["1" "2"]]] (query/filter-terms {:conversation-id #{2 1}})))
    (is (= [["conversation_id" ["a" "b"]]] (query/filter-terms {:conversation-id ["b" "a" "b"]})))
    (is (= [["conversation_id" []]] (query/filter-terms {:conversation-id #{}})))))
