(ns wagoe.audience.shell.http-served-test
  "The audience API as served: the platform's router, body decoding and
   coercion in front of the real handlers and service."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.audience.ports :as ports]
            [wagoe.audience.shell.http :as sut]
            [wagoe.audience.shell.service :as service]
            [wagoe.platform.shell.http.reitit-router :as reitit-router]))

(def ^:private u1 #uuid "00000000-0000-0000-0000-000000000001")
(def ^:private u2 #uuid "00000000-0000-0000-0000-000000000002")

(defn- store []
  (let [db (atom {})]
    (reify ports/IAudienceRepository
      (save-audience [_ d] (swap! db assoc (:id d) d) d)
      (find-audience [_ id] (get @db id))
      (list-audiences [_] (vec (vals @db)))
      (list-audiences [this _] (ports/list-audiences this))
      (delete-audience [_ id] (swap! db dissoc id) nil))))

(def ^:private users
  "Two users; a SQL filter narrows them to the first."
  (reify ports/IUserDataSource
    (query-users-sql [_ clause] (if clause [u1] [u1 u2]))
    (load-users [_ ids] (mapv (fn [id] {:id id}) ids))))

(defn- served [s]
  (let [resolver (service/create-audience-service {:repository s :user-data-source users})
        handler  (reitit-router/compile-routes (sut/audience-api-routes resolver s)
                                               {:swagger-enabled false})]
    (fn [method uri body]
      (let [r (handler {:request-method method :uri uri
                        :headers {"content-type" "application/json" "accept" "application/json"}
                        :body (java.io.ByteArrayInputStream. (.getBytes (json/generate-string body)))})]
        (update r :body #(some-> % slurp (json/parse-string true)))))))

(deftest ^:unit create-update-and-preview-read-the-body
  ;; No route declared :parameters, so [:parameters :body] was empty and every
  ;; create and update answered 422 whatever was sent.
  (let [s    (store)
        call (served s)]
    (testing "create stores the definition sent, its values coerced"
      (let [r (call :post "/audiences" {:id "gold" :label "Gold"
                                        :filters [{:type "role" :field "plan" :op "eq" :value "gold"}]})]
        (is (= 201 (:status r)))
        (is (= {:id "gold" :label "Gold"} (:body r))))
      (is (= {:id :gold :label "Gold"
              :filters [{:type :role :field :plan :op :eq :value "gold"}]}
             (ports/find-audience s :gold))))

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

    (testing "a body missing required keys is a validation error naming them"
      (doseq [[method uri] [[:post "/audiences"] [:put "/audiences/gold"]]]
        (let [r (call method uri {})]
          (is (= 400 (:status r)) uri)
          (is (= "validation-error" (get-in r [:body :error :type])) uri))))))

(deftest ^:unit filters-beside-compose-are-refused
  ;; The service evaluates :compose and ignored :filters next to it, so the
  ;; audience was larger than its definition said.
  (let [s    (store)
        call (served s)
        both {:label   "Both"
              :filters [{:type "role" :field "plan" :op "eq" :value "gold"}]
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
