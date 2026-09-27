(ns wagoe.search.shell.http-test
  (:require [clojure.test :refer [deftest is testing]]
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
