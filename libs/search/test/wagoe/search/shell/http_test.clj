(ns wagoe.search.shell.http-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.platform.shell.http.reitit-router :as reitit-router]
            [wagoe.search.ports :as ports]
            [wagoe.search.shell.http :as sut]))

(defn- answer
  "What the route at `path`/`method` answers `user`, with its middleware and a
   handler that only says it was reached."
  [routes path method user]
  (let [data     (some (fn [[p d]] (when (= p path) d)) routes)
        mws      (concat (:middleware data) (get-in data [method :middleware]))
        handler  (reduce (fn [h mw] (mw h)) (constantly {:status 200}) (reverse mws))]
    (:status (handler {:request-method method :uri path :user user}))))

(deftest ^:unit ^:security search-roles
  ;; Anonymous callers are the platform's to refuse; these are the roles on top
  ;; of that (BOU-568).
  (let [api  (sut/search-routes nil)
        web  (sut/search-web-routes nil)
        user {:id (random-uuid) :role :user}
        adm  {:id (random-uuid) :role :admin}]
    (testing "indexing and removing documents take the admin role"
      (doseq [[path method] [["/search/documents" :post]
                             ["/search/documents/:entity-type/:entity-id" :delete]]]
        (is (= 403 (answer api path method user)) path)
        (is (= 200 (answer api path method adm)) path)))

    (testing "searching takes only a signed-in user"
      (is (= 200 (answer api "/search/:index-id" :post user)))
      (is (= 200 (answer api "/search/:index-id/suggest" :post user))))

    (testing "the admin pages take the admin role"
      (doseq [[path method] [["/search" :get] ["/search/:index-id" :get]
                             ["/search/:index-id/search" :post]]]
        (is (= 403 (answer web path method user)) path)
        (is (= 200 (answer web path method adm)) path)))))

;; =============================================================================
;; As served: the platform's router, coercion and error handling (BOU-586)
;; =============================================================================

(defn- engine-with
  "An engine holding `definitions`, recording what it is asked into `calls`."
  [calls definitions]
  (reify ports/ISearchEngine
    (index-document! [_ index-id entity-id fields _opts]
      (swap! calls conj [:index index-id entity-id fields]))
    (remove-document! [_ index-id entity-id]
      (swap! calls conj [:remove index-id entity-id]))
    (search [_ index-id query _opts]
      (swap! calls conj [:search index-id query])
      {:results [] :total 0 :query query :took-ms 0})
    (suggest [_ index-id _ _]
      (swap! calls conj [:suggest index-id])
      [])
    (index-definition [_ index-id] (get definitions index-id))
    (list-indices [_]
      (mapv (fn [[id d]] {:id id :entity-type (:entity-type d) :language :english
                          :fields [] :doc-count 0})
            definitions))
    (reindex! [_ _ _] nil)))

(defn- served
  ([calls] (served calls {:products {:id :products :entity-type :product :api? true}}))
  ([calls definitions]
   (let [routes (reitit-router/compile-routes (sut/search-routes (engine-with calls definitions))
                                              {:swagger-enabled false})]
     (fn [method uri body & [user]]
       (routes {:request-method method :uri uri
                :user (or user {:id (random-uuid) :role :admin})
                :headers {"content-type" "application/json" "accept" "application/json"}
                :body (java.io.ByteArrayInputStream. (.getBytes ^String body))})))))

(defn- error-type [response]
  (get-in (json/parse-string (slurp (:body response)) true) [:error :type]))

(deftest ^:unit the-api-reads-the-body-it-is-sent
  ;; No route declared :parameters, so [:parameters :body] was nil whatever the
  ;; caller sent: a search ran for "", and indexing threw on a nil entity id
  ;; and answered 500 (BOU-586).
  (let [calls (atom [])
        call  (served calls)
        id    (str (random-uuid))]
    (testing "an empty body is a 400, not a 500"
      (let [r (call :post "/search/documents" "{}")]
        (is (= 400 (:status r)))
        (is (= "validation-error" (error-type r))))
      (is (= 400 (:status (call :post "/search/documents"
                                "{\"indexId\":\"products\",\"entityId\":\"nope\"}")))))
    (testing "a document is indexed from the body"
      (is (= 200 (:status (call :post "/search/documents"
                                (json/generate-string {:indexId "products" :entityId id
                                                       :fields {:name "Widget"}})))))
      (is (= [:index :products (parse-uuid id) {:name "Widget"}] (last @calls))))
    (testing "a search runs the query it was sent"
      (is (= 200 (:status (call :post "/search/products" "{\"query\":\"widget\"}"))))
      (is (= [:search :products "widget"] (last @calls))))
    (testing "a removal names a UUID or is a 400"
      (is (= 400 (:status (call :delete "/search/documents/product/nope" "")))))))

;; =============================================================================
;; Which indexes the API serves
;; =============================================================================

(def ^:private definitions
  {:private  {:id :private :entity-type :note}
   ;; An :api map without an :authorize fn fails closed.
   :misspelt {:id :misspelt :entity-type :note :api {:authorise (constantly true)}}
   :products {:id :products :entity-type :product :api? true}
   :orders   {:id :orders :entity-type :order
              :api {:authorize (fn [request _] (= :admin (get-in request [:user :role])))}}})

(def ^:private member {:id (random-uuid) :role :user})

(deftest ^:unit ^:security only-an-index-that-opts-in-is-served
  (let [calls (atom [])
        call  (served calls definitions)
        body  (fn [r] (slurp (:body r)))]
    (testing "an index without :api? answers 404 on search and suggest, and is never run"
      (doseq [uri ["/search/private" "/search/private/suggest" "/search/misspelt"]]
        (let [r (call :post uri "{\"query\":\"secret\"}" member)]
          (is (= 404 (:status r)) uri)
          (is (= "not-found" (error-type r)) uri)))
      (is (empty? @calls)))

    (testing "and says exactly what an unknown index says"
      (let [error (fn [id] (-> (body (call :post (str "/search/" id) "{}" member))
                               (json/parse-string true)
                               :error
                               (dissoc :correlation-id)
                               pr-str
                               (str/replace id "ID")))]
        (is (= (error "nope") (error "private")))))

    (testing ":api? true is served to any signed-in user"
      (is (= 200 (:status (call :post "/search/products" "{\"query\":\"widget\"}" member))))
      (is (= 200 (:status (call :post "/search/products/suggest" "{\"query\":\"wid\"}" member)))))

    (testing "an :api :authorize hook decides per request, refusing with 404"
      (is (= 404 (:status (call :post "/search/orders" "{\"query\":\"x\"}" member))))
      (is (= 404 (:status (call :post "/search/orders/suggest" "{\"query\":\"x\"}" member))))
      (is (= 200 (:status (call :post "/search/orders" "{\"query\":\"x\"}"
                                {:id (random-uuid) :role :admin})))))))

(deftest ^:unit ^:security the-admin-pages-serve-the-same-indexes
  (let [engine (engine-with (atom []) definitions)
        admin  {:user {:id (random-uuid) :role :admin}}
        page   (fn [handler id]
                 (try (:status (handler engine (assoc admin :path-params {:index-id id})))
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))]
    (is (= #{"products" "orders"}
           (set (map second (re-seq #"/web/admin/search/([a-z]+)\""
                                    (:body (sut/handle-list-indices-web engine admin))))))
        "the list leaves out an index that is not served")
    (is (= 404 (page sut/handle-get-index-web "private")))
    (is (= 200 (page sut/handle-get-index-web "products")))
    (is (= :not-found (page sut/handle-search-fragment "private")))))
