(ns wagoe.audience.shell.http-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.audience.ports :as ports]
            [wagoe.audience.shell.http :as sut]))

(deftest ^:unit ^:security an-invalid-definition-names-its-fields-not-the-schema
  ;; `details` was the Malli explanation as a string: the whole schema, fn
  ;; objects included (BOU-586).
  (doseq [[label response]
          [["create" (sut/create-audience-handler nil nil {:parameters {:body {:id :x}}})]
           ["update" (sut/update-audience-handler nil nil {:path-params {:id "x"}
                                                           :parameters  {:body {}}})]]]
    (testing label
      (let [err (get-in response [:body :error])]
        (is (= 422 (:status response)))
        (is (= "unprocessable" (:type err)))
        (is (= ["missing required key"] (get-in err [:details :errors :label])))
        (is (not (str/includes? (pr-str err) ":map")) "the schema leaked")))))

(defn- store
  "An in-memory IAudienceRepository, or one whose every call throws `ex`."
  ([] (store nil))
  ([ex]
   (let [db (atom {})]
     (reify ports/IAudienceRepository
       (save-audience [_ d] (when ex (throw ex)) (swap! db assoc (:id d) d) d)
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
    (testing "create stores the definition, filtersData standing in for filters"
      (let [res (sut/create-audience-handler nil s {:parameters {:body {:id :vip :label "VIP"
                                                                        :filtersData [{:type :role :field :plan
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
