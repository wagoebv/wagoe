(ns wagoe.search.shell.service-test
  "Unit tests for SearchService using an in-memory ISearchStore double."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [wagoe.search.ports :as ports]
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
         count))

  ports/ISearchStoreTenantDeletion
  (delete-tenant-document! [_ index-id entity-id tenant-id]
    (when (= (str tenant-id) (str (get-in @documents [[index-id entity-id] :filters :tenant-id])))
      (swap! documents dissoc [index-id entity-id]))
    nil))

(defn- create-memory-store []
  (->MemorySearchStore (atom {})))

;; =============================================================================
;; Test state
;; =============================================================================

(def ^:private ^:dynamic *service* nil)

(defn- service-fixture [f]
  (let [store   (create-memory-store)
        svc     (service/create-search-service
                 store
                 {:product-search {:id          :product-search
                                   :entity-type :product
                                   :language    :english
                                   :fields      [{:name :title       :weight :a}
                                                 {:name :description :weight :b}
                                                 {:name :tags        :weight :c}]
                                   :options     {:highlight? false}}})]
    (binding [*service* svc]
      (f))))

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

;; =============================================================================
;; Tenant-scoped indexes (BOU-1114)
;; =============================================================================

(defn- tenant-service
  "A service on `store` holding :notes, tenant-scoped, and :shared, which
   filters on :tenant-id but says it is global."
  [store]
  (service/create-search-service
   store
   {:notes  {:id :notes :entity-type :note :fields [{:name :title :weight :a}]
             :filters [:tenant-id]}
    :shared {:id :shared :entity-type :note :fields [{:name :title :weight :a}]
             :filters [:tenant-id] :global? true}}))

(defn- refused?
  "Whether `f` throws the :validation-error on :tenant-id."
  [f]
  (try (f) false
       (catch clojure.lang.ExceptionInfo e
         (= {:type :validation-error :field :tenant-id}
            (select-keys (ex-data e) [:type :field])))))

(deftest ^:unit ^:security a-tenant-scoped-index-needs-a-tenant
  (let [svc    (tenant-service (create-memory-store))
        tenant (str (UUID/randomUUID))
        index  #(ports/index-document! svc :notes (UUID/randomUUID) {:title "widget"} %)]
    (testing "indexing names one tenant in :filter-values"
      (doseq [opts [{} {:filter-values {}} {:filter-values {:tenant-id nil}}
                    {:filter-values {:tenant-id " "}} {:filter-values {:tenant-id #{tenant}}}
                    {:filter-values {:tenant-id tenant :tenant_id nil}}]]
        (is (refused? #(index opts)) (pr-str opts)))
      (is (= {:tenant-id tenant} (:filters (index {:filter-values {:tenant-id tenant}})))))

    (testing "search and suggest name a tenant in :filters, or say :all-tenants? true"
      (doseq [opts [{} {:filters {:tenant-id nil}} {:filters {:tenant-id ""}}
                    {:filters {:tenant-id #{tenant nil}}} {:all-tenants? "yes"}]]
        (is (refused? #(ports/search svc :notes "widget" opts)) (pr-str opts))
        (is (refused? #(ports/suggest svc :notes "wid" opts)) (pr-str opts)))
      (is (refused? #(ports/search svc :notes "" {})) "an empty query too")
      (doseq [opts [{:filters {:tenant-id tenant}} {:filters {:tenant_id tenant}}
                    {:filters {:tenant-id [tenant]}} {:all-tenants? true}]]
        (is (= 1 (:total (ports/search svc :notes "widget" opts))) (pr-str opts))
        (is (seq (ports/suggest svc :notes "wid" opts)) (pr-str opts))))

    (testing "an empty set of tenants is a tenant filter matching nothing, not every tenant"
      (is (not (refused? #(ports/search svc :notes "widget" {:filters {:tenant-id #{}}})))))

    (testing "a :global? true index needs none"
      (ports/index-document! svc :shared (UUID/randomUUID) {:title "widget"} {})
      (is (= 1 (:total (ports/search svc :shared "widget" {})))))))

(deftest ^:unit reindex-carries-each-documents-opts
  (let [svc    (tenant-service (create-memory-store))
        tenant (str (UUID/randomUUID))]
    (is (= {:indexed 2}
           (ports/reindex! svc :notes [[(UUID/randomUUID) {:title "widget"} {:filter-values {:tenant-id tenant}}]
                                       [(UUID/randomUUID) {:title "widget"} {:filter-values {:tenant-id tenant}}]])))
    (is (refused? #(ports/reindex! svc :notes [[(UUID/randomUUID) {:title "widget"}]]))
        "a pair has no tenant")))

(deftest ^:unit ^:security a-document-is-removed-within-its-tenant
  (let [svc    (tenant-service (create-memory-store))
        [a b]  [(str (UUID/randomUUID)) (str (UUID/randomUUID))]
        id     (UUID/randomUUID)
        total  #(:total (ports/search svc :notes "widget" {:filters {:tenant-id a}}))]
    (ports/index-document! svc :notes id {:title "widget"} {:filter-values {:tenant-id a}})
    (ports/remove-tenant-document! svc :notes id b)
    (is (= 1 (total)) "another tenant's id leaves it")
    (is (refused? #(ports/remove-tenant-document! svc :notes id nil)))
    (ports/remove-tenant-document! svc :notes id a)
    (is (= 0 (total)))))

(deftest ^:unit a-store-without-tenant-deletion-is-a-configuration-error
  (let [store (reify ports/ISearchStore)
        svc   (service/create-search-service store {})]
    (is (= "WGE-107"
           (try (ports/remove-tenant-document! svc :notes (UUID/randomUUID) "t")
                (catch clojure.lang.ExceptionInfo e (:wagoe/error-code (ex-data e))))))))
