(ns wagoe.search.shell.tenant-scope-test
  "A search of a tenant-scoped index reads the caller's tenant and nothing else,
   whatever filter the caller sends (BOU-568)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [wagoe.platform.shell.adapters.database.h2.core :as h2]
            [wagoe.search.ports :as ports]
            [wagoe.search.shell.http :as sut]
            [wagoe.search.shell.persistence :as persistence]
            [wagoe.search.shell.registry :as registry]
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
  (let [ds (jdbc/get-datasource
            {:jdbcUrl (str "jdbc:h2:mem:search-scope-" (UUID/randomUUID) ";DB_CLOSE_DELAY=-1")})]
    (create-search-documents-table! ds)
    (registry/register-search! {:id :scoped-items :entity-type :item
                                :fields [{:name :title :weight :a}]
                                :filters [:tenant-id]})
    (registry/register-search! {:id :open-items :entity-type :item
                                :fields [{:name :title :weight :a}]})
    (let [engine (service/create-search-service (persistence/create-search-store ds (h2/new-adapter)))]
      (ports/index-document! engine :scoped-items doc-a {:title "widget alpha"}
                             {:filter-values {:tenant-id tenant-a}})
      (ports/index-document! engine :scoped-items doc-b {:title "widget beta"}
                             {:filter-values {:tenant-id tenant-b}})
      (ports/index-document! engine :open-items doc-open {:title "widget open"} {})
      (binding [*engine* engine] (f)))))

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

(deftest ^:unit ^:security without-a-tenant-only-an-admin-reads-a-scoped-index
  (testing "a signed-in user with no tenant context is refused"
    (is (= 403 (:status (call :search :scoped-items {:user {:id (UUID/randomUUID) :role :user}}
                              {:filters {:tenant_id (str tenant-b)}}))))
    (is (= 403 (:status (call :suggest :scoped-items {:user {:id (UUID/randomUUID) :role :user}} {})))))
  (testing "a resolved tenant the caller is not a member of is refused"
    (is (= 403 (:status (call :search :scoped-items {:user   {:id (UUID/randomUUID) :role :user}
                                                     :tenant {:id tenant-b}} {})))))
  (testing "a global admin reads across tenants, and may filter"
    (let [admin {:user {:id (UUID/randomUUID) :role :admin}}]
      (is (= #{(str doc-a) (str doc-b)} (ids (call :search :scoped-items admin {}))))
      (is (= #{(str doc-b)} (ids (call :search :scoped-items admin
                                       {:filters {:tenant_id (str tenant-b)}})))))))

(deftest ^:unit an-index-without-a-tenant-filter-is-open-to-any-signed-in-user
  (is (= #{(str doc-open)}
         (ids (call :search :open-items {:user {:id (UUID/randomUUID) :role :user}} {})))))
