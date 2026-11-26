(ns wagoe.search.shell.tenant-scope-test
  "A search of a tenant-scoped index reads the caller's tenant and nothing else,
   whatever filter the caller sends (BOU-568)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [support.test-db :as test-db]
            [wagoe.platform.shell.http.reitit-router :as reitit-router]
            [wagoe.search.ports :as ports]
            [wagoe.search.shell.http :as sut]
            [wagoe.search.shell.persistence :as persistence]
            [wagoe.search.shell.service :as service]
            [wagoe.search.test.support :refer [create-search-documents-table!]])
  (:import [java.util UUID]))

(def ^:private tenant-a #uuid "a0000000-0000-0000-0000-00000000000a")
(def ^:private tenant-b #uuid "b0000000-0000-0000-0000-00000000000b")
(def ^:private doc-a    #uuid "a0000000-0000-0000-0000-0000000000d1")
(def ^:private doc-b    #uuid "b0000000-0000-0000-0000-0000000000d2")
(def ^:private doc-open #uuid "c0000000-0000-0000-0000-0000000000d3")

(def ^:dynamic *engine* nil)

(defn- with-engine [f]
  (test-db/with-db "search_scope"
    (fn [{:keys [datasource adapter]}]
      (create-search-documents-table! datasource)
      (let [engine (service/create-search-service
                    (persistence/create-search-store datasource adapter)
                    {:scoped-items {:id :scoped-items :entity-type :item :api? true
                                    :fields [{:name :title :weight :a}]
                                    :filters [:tenant-id]}
                     :open-items   {:id :open-items :entity-type :item :api? true
                                    :fields [{:name :title :weight :a}]}
                     :global-items {:id :global-items :entity-type :item :api? true :global? true
                                    :fields [{:name :title :weight :a}]}})]
        (ports/index-document! engine :scoped-items doc-a {:title "widget alpha"}
                               {:filter-values {:tenant-id tenant-a}})
        (ports/index-document! engine :scoped-items doc-b {:title "widget beta"}
                               {:filter-values {:tenant-id tenant-b}})
        (ports/index-document! engine :open-items doc-open {:title "widget open"} {})
        (ports/index-document! engine :global-items doc-open {:title "widget open"} {})
        (binding [*engine* engine] (f))))))

(use-fixtures :each with-engine)

(defn- call
  "POST to the search or suggest route, through its middleware."
  [route index-id request body]
  (let [path    (case route :search "/search/:index-id" :suggest "/search/:index-id/suggest")
        data    (some (fn [[p d]] (when (= p path) d)) (sut/search-routes *engine*))
        mws     (concat (:middleware data) (get-in data [:post :middleware]))
        handler (reduce (fn [h mw] (mw h)) (get-in data [:post :handler]) (reverse mws))]
    (handler (merge {:request-method :post
                     :uri            (str "/api/v1/search/" (name index-id))
                     :path-params    {:index-id (name index-id)}
                     :parameters     {:body (merge {:query "widget"} body)}}
                    request))))

(defn- ids [response]
  (set (map :entityId (get-in response [:body (if (contains? (:body response) :suggestions)
                                                :suggestions :results)]))))

(def ^:private member-of-a
  {:user {:id (UUID/randomUUID) :role :user}
   :tenant {:id tenant-a}
   :tenant-membership {:tenant-id tenant-a :role :member :status :active}})

(deftest ^:unit ^:security a-member-reads-only-their-own-tenant
  (testing "with no filter"
    (is (= #{(str doc-a)} (ids (call :search :scoped-items member-of-a {})))))
  (testing "with another tenant's id as the filter, in either spelling"
    (is (= #{(str doc-a)} (ids (call :search :scoped-items member-of-a
                                     {:filters {:tenant_id (str tenant-b)}}))))
    (is (= #{(str doc-a)} (ids (call :search :scoped-items member-of-a
                                     {:filters {:tenant-id (str tenant-b)}})))))
  (testing "suggestions are scoped too"
    (is (= #{(str doc-a)} (ids (call :suggest :scoped-items member-of-a {}))))))

(deftest ^:unit ^:security indexing-cannot-move-another-tenants-document
  ;; One upsert by entity id used to overwrite tenant B's row and move it to A (BOU-1121).
  (let [index-as-a #(ports/index-document! *engine* :scoped-items doc-b {:title "widget moved"}
                                           {:filter-values {:tenant-id tenant-a}})]
    (is (= :conflict (try (index-as-a) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
    (is (= [doc-b] (map :entity-id (:results (ports/search *engine* :scoped-items "beta"
                                                           {:filters {:tenant-id tenant-b}})))))
    (is (empty? (:results (ports/search *engine* :scoped-items "moved" {:all-tenants? true}))))))

(deftest ^:unit ^:security a-body-cannot-ask-for-every-tenant
  ;; :all-tenants? is the platform admin's, set by the handler (BOU-1114).
  (let [body {:all-tenants? true :all_tenants true :allTenants true}]
    (is (= #{(str doc-a)} (ids (call :search :scoped-items member-of-a body))))
    (is (= #{(str doc-a)} (ids (call :suggest :scoped-items member-of-a body))))))

(deftest ^:unit ^:security a-set-filter-cannot-widen-the-tenant
  ;; A filter value may be a set (BOU-1055); the tenant stays the resolved one.
  (testing "a set of tenant ids, in either spelling, reads only the caller's"
    (is (= #{(str doc-a)} (ids (call :search :scoped-items member-of-a
                                     {:filters {:tenant_id [(str tenant-a) (str tenant-b)]}}))))
    (is (= #{(str doc-a)} (ids (call :search :scoped-items member-of-a
                                     {:filters {:tenant-id #{(str tenant-a) (str tenant-b)}}})))))
  (testing "a set over another key matches within the caller's tenant only"
    (let [conv-a (UUID/randomUUID) conv-b (UUID/randomUUID)
          in-a   (UUID/randomUUID) in-b   (UUID/randomUUID)]
      (ports/index-document! *engine* :scoped-items in-a {:title "widget in a"}
                             {:filter-values {:tenant-id tenant-a :conversation-id conv-a}})
      (ports/index-document! *engine* :scoped-items in-b {:title "widget in b"}
                             {:filter-values {:tenant-id tenant-b :conversation-id conv-b}})
      (let [response (call :search :scoped-items member-of-a
                           {:filters {:conversation_id [(str conv-a) (str conv-b)]}})]
        (is (= #{(str in-a)} (ids response)))
        (is (= 1 (get-in response [:body :total])))))))

(deftest ^:unit ^:security without-a-tenant-only-an-admin-reads-a-scoped-index
  (testing "a signed-in user with no tenant context is refused"
    (is (= 403 (:status (call :search :scoped-items {:user {:id (UUID/randomUUID) :role :user}}
                              {:filters {:tenant_id (str tenant-b)}}))))
    (is (= 403 (:status (call :suggest :scoped-items {:user {:id (UUID/randomUUID) :role :user}} {})))))
  (testing "a resolved tenant the caller is not a member of is refused"
    (is (= 403 (:status (call :search :scoped-items {:user   {:id (UUID/randomUUID) :role :user}
                                                     :tenant {:id tenant-b}} {})))))
  (testing "a tenant's admin with no tenant resolved is refused, not given every tenant"
    (is (= 403 (:status (call :search :scoped-items {:user {:id (UUID/randomUUID) :role :admin
                                                            :tenant-id tenant-a}}
                              {:filters {:tenant_id (str tenant-b)}})))))
  (testing "a global admin reads across tenants, and may filter"
    (let [admin {:user {:id (UUID/randomUUID) :role :admin}}]
      (is (= #{(str doc-a) (str doc-b)} (ids (call :search :scoped-items admin {}))))
      (is (= #{(str doc-b)} (ids (call :search :scoped-items admin
                                       {:filters {:tenant_id (str tenant-b)}}))))
      (is (= #{(str doc-a) (str doc-b)} (ids (call :suggest :scoped-items admin {})))))))

(deftest ^:unit ^:security an-index-without-a-tenant-filter-is-refused-to-a-caller-in-a-tenant
  (testing "a member of a resolved tenant, or a user of one, is refused"
    (is (= 403 (:status (call :search :open-items member-of-a {}))))
    (is (= 403 (:status (call :suggest :open-items {:user {:id (UUID/randomUUID) :role :user
                                                           :tenant-id tenant-a}} {})))))
  (testing "unless the index says it is global"
    (is (= #{(str doc-open)} (ids (call :search :global-items member-of-a {})))))
  (testing "a user of no tenant, and a platform admin, read it"
    (is (= #{(str doc-open)} (ids (call :search :open-items {:user {:id (UUID/randomUUID) :role :user}} {}))))
    (is (= #{(str doc-open)} (ids (call :search :open-items {:user {:id (UUID/randomUUID) :role :admin}
                                                             :tenant {:id tenant-a}} {}))))))

(deftest ^:unit ^:security the-admin-search-page-is-scoped-as-the-api-is
  (let [search (fn [index-id request]
                 (sut/handle-search-fragment *engine*
                                             (merge {:path-params {:index-id (name index-id)}
                                                     :form-params {"query" "widget"}}
                                                    request)))
        admin-of-a {:user {:id (UUID/randomUUID) :role :admin :tenant-id tenant-a}
                    :tenant {:id tenant-a}}]
    (testing "a tenant's admin sees their tenant's documents only"
      (let [body (:body (search :scoped-items admin-of-a))]
        (is (str/includes? body (str doc-a)))
        (is (not (str/includes? body (str doc-b))))))
    (testing "and is refused an index that holds every tenant's data"
      (is (= 403 (:status (search :open-items admin-of-a)))))))

(defn- write
  "POST /search/documents of tenant-b's doc-b for `user`, through its middleware,
   as `tenant`'s."
  ([user] (write user tenant-a))
  ([user tenant]
   (let [data    (some (fn [[p d]] (when (= p "/search/documents") d)) (sut/search-routes *engine*))
         handler (reduce (fn [h mw] (mw h)) (get-in data [:post :handler]) (reverse (get-in data [:post :middleware])))]
     (handler {:request-method :post :uri "/api/v1/search/documents" :user user
               :parameters {:body {:indexId "scoped-items" :entityId doc-b :fields {:title "taken"}
                                   :filterValues {:tenant-id (str tenant)}}}}))))

(deftest ^:unit ^:security only-a-platform-admin-writes-documents
  (testing "a tenant's admin could otherwise overwrite another tenant's document by its id"
    (is (= 403 (:status (write {:id (UUID/randomUUID) :role :admin :tenant-id tenant-a}))))
    (is (= #{(str doc-b)} (ids (call :search :scoped-items {:user {:id (UUID/randomUUID) :role :user}
                                                            :tenant {:id tenant-b}
                                                            :tenant-membership {:tenant-id tenant-b :role :member :status :active}}
                                     {})))))
  (testing "a platform admin may, within the document's tenant (BOU-1121)"
    (is (= 200 (:status (write {:id (UUID/randomUUID) :role :admin} tenant-b))))
    (is (= :conflict (try (write {:id (UUID/randomUUID) :role :admin} tenant-a) nil
                          (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
        "and gets the :conflict HTTP answers with a 409 in another")))

(deftest ^:unit ^:security in-process-a-scoped-index-is-read-within-a-tenant
  ;; BOU-1114: over HTTP the tenant is forced; in process a forgotten filter read every tenant.
  (testing "a search or suggest naming no tenant is refused"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"tenant-scoped"
                          (ports/search *engine* :scoped-items "widget" {})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"tenant-scoped"
                          (ports/suggest *engine* :scoped-items "widget" {}))))
  (testing "one naming a tenant reads that tenant, and :all-tenants? true every one"
    (is (= [doc-a] (map :entity-id (:results (ports/search *engine* :scoped-items "widget"
                                                           {:filters {:tenant-id tenant-a}})))))
    (is (= #{doc-a doc-b} (set (map :entity-id (:results (ports/search *engine* :scoped-items "widget"
                                                                       {:all-tenants? true})))))))
  (testing "a document is removed only within its tenant"
    (ports/remove-tenant-document! *engine* :scoped-items doc-a tenant-b)
    (is (= 1 (:total (ports/search *engine* :scoped-items "widget" {:filters {:tenant-id tenant-a}}))))
    (ports/remove-tenant-document! *engine* :scoped-items doc-a tenant-a)
    (is (= 0 (:total (ports/search *engine* :scoped-items "widget" {:filters {:tenant-id tenant-a}}))))))

(deftest ^:unit indexing-a-scoped-document-without-a-tenant-is-a-400
  (let [routes (reitit-router/compile-routes (sut/search-routes *engine*) {:swagger-enabled false})
        body   (json/generate-string {:indexId "scoped-items" :entityId (str (UUID/randomUUID))
                                      :fields {:title "widget"}})]
    (is (= 400 (:status (routes {:request-method :post :uri "/search/documents"
                                 :user {:id (UUID/randomUUID) :role :admin}
                                 :headers {"content-type" "application/json" "accept" "application/json"}
                                 :body (java.io.ByteArrayInputStream. (.getBytes ^String body))}))))))
