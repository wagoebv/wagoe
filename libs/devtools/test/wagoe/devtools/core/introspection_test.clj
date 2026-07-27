(ns wagoe.devtools.core.introspection-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [wagoe.devtools.core.introspection :as introspection]))

(def ^:private sample-routes
  [{:method :get  :path "/api/users"       :handler "wagoe.user.shell.http/list-users"   :module "user"}
   {:method :post :path "/api/users"       :handler "wagoe.user.shell.http/create-user"  :module "user"}
   {:method :get  :path "/api/admin/users" :handler "wagoe.admin.shell.http/list-users"  :module "admin"}])

(deftest ^:unit format-route-table-test
  (testing "formats routes into aligned table"
    (let [result (introspection/format-route-table sample-routes)]
      (is (str/includes? result "GET"))
      (is (str/includes? result "/api/users"))
      (is (str/includes? result "http/list-users"))
      (is (str/includes? result "METHOD"))
      (is (str/includes? result "PATH"))
      (is (str/includes? result "HANDLER"))))

  (testing "returns no-routes message for empty input"
    (is (= "No routes found." (introspection/format-route-table []))))

  (testing "filter-routes by module keyword returns matching routes"
    (let [result (introspection/filter-routes sample-routes :admin)]
      (is (= 1 (count result)))
      (is (= "admin" (:module (first result))))))

  (testing "filter-routes by path string returns substring matches"
    (let [result (introspection/filter-routes sample-routes "/api/users")]
      (is (= 2 (count result)))))

  (testing "filter-routes with non-string non-keyword returns unchanged"
    (let [result (introspection/filter-routes sample-routes nil)]
      (is (= 3 (count result))))))

(def ^:private sample-config
  {:boundary/http         {:port 3000 :host "localhost"}
   :boundary/postgresql   {:jdbcUrl "jdbc:postgresql://localhost/app"
                           :password "supersecret"
                           :username "app"}})

(deftest ^:unit format-config-tree-test
  (testing "formats full config showing port"
    (let [result (introspection/format-config-tree sample-config)]
      (is (str/includes? result "3000"))
      (is (str/includes? result ":boundary/http"))))

  (testing "redacts password in config"
    (let [result (introspection/format-config-tree sample-config)]
      (is (str/includes? result "****"))
      (is (not (str/includes? result "supersecret")))))

  (testing "drill-down with :database section shows postgresql config"
    (let [result (introspection/format-config-tree sample-config :database)]
      (is (str/includes? result ":boundary/postgresql"))
      (is (not (str/includes? result ":boundary/http")))))

  (testing "drill-down with :http section shows http config"
    (let [result (introspection/format-config-tree sample-config :http)]
      (is (str/includes? result ":boundary/http"))
      (is (str/includes? result "3000"))))

  (testing "drill-down with unknown section returns message"
    (let [result (introspection/format-config-tree sample-config :unknown)]
      (is (str/includes? result "unknown"))))

  (testing "empty config returns message"
    (is (= "Empty configuration." (introspection/format-config-tree {}))))

  (testing "redacts compound secret keys like :auth-token and :webhook-secret"
    (let [config {:boundary/webhooks {:webhook-secret "whsec_abc123"
                                      :endpoint "https://example.com"}
                  :boundary/auth    {:auth-token "tok_xyz"
                                     :provider "jwt"}}
          result (introspection/format-config-tree config)]
      (is (not (str/includes? result "whsec_abc123")))
      (is (not (str/includes? result "tok_xyz")))
      (is (str/includes? result "****"))
      (is (str/includes? result "example.com"))))

  (testing "redacts secrets in deeply nested maps"
    (let [config {:boundary/ai-service {:provider :anthropic
                                        :fallback {:api-key "sk-secret-123"
                                                   :model "claude"}}}
          result (introspection/format-config-tree config)]
      (is (not (str/includes? result "sk-secret-123")))
      (is (str/includes? result "****"))
      (is (str/includes? result "claude")))))

(deftest ^:unit format-module-summary-test
  (testing "formats module list with names and component counts"
    (let [modules [{:name "user" :components 5}
                   {:name "admin" :components 3}]
          result  (introspection/format-module-summary modules)]
      (is (str/includes? result "user"))
      (is (str/includes? result "admin"))
      (is (str/includes? result "5"))
      (is (str/includes? result "3"))))

  (testing "returns no-modules message for empty input"
    (is (= "No modules found." (introspection/format-module-summary [])))))
