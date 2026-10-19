(ns wagoe.platform.shell.authorization-test
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.platform.ports.authorization :as ports]
            [wagoe.platform.shell.http.reitit-router]
            [wagoe.platform.shell.system.config :as sys]
            [wagoe.platform.shell.system.wiring]))

(defn- registry [declarations]
  (ig/init-key :wagoe/permission-registry {:declarations declarations}))

(deftest ^:unit the-registry-reads-through-its-port
  (let [r (registry {:wagoe/orders {:roles {:orders/viewer {:permissions #{:orders/read}}}}})]
    (is (ports/permission? r :orders/write))
    (is (not (ports/permission? r :orders/refund)))
    (is (= #{:orders/viewer} (set (keys (ports/roles r)))))
    (is (= {} (ports/scopes r)))))

(deftest ^:unit a-role-naming-an-unknown-permission-fails-boot
  (let [e (is (thrown? clojure.lang.ExceptionInfo
                       (registry {:wagoe/orders {:roles {:orders/viewer {:permissions #{:orders/raed}}}}})))]
    (is (= :configuration-error (:type (ex-data e))))
    (is (re-find #":orders/raed" (ex-message e)))))

(defn- handler [registry module-routes]
  (with-redefs [wagoe.platform.shell.http.reitit-router/compile-routes (fn [_ _] identity)]
    (ig/init-key :wagoe/http-handler
                 {:module-routes       module-routes
                  :permission-registry registry
                  :router              ::router
                  :logger              ::logger
                  :metrics-emitter     ::metrics
                  :error-reporter      ::error-reporter
                  :config              {:active {:wagoe/settings {:name "t"}}}})))

(deftest ^:unit a-route-permission-outside-the-registry-fails-boot
  (let [r (registry {:wagoe/orders nil})]
    (testing "a declared one boots"
      (is (some? (handler r [{:api [["/orders" {:get {:handler identity :permission :orders/read}}]]}]))))
    (testing "an unknown one does not"
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (handler r [{:api [["/orders" {:post {:handler identity :permission :orders/refund}}]]}])))]
        (is (= :configuration-error (:type (ex-data e))))
        (is (= [{:path "/api/v1/orders" :permission :orders/refund}] (:routes (ex-data e))))))))

(deftest ^:unit the-system-config-collects-every-modules-declaration
  (let [m (sys/system-config {:wagoe/profile :test
                              :active {:wagoe/settings {:name "t"}
                                       :wagoe/h2       {:memory true}
                                       :wagoe/widgets  {:enabled? true}}})]
    (is (= (ig/ref :wagoe/permission-registry)
           (get-in m [:wagoe/http-handler :permission-registry])))
    (testing "a discovered module's `authorization` var included"
      (is (= #{:widgets/viewer}
             (set (keys (get-in m [:wagoe/permission-registry :declarations :wagoe/widgets :roles]))))))))
