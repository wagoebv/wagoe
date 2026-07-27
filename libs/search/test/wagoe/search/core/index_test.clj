(ns wagoe.search.core.index-test
  "Unit tests for search document construction (pure)."
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.search.core.index :as index])
  (:import [java.time Instant]
           [java.util UUID]))

;; =============================================================================
;; build-document
;; =============================================================================

(def ^:private fixed-document-id "search-doc-001")
(def ^:private fixed-updated-at (Instant/parse "2026-04-10T12:00:00Z"))

(deftest ^:unit build-document-test
  (testing "maps fields to correct weight buckets"
    (let [definition {:id          :product-search
                      :entity-type :product
                      :language    :english
                      :fields      [{:name :title       :weight :a}
                                    {:name :description :weight :b}
                                    {:name :tags        :weight :c}]}
          entity-id  (UUID/fromString "11111111-1111-1111-1111-111111111111")
          doc        (index/build-document* definition entity-id
                                            {:title "Widget Pro"
                                             :description "A great widget"
                                             :tags "tools hardware"}
                                            {:id fixed-document-id
                                             :updated-at fixed-updated-at})]
      (is (= fixed-document-id (:id doc)))
      (is (= :product (:entity-type doc)))
      (is (= entity-id (:entity-id doc)))
      (is (= "Widget Pro" (:weight-a doc)))
      (is (= "A great widget" (:weight-b doc)))
      (is (= "tools hardware" (:weight-c doc)))
      (is (= "" (:weight-d doc)))
      (is (string? (:content-all doc)))
      (is (.contains (:content-all doc) "Widget Pro"))
      (is (.contains (:content-all doc) "A great widget"))
      (is (= fixed-updated-at (:updated-at doc)))))

  (testing "joins seq values with space"
    (let [definition {:id          :article-search
                      :entity-type :article
                      :language    :english
                      :fields      [{:name :tags :weight :c}]}
          entity-id  (UUID/fromString "22222222-2222-2222-2222-222222222222")
          doc        (index/build-document* definition entity-id
                                            {:tags ["clojure" "functional" "search"]}
                                            {:id fixed-document-id
                                             :updated-at fixed-updated-at})]
      (is (= "clojure functional search" (:weight-c doc)))
      (is (.contains (:content-all doc) "clojure"))))

  (testing "handles nil field values gracefully"
    (let [definition {:id          :product-search
                      :entity-type :product
                      :language    :english
                      :fields      [{:name :title       :weight :a}
                                    {:name :description :weight :b}]}
          entity-id  (UUID/fromString "33333333-3333-3333-3333-333333333333")
          doc        (index/build-document* definition entity-id {:title "Widget"}
                                            {:id fixed-document-id
                                             :updated-at fixed-updated-at})]
      (is (= "Widget" (:weight-a doc)))
      (is (= "" (:weight-b doc)))))

  (testing "attaches metadata when provided"
    (let [definition {:id          :product-search
                      :entity-type :product
                      :language    :english
                      :fields      [{:name :title :weight :a}]}
          entity-id  (UUID/fromString "44444444-4444-4444-4444-444444444444")
          doc        (index/build-document* definition entity-id {:title "Widget"}
                                            {:id fixed-document-id
                                             :updated-at fixed-updated-at
                                             :metadata {:price 9.99 :sku "WGT-001"}})]
      (is (= 9.99 (get-in doc [:metadata :price])))
      (is (= "WGT-001" (get-in doc [:metadata :sku])))))

  (testing "uses :english as default language when not specified"
    (let [definition {:id          :product-search
                      :entity-type :product
                      :fields      [{:name :title :weight :a}]}
          entity-id  (UUID/fromString "55555555-5555-5555-5555-555555555555")
          doc        (index/build-document* definition entity-id {:title "test"}
                                            {:id fixed-document-id
                                             :updated-at fixed-updated-at})]
      (is (= "english" (:language doc)))))

  (testing "uses definition language when specified"
    (let [definition {:id          :product-search
                      :entity-type :product
                      :language    :dutch
                      :fields      [{:name :title :weight :a}]}
          entity-id  (UUID/fromString "66666666-6666-6666-6666-666666666666")
          doc        (index/build-document* definition entity-id {:title "Widget"}
                                            {:id fixed-document-id
                                             :updated-at fixed-updated-at})]
      (is (= "dutch" (:language doc))))))
