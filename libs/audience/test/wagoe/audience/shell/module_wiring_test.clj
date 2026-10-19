(ns wagoe.audience.shell.module-wiring-test
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [wagoe.audience.ports :as ports]
            [wagoe.audience.shell.module-wiring]))

(def ^:private users
  (reify ports/IUserDataSource
    (query-users-sql [_ _] [])
    (load-users [_ _] [])))

(deftest ^:unit ^:security filter-fields-are-checked-at-boot
  (let [ds      (jdbc/get-datasource "jdbc:h2:mem:audience-wiring")
        init    (fn [fields] (ig/init-key :wagoe/audience {:db-ctx           {:datasource ds}
                                                           :user-data-source users
                                                           :filter-fields    fields}))
        refusal (fn [fields] (try (init fields) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))]
    (testing "configured fields join the defaults"
      (let [allowed (:filter-fields (init #{:plan}))]
        (is (contains? allowed :plan))
        (is (contains? allowed :role))))
    (testing "a credential, or anything but keywords, refuses to boot"
      (doseq [fields [#{:password-hash} #{:mfa_secret} ["plan"] :plan]]
        (is (= :configuration-error (:type (refusal fields))) (pr-str fields))))))
