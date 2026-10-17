(ns wagoe.search.shell.module-contribution-test
  "An application hands search its indexes through config and its own module,
   without requiring a search shell namespace."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.platform.shell.adapters.database.h2.core :as h2]
            [wagoe.platform.shell.system.config :as system-config]
            [wagoe.search.ports :as ports]
            [wagoe.search.shell.module-wiring]))

(def ^:private config
  {:wagoe/profile :test
   :active {:wagoe/settings {:name "test"}
            :wagoe/h2       {:memory true}
            :wagoe/search   {:indexes {:articles {:entity-type :article :fields []}}}
            :wagoe/catalog  {:enabled? true}}})

(deftest ^:integration indexes-reach-the-search-component
  (let [c (system-config/system-config config {:base-ns "acme"})]
    (testing "config's :indexes and the module's :search part both arrive"
      (is (= #{:articles} (set (keys (get-in c [:wagoe/search :indexes])))))
      (is (= #{:products :orders} (set (keys (get-in c [:wagoe/search :contributed]))))))

    (testing "and the engine holds them, components resolved"
      (let [system (ig/init (-> (select-keys c [:wagoe/search :wagoe/catalog-orders-index])
                                (assoc-in [:wagoe/search :db-ctx]
                                          {:adapter (h2/new-adapter) :datasource ::unused})))]
        (try
          (let [engine (:engine (:wagoe/search system))]
            (is (= :article (:entity-type (ports/index-definition engine :articles))))
            (is (true? (:api? (ports/index-definition engine :products))))
            (is (fn? (get-in (ports/index-definition engine :orders) [:api :authorize]))))
          (finally (ig/halt! system)))))))

(deftest ^:unit a-module-index-without-the-search-module-fails-the-boot
  (is (= :configuration-error
         (:type (ex-data (try (system-config/system-config
                               (update config :active dissoc :wagoe/search) {:base-ns "acme"})
                              nil
                              (catch clojure.lang.ExceptionInfo e e)))))))
