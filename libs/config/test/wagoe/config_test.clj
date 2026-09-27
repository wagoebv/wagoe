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

;; =============================================================================
;; #include inside a jar (BOU-563)
;; =============================================================================

(defn- temp-jar
  "A jar holding `entries` {path content}."
  ^java.io.File [entries]
  (let [f (java.io.File/createTempFile "wagoe-config" ".jar")]
    (.deleteOnExit f)
    (with-open [out (java.util.jar.JarOutputStream. (java.io.FileOutputStream. f))]
      (doseq [[path ^String content] entries]
        (.putNextEntry out (java.util.jar.JarEntry. ^String path))
        (.write out (.getBytes content "UTF-8"))
        (.closeEntry out)))
    f))

(defn- jar-url [^java.io.File jar path]
  (java.net.URL. (str "jar:" (.toURI jar) "!/" path)))

(deftest ^:unit an-include-resolves-next-to-its-config-inside-a-jar
  ;; Aero resolved a relative #include against a file only, so from the
  ;; uberjar every include came back as {:aero/missing-include ...} and the
  ;; admin started without its entities (BOU-563).
  (let [jar (temp-jar {"conf/test/config.edn"      "{:entities #merge [#include \"admin/users.edn\"]}"
                       "conf/test/admin/users.edn" "{:users {:label \"Users\" :nested #include \"more.edn\"}}"
                       "conf/test/admin/more.edn"  "{:ok true}"})]
    (is (= {:entities {:users {:label "Users" :nested {:ok true}}}}
           (sut/read-config-resource (jar-url jar "conf/test/config.edn") :test)))))

(deftest ^:unit a-missing-include-fails-and-names-the-file
  (let [jar (temp-jar {"conf/test/config.edn" "{:entities #merge [#include \"admin/gone.edn\"]}"})
        e   (try (sut/read-config-resource (jar-url jar "conf/test/config.edn") :test) nil
                 (catch clojure.lang.ExceptionInfo e e))]
    (is (= :configuration-error (:type (ex-data e))))
    (is (re-find #"admin/gone\.edn" (str (ex-message e))))
    (is (re-find #"conf/test/config\.edn" (str (ex-message e))))))
