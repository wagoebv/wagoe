(ns wagoe.search.shell.http-test
  (:require [cheshire.core :as json]
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

(defn- served [calls]
  (let [engine (reify ports/ISearchEngine
                 (index-document! [_ index-id entity-id fields _opts]
                   (swap! calls conj [:index index-id entity-id fields]))
                 (remove-document! [_ index-id entity-id]
                   (swap! calls conj [:remove index-id entity-id]))
                 (search [_ index-id query _opts]
                   (swap! calls conj [:search index-id query])
                   {:results [] :total 0 :query query :took-ms 0})
                 (suggest [_ _ _ _] [])
                 (list-indices [_] [])
                 (reindex! [_ _ _] nil))
        routes (reitit-router/compile-routes (sut/search-routes engine) {:swagger-enabled false})]
    (fn [method uri body]
      (routes {:request-method method :uri uri
               :user {:id (random-uuid) :role :admin}
               :headers {"content-type" "application/json" "accept" "application/json"}
               :body (java.io.ByteArrayInputStream. (.getBytes ^String body))}))))

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
