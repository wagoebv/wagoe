(ns wagoe.search.shell.service-test
  "Unit tests for SearchService using an in-memory ISearchStore double."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [wagoe.search.ports :as ports]
            [wagoe.search.shell.registry :as registry]
            [wagoe.search.shell.service :as service]
            [clojure.string :as str])
  (:import [java.util UUID]))

;; =============================================================================
;; In-memory ISearchStore double
;; =============================================================================

(defrecord MemorySearchStore [documents]
  ports/ISearchStore

  (upsert-document! [_ doc]
    (let [k [(:index-id doc) (:entity-id doc)]]
      (swap! documents assoc k doc))
    doc)

  (delete-document! [_ index-id entity-id]
    (let [k [index-id entity-id]]
      (swap! documents dissoc k))
    nil)

  (search-documents [_ index-id entity-type query opts]
    (let [{:keys [limit offset] :or {limit 20 offset 0}} opts
          pattern (str/lower-case (or query ""))
          results (->> @documents
                       (filter (fn [[_ doc]]
                                 (and (= entity-type (:entity-type doc))
                                      (= index-id (:index-id doc)))))
                       (filter (fn [[_ doc]]
                                 (or (str/blank? pattern)
                                     (str/includes?
                                      (str/lower-case (or (:content-all doc) ""))
                                      pattern))))
                       (map second)
                       (sort-by :updated-at)
                       reverse)]
      (mapv (fn [doc]
              {:entity-type (:entity-type doc)
               :entity-id   (:entity-id doc)
               :rank        1.0
               :snippet     nil
               :metadata    (:metadata doc)})
            (take limit (drop offset results)))))

  (count-results [_ entity-type query opts]
    (let [{:keys [index-id]} opts
          pattern (str/lower-case (or query ""))]
      (->> @documents
           (filter (fn [[_ doc]]
                     (and (= entity-type (:entity-type doc))
                          (= index-id (:index-id doc)))))
           (filter (fn [[_ doc]]
                     (or (str/blank? pattern)
                         (str/includes?
                          (str/lower-case (or (:content-all doc) ""))
                          pattern))))
           count)))

  (suggest-documents [_ index-id entity-type query opts]
    (let [{:keys [limit] :or {limit 5}} opts
          pattern (str/lower-case (or query ""))
          results (->> @documents
                       (filter (fn [[_ doc]]
                                 (and (= entity-type (:entity-type doc))
                                      (= index-id (:index-id doc)))))
                       (filter (fn [[_ doc]]
                                 (str/includes?
                                  (str/lower-case (or (:content-all doc) ""))
                                  pattern)))
                       (map second))]
      (mapv (fn [doc]
              {:entity-type (:entity-type doc)
               :entity-id   (:entity-id doc)
               :rank        0.5
               :snippet     nil})
            (take limit results))))

  (count-documents [_ index-id]
    (->> @documents
         (filter (fn [[_ doc]] (= index-id (:index-id doc))))
         count)))

(defn- create-memory-store []
  (->MemorySearchStore (atom {})))

;; =============================================================================
;; Test state
;; =============================================================================

(def ^:private ^:dynamic *service* nil)

(defn- service-fixture [f]
  (registry/clear-registry!)
  (registry/register-search!
   {:id          :product-search
    :entity-type :product
    :language    :english
    :fields      [{:name :title       :weight :a}
                  {:name :description :weight :b}
                  {:name :tags        :weight :c}]
    :options     {:highlight? false}})
  (let [store   (create-memory-store)
        svc     (service/create-search-service store)]
    (binding [*service* svc]
      (f)))
  (registry/clear-registry!))

(use-fixtures :each service-fixture)

;; =============================================================================
;; index-document!
;; =============================================================================

(deftest ^:unit index-document-test
  (testing "indexes a document and returns it"
    (let [entity-id (UUID/randomUUID)
          doc-id    "search-doc-123"
          now       (java.time.Instant/parse "2026-04-10T12:00:00Z")]
      (with-redefs [wagoe.search.shell.service/generate-document-id (fn [] doc-id)
                    wagoe.search.shell.service/current-timestamp (fn [] now)]
        (let [doc (ports/index-document! *service* :product-search entity-id
                                         {:title "Widget Pro"
                                          :description "A great widget"
                                          :tags "tools"}
                                         {})]
          (is (= doc-id (:id doc)))
          (is (= now (:updated-at doc)))
          (is (= :product (:entity-type doc)))
          (is (= entity-id (:entity-id doc)))
          (is (= "Widget Pro" (:weight-a doc)))))))

  (testing "throws :not-found for unregistered index"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"not registered"
         (ports/index-document! *service* :unknown-index (UUID/randomUUID) {} {})))))

;; =============================================================================
;; remove-document!
;; =============================================================================

(deftest ^:unit remove-document-test
  (testing "removes a document from the index"
    (let [entity-id (UUID/randomUUID)]
      (ports/index-document! *service* :product-search entity-id {:title "Widget"} {})
      (ports/remove-document! *service* :product-search entity-id)
      (let [results (:results (ports/search *service* :product-search "Widget" {}))]
        (is (empty? results))))))

;; =============================================================================
;; search
;; =============================================================================

(deftest ^:unit search-test
  (testing "returns SearchResponse shape"
    (let [response (ports/search *service* :product-search "widget" {})]
      (is (map? response))
      (is (contains? response :results))
      (is (contains? response :total))
      (is (contains? response :query))
      (is (contains? response :took-ms))))

  (testing "finds indexed documents matching query"
    (let [entity-id (UUID/randomUUID)]
      (ports/index-document! *service* :product-search entity-id
                             {:title "Widget Pro"} {})
      (let [results (:results (ports/search *service* :product-search "widget" {}))]
        (is (= 1 (count results)))
        (is (= entity-id (:entity-id (first results)))))))

  (testing "returns empty results for empty query"
    (let [entity-id (UUID/randomUUID)]
      (ports/index-document! *service* :product-search entity-id
                             {:title "Widget Pro"} {})
      (let [response (ports/search *service* :product-search "" {})]
        (is (= [] (:results response)))
        (is (= 0 (:total response))))))

  (testing "throws :not-found for unregistered index"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"not registered"
         (ports/search *service* :unknown-index "widget" {})))))

;; =============================================================================
;; suggest
;; =============================================================================

(deftest ^:unit suggest-test
  (testing "returns empty vec for empty query"
    (is (= [] (ports/suggest *service* :product-search "" {}))))

  (testing "returns suggestions matching partial query"
    (let [entity-id (UUID/randomUUID)]
      (ports/index-document! *service* :product-search entity-id
                             {:title "Widget Pro Max"} {})
      (let [suggestions (ports/suggest *service* :product-search "wid" {})]
        (is (seq suggestions))
        (is (= entity-id (:entity-id (first suggestions))))))))

;; =============================================================================
;; list-indices
;; =============================================================================

(deftest ^:unit list-indices-test
  (testing "returns registered index info"
    (let [indices (ports/list-indices *service*)]
      (is (= 1 (count indices)))
      (is (= :product-search (:id (first indices))))
      (is (= :product (:entity-type (first indices))))))

  (testing "doc-count reflects indexed documents"
    (dotimes [_ 3]
      (ports/index-document! *service* :product-search (UUID/randomUUID)
                             {:title "Widget"} {}))
    (let [indices (ports/list-indices *service*)
          info    (first indices)]
      (is (= 3 (:doc-count info))))))

;; =============================================================================
;; reindex!
;; =============================================================================

(deftest ^:unit reindex-test
  (testing "bulk indexes all provided documents"
    (let [documents (map (fn [n]
                           [(UUID/randomUUID)
                            {:title (str "Widget " n)}])
                         (range 5))
          result    (ports/reindex! *service* :product-search documents)]
      (is (= {:indexed 5} result))
      (is (= 5 (:doc-count (first (ports/list-indices *service*))))))))
