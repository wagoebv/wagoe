(ns wagoe.config-test
  "Config accessors, tested against the library's own classpath.

   These lived in the application's test suite, where they passed because the
   whole monorepo was loaded. The point of BOU-306 is that published libraries
   read configuration through this namespace, so it has to work with nothing
   but its own dependencies."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.config :as sut]))

(deftest ^:unit the-active-adapter-decides-the-db-spec
  (testing "sqlite"
    (is (= {:adapter :sqlite :database-path "app.db" :pool nil}
           (sut/db-spec {:active {:wagoe/sqlite {:db "app.db"}}}))))

  (testing "postgresql maps its own keys"
    (let [spec (sut/db-spec {:active {:wagoe/postgresql {:host "db" :port 5432
                                                         :dbname "app" :user "u"
                                                         :password "p"}}})]
      (is (= :postgresql (:adapter spec)))
      (is (= "db" (:host spec)))
      (is (= "app" (:name spec)) "dbname is exposed as :name")))

  (testing "no adapter at all is an error, not a default"
    ;; Falling back to something would boot an application against a database
    ;; nobody chose.
    (is (thrown? clojure.lang.ExceptionInfo (sut/db-adapter {:active {}})))
    (is (thrown? clojure.lang.ExceptionInfo (sut/db-spec {:active {}})))))

(deftest ^:unit optional-sections-default-rather-than-throw
  ;; Read on every boot, and absent in most configs. Throwing here would make
  ;; every optional feature mandatory.
  (testing "error reporting"
    (is (= {:provider :no-op} (sut/error-reporting-config {:active {}}))))

  (testing "user validation"
    (is (map? (sut/user-validation-config {:active {}})))))
