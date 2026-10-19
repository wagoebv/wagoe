(ns wagoe.audience.shell.http-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.audience.core.filter :as filter]
            [wagoe.audience.ports :as ports]
            [wagoe.audience.shell.http :as sut]
            [wagoe.audience.shell.service :as service]
            [ring.util.codec :as codec]
            [wagoe.platform.shell.http.reitit-router :as reitit-router]))

(defn- store
  "An in-memory IAudienceRepository, or one whose every call throws `ex`."
  ([] (store nil))
  ([ex]
   (let [db (atom {})]
     (reify ports/IAudienceRepository
       (save-audience [_ d] (when ex (throw ex)) (swap! db assoc (:id d) d) d)
       (create-audience [this d]
         (when (get @db (:id d)) (throw (ex-info "taken" {:type :audience-exists})))
         (ports/save-audience this d))
       (update-audience [this d]
         (when-not (or ex (get @db (:id d))) (throw (ex-info "missing" {:type :audience-not-found})))
         (ports/save-audience this d))
       (find-audience [_ id] (when ex (throw ex)) (get @db id))
       (list-audiences [_] (when ex (throw ex)) (vec (vals @db)))
       (list-audiences [this _] (ports/list-audiences this))
       (delete-audience [_ id] (when ex (throw ex)) (swap! db dissoc id) nil)))))

(defn- resolver
  "An IAudienceResolver answering `result` for every id, or throwing `ex`."
  [result & [ex]]
  (let [calls (atom [])]
    (with-meta
      (reify ports/IAudienceResolver
        (resolve-audience [this id] (ports/resolve-audience this id {}))
        (resolve-audience [_ id opts]
          (swap! calls conj [id opts])
          (if ex (throw ex) result))
        (member? [_ _ _] false))
      {:calls calls})))

(def ^:private u1 #uuid "00000000-0000-0000-0000-000000000001")
(def ^:private u2 #uuid "00000000-0000-0000-0000-000000000002")

(deftest ^:unit web-pages-test
  (testing "the list page shows each stored segment"
    (let [s (store)]
      (ports/save-audience s {:id :vip :label "VIP" :filters []})
      (let [res (sut/list-audiences-handler nil s {})]
        (is (= 200 (:status res)))
        (is (= "text/html; charset=utf-8" (get-in res [:headers "Content-Type"])))
        (is (str/includes? (:body res) "VIP")))))

  (testing "an empty list says so"
    (is (str/includes? (:body (sut/list-audiences-handler nil (store) {})) "No audience segments")))

  (testing "a failing store is a 500 page, not an exception"
    (is (= 500 (:status (sut/list-audiences-handler nil (store (RuntimeException. "db down")) {})))))

  (testing "the edit page loads the segment, 404s an unknown one, 400s a bad id"
    (let [s (store)]
      (ports/save-audience s {:id :vip :label "VIP" :filters []})
      (is (str/includes? (:body (sut/builder-edit-handler nil s {:path-params {:id "vip"}})) "Edit Audience"))
      (is (= 404 (:status (sut/builder-edit-handler nil s {:path-params {:id "nope"}}))))
      (is (= 400 (:status (sut/builder-edit-handler nil s {:path-params {:id "Not Valid!"}}))))))

  (testing "the new page is the empty builder"
    (is (str/includes? (:body (sut/builder-page-handler nil nil {})) "New Audience"))))

(deftest ^:unit crud-api-test
  (let [s (store)]
    (testing "create stores the definition"
      (let [res (sut/create-audience-handler nil s {:parameters {:body {:id :vip :label "VIP"
                                                                        :filters [{:type :role :field :role
                                                                                   :op :eq :value "gold"}]}}})]
        ;; The id came back as ":vip", which no /audiences/:id path accepts.
        (is (= {:status 201 :body {:id "vip" :label "VIP"}} res))
        (is (= :role (get-in (ports/find-audience s :vip) [:filters 0 :type])))))

    (testing "update replaces it under the path id"
      (let [res (sut/update-audience-handler nil s {:path-params {:id "vip"}
                                                    :parameters  {:body {:label "Gold" :filters []}}})]
        (is (= {:status 200 :body {:id "vip" :label "Gold"}} res))
        (is (= "Gold" (:label (ports/find-audience s :vip))))))

    (testing "delete removes it"
      (is (= 204 (:status (sut/delete-audience-handler nil s {:path-params {:id "vip"}}))))
      (is (nil? (ports/find-audience s :vip))))

    (testing "a bad id is refused before the store is touched"
      (doseq [h [sut/update-audience-handler sut/delete-audience-handler]]
        (is (= 400 (:status (h nil (store (RuntimeException. "touched")) {:path-params {:id "../x"}}))))))))

(deftest ^:unit resolve-api-test
  (let [result {:user-ids #{u1} :count 1 :cached? false :evaluated-at #inst "2026-10-19T10:00:00Z"}]
    (testing "evaluate forces a refresh and reports the count"
      (let [r   (resolver result)
            res (sut/evaluate-audience-handler r nil {:path-params {:id "vip"}})]
        (is (= {:count 1 :cached false} (dissoc (:body res) :cachedAt)))
        (is (seq (get-in res [:body :cachedAt])))
        (is (= [[:vip {:force-refresh? true}]] @(:calls (meta r))))))

    (testing "members lists the ids as strings, from the cache when it can"
      (let [r (resolver result)]
        (is (= {:count 1 :userIds [(str u1)]}
               (:body (sut/list-members-handler r nil {:path-params {:id "vip"}}))))
        (is (= [[:vip {}]] @(:calls (meta r))))))

    (testing "preview resolves the named audience, or refuses a malformed name"
      (let [r (resolver result)]
        (is (= {:count 1 :sample [u1]}
               (update (:body (sut/preview-audience-handler r nil {:parameters {:body {:audienceId "vip"}}}))
                       :sample vec)))
        (is (= 400 (:status (sut/preview-audience-handler r nil {:parameters {:body {:audienceId "BAD ID"}}}))))))))

(deftest ^:unit resolver-errors-map-to-statuses
  (let [respond (fn [ex] (sut/evaluate-audience-handler (resolver nil ex) nil {:path-params {:id "vip"}}))]
    (doseq [[ex-type status] [[:audience-not-found 404]
                              [:circular-reference 422]
                              [:unknown-segment-ref 422]
                              [:composition-error 422]
                              [:unsupported-filter-op 422]
                              [:unknown-filter-type 422]
                              [:something-else 500]]]
      (is (= status (:status (respond (ex-info "x" {:type ex-type})))) (name ex-type)))
    (testing "an ordinary exception is a 500 without its message"
      (let [res (respond (RuntimeException. "secret detail"))]
        (is (= 500 (:status res)))
        (is (not (str/includes? (pr-str res) "secret detail")))))))

(deftest ^:unit routes-carry-the-guard
  (let [mw     (fn [h] h)
        routes (concat (sut/audience-web-routes nil nil [mw]) (sut/audience-api-routes nil nil [mw]))]
    (is (= 8 (count routes)))
    (is (every? (fn [[_ data]] (= [mw] (:middleware data))) routes)
        "every route is guarded, so a new one cannot ship open")
    (is (every? (fn [[_ data]] (nil? (:middleware data))) (sut/audience-api-routes nil nil))
        "no middleware given, none attached")))

;; =============================================================================
;; As served: the platform's router, body decoding and coercion in front of
;; the real handlers and service
;; =============================================================================

(def ^:private users
  "Two users; a SQL filter narrows them to the first."
  (reify ports/IUserDataSource
    (query-users-sql [_ clause] (if clause [u1] [u1 u2]))
    (load-users [_ ids] (mapv (fn [id] {:id id}) ids))))

(defn- request [method uri content-type body]
  {:request-method method :uri uri
   :headers {"content-type" content-type "accept" "application/json"}
   :body (java.io.ByteArrayInputStream. (.getBytes ^String body))})

(defn- served [s & [{:keys [filter-fields cache]}]]
  (let [resolver (service/create-audience-service {:repository s :user-data-source users
                                                   :filter-fields filter-fields :cache cache})
        handler  (reitit-router/compile-routes (sut/audience-api-routes resolver s [] {:filter-fields filter-fields})
                                               {:swagger-enabled false})]
    (fn [method uri body]
      (-> (handler (request method uri "application/json" (json/generate-string body)))
          (update :body #(some-> % slurp (json/parse-string true)))))))

(deftest ^:unit ^:security create-update-and-preview-read-the-body
  ;; No route declared :parameters, so [:parameters :body] was empty and every
  ;; create and update answered 422 whatever was sent.
  (let [s    (store)
        call (served s)]
    (testing "create stores the definition sent, its values coerced"
      (let [r (call :post "/audiences" {:id "gold" :label "Gold"
                                        :filters [{:type "role" :field "role" :op "eq" :value "gold"}]})]
        (is (= 201 (:status r)))
        (is (= {:id "gold" :label "Gold"} (:body r))))
      (is (= {:id :gold :label "Gold"
              :filters [{:type :role :field :role :op :eq :value "gold"}]}
             (ports/find-audience s :gold))))

    (testing "an id no /audiences/:id path accepts is refused"
      (doseq [id ["Gold Plan" "" "a/b" "preview"]]
        (is (= 400 (:status (call :post "/audiences" {:id id :label "X" :filters []}))) id))
      (is (= [:gold] (mapv :id (ports/list-audiences s)))))

    (testing "preview resolves the audience named in the body"
      (let [r (call :post "/audiences/preview" {:audienceId "gold"})]
        (is (= 200 (:status r)))
        (is (= {:count 1 :sample [(str u1)]} (:body r)))))

    (testing "update replaces the definition under the path id"
      (let [r (call :put "/audiences/gold" {:label "Everyone" :filters []})]
        (is (= 200 (:status r)))
        (is (= {:id "gold" :label "Everyone"} (:body r))))
      (is (= {:id :gold :label "Everyone" :filters []} (ports/find-audience s :gold)))
      (is (= 2 (get-in (call :post "/audiences/preview" {:audienceId "gold"}) [:body :count]))))

    (testing "a body missing required keys is a validation error naming them, not the schema"
      (doseq [[method uri] [[:post "/audiences"] [:put "/audiences/gold"]]]
        (let [r (call method uri {})]
          (is (= 400 (:status r)) uri)
          (is (= "validation-error" (get-in r [:body :error :type])) uri)
          (is (contains? (get-in r [:body :error :details]) :label) uri)
          (is (not (str/includes? (pr-str (:body r)) ":map")) "the schema leaked"))))))

(deftest ^:unit filters-beside-compose-are-refused
  ;; The service evaluates :compose and ignored :filters next to it, so the
  ;; audience was larger than its definition said.
  (let [s    (store)
        call (served s)
        both {:label   "Both"
              :filters [{:type "role" :field "role" :op "eq" :value "gold"}]
              :compose {:or [{:ref "a"} {:ref "b"}]}}]
    (doseq [[method uri body] [[:post "/audiences" (assoc both :id "both")]
                               [:put "/audiences/both" both]]]
      (let [r (call method uri body)]
        (is (= 422 (:status r)) uri)
        (is (= "validation-error" (get-in r [:body :error :type])) uri)
        (is (str/includes? (get-in r [:body :error :message]) "either") uri)))
    (is (empty? (ports/list-audiences s)))
    (testing "compose with empty filters is accepted"
      (is (= 201 (:status (call :post "/audiences" (assoc both :id "both" :filters [])))))
      (is (= {:or [{:ref :a} {:ref :b}]} (:compose (ports/find-audience s :both)))))))

(deftest ^:unit ^:security filters-name-only-allowed-fields
  ;; A filter's field is a SQL column and preview answers a count, so naming
  ;; password_hash made the API an oracle over credentials.
  (let [s      (store)
        call   (served s)
        secret {:type "demographics" :field "password-hash" :op "contains" :value "$2a$"}]
    (testing "create refuses a credential column, in any spelling"
      (doseq [field ["password-hash" "PASSWORD_HASH" "mfa-secret"]]
        (let [r (call :post "/audiences" {:id "x" :label "X" :filters [(assoc secret :field field)]})]
          (is (= 422 (:status r)) field)
          (is (= "validation-error" (get-in r [:body :error :type])) field)))
      (is (empty? (ports/list-audiences s))))
    (testing "so does update"
      (is (= 422 (:status (call :put "/audiences/x" {:label "X" :filters [secret]}))))
      (is (empty? (ports/list-audiences s))))
    (testing "and a column nobody allowed"
      (is (= 422 (:status (call :post "/audiences" {:id "x" :label "X"
                                                    :filters [(assoc secret :field "plan")]})))))
    (testing "preview refuses one stored before the allowlist"
      (ports/save-audience s {:id :old :label "Old"
                              :filters [{:type :demographics :field :password-hash :op :contains :value "$2a$"}]})
      (let [r (call :post "/audiences/preview" {:audienceId "old"})]
        (is (= 422 (:status r)))
        (is (= "validation-error" (get-in r [:body :error :type])))
        (is (nil? (get-in r [:body :count])))))
    (testing "a field the app configures is allowed"
      (let [s    (store)
            call (served s {:filter-fields (filter/allowed-filter-fields #{:plan})})]
        (is (= 201 (:status (call :post "/audiences" {:id "paid" :label "Paid"
                                                      :filters [(assoc secret :field "plan" :value "paid")]}))))
        (is (= 1 (get-in (call :post "/audiences/preview" {:audienceId "paid"}) [:body :count])))))))

(defn- atom-cache
  "An IAudienceCache that keeps every result, whatever its TTL."
  []
  (let [db (atom {})]
    (reify ports/IAudienceCache
      (get-cached [_ id] (some-> (get @db id) (assoc :cached? true)))
      (put-cached [_ id result _] (swap! db assoc id result) result)
      (invalidate [_ id] (swap! db dissoc id) nil)
      (invalidate-all [_] (reset! db {}) nil))))

(deftest ^:unit create-and-update-do-not-stand-in-for-each-other
  ;; Both saved unconditionally: create overwrote an existing audience and still
  ;; answered 201, update of an unknown id created it.
  (let [s    (store)
        call (served s)
        gold {:id "gold" :label "Gold" :filters []}]
    (is (= 201 (:status (call :post "/audiences" gold))))
    (testing "create of a taken id is a conflict and leaves it alone"
      (let [r (call :post "/audiences" (assoc gold :label "Other"))]
        (is (= 409 (:status r)))
        (is (= "conflict" (get-in r [:body :error :type]))))
      (is (= "Gold" (:label (ports/find-audience s :gold)))))
    (testing "update of an unknown id is not found and creates nothing"
      (let [r (call :put "/audiences/silver" {:label "Silver" :filters []})]
        (is (= 404 (:status r)))
        (is (= "not-found" (get-in r [:body :error :type]))))
      (is (nil? (ports/find-audience s :silver))))))

(deftest ^:unit json-keys-are-camel-case
  ;; Create and update read `cache-config` while preview read `audienceId`;
  ;; coercion dropped `cacheConfig` without a word.
  (let [s    (store)
        call (served s)]
    (is (= 201 (:status (call :post "/audiences" {:id "gold" :label "Gold" :filters []
                                                  :cacheConfig {:ttlMinutes 30 :refreshSchedule "hourly"}}))))
    (is (= {:ttl-minutes 30 :refresh-schedule "hourly"} (:cache-config (ports/find-audience s :gold))))
    (is (= 200 (:status (call :put "/audiences/gold" {:label "Gold" :filters [] :cacheConfig {:ttlMinutes 5}}))))
    (is (= {:ttl-minutes 5} (:cache-config (ports/find-audience s :gold))))))

(deftest ^:unit preview-needs-an-audience-id
  ;; Without one it resolved an audience named :preview.
  (let [call (served (store))]
    (doseq [body [{} {:audienceId ""}]]
      (let [r (call :post "/audiences/preview" body)]
        (is (= 400 (:status r)) (pr-str body))
        (is (str/includes? (pr-str (:body r)) "audienceId") (pr-str body))))))

(deftest ^:unit ^:security cached-results-honour-the-definition-checks
  ;; The checks run before the cache, so a result cached for a definition that
  ;; is now refused is not served until its TTL runs out.
  (let [s    (store)
        call (served s {:cache (atom-cache)})]
    (ports/save-audience s {:id :gold :label "Gold" :filters [] :cache-config {:ttl-minutes 60}})
    (is (= 2 (get-in (call :get "/audiences/gold/members" nil) [:body :count])))
    (ports/save-audience s {:id :gold :label "Gold" :cache-config {:ttl-minutes 60}
                            :filters [{:type :role :field :role :op :eq :value "x"}]
                            :compose {:or [{:ref :a}]}})
    (let [r (call :get "/audiences/gold/members" nil)]
      (is (= 422 (:status r)))
      (is (nil? (get-in r [:body :count]))))))

(defn- served-web [s]
  (let [handler (reitit-router/compile-routes (sut/audience-web-routes nil s [])
                                              {:swagger-enabled false})]
    (fn [method uri form]
      (let [r (handler (assoc (request method uri "application/x-www-form-urlencoded"
                                       (codec/form-encode form))
                              :anti-forgery-token "tok"))]
        (update r :body #(cond-> % (instance? java.io.InputStream %) slurp))))))

(deftest ^:unit the-builder-form-saves
  ;; It posted form fields to the unversioned /api/audiences, which nothing
  ;; served, so the builder could not create an audience.
  (let [s    (store)
        post (served-web s)]
    (testing "the new form posts to the web create route, with its CSRF token"
      (let [page (:body (post :get "/audiences/builder" {}))]
        (is (str/includes? page "action=\"/web/audiences\""))
        (is (str/includes? page "value=\"tok\""))))
    (testing "create stores the definition and goes back to the list"
      (let [r (post :post "/audiences" {"id" "gold" "label" "Gold" "description" ""
                                        "filters-data" (json/generate-string [{:type "role" :field "role"
                                                                               :op "eq" :value "gold"}])
                                        "compose-data" ""})]
        (is (= 303 (:status r)))
        (is (= "/web/audiences" (get-in r [:headers "Location"]))))
      (is (= {:id :gold :label "Gold" :filters [{:type :role :field :role :op :eq :value "gold"}]}
             (ports/find-audience s :gold))))
    (testing "a taken id shows the form again with the conflict"
      (let [r (post :post "/audiences" {"id" "gold" "label" "Again" "filters-data" "[]"})]
        (is (= 409 (:status r)))
        (is (str/includes? (:body r) "already exists"))
        (is (str/includes? (:body r) "value=\"Again\""))))
    (testing "a refused definition shows the form again"
      (is (= 422 (:status (post :post "/audiences" {"id" "pw" "label" "PW"
                                                    "filters-data" (json/generate-string
                                                                    [{:type "demographics" :field "password-hash"
                                                                      :op "eq" :value "x"}])}))))
      (is (= 400 (:status (post :post "/audiences" {"id" "bad" "label" "Bad" "filters-data" "{not json"}))))
      (is (nil? (ports/find-audience s :pw))))
    (testing "the edit form posts to the update route, keeping what it has no field for"
      (ports/save-audience s (assoc (ports/find-audience s :gold) :cache-config {:ttl-minutes 5}))
      (let [page (:body (post :get "/audiences/builder/gold" {}))]
        (is (str/includes? page "action=\"/web/audiences/builder/gold\"")))
      (is (= 303 (:status (post :post "/audiences/builder/gold" {"label" "Golder" "filters-data" "[]"}))))
      (is (= {:id :gold :label "Golder" :filters [] :cache-config {:ttl-minutes 5}}
             (ports/find-audience s :gold))))
    (testing "editing an unknown id is not found"
      (is (= 404 (:status (post :post "/audiences/builder/nope" {"label" "N" "filters-data" "[]"}))))
      (is (nil? (ports/find-audience s :nope))))))
