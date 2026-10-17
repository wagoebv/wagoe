(ns wagoe.devtools.shell.dashboard.pages.overview-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.devtools.shell.dashboard.pages.overview :as overview]
            [wagoe.config :as config]))

(defrecord PostgreSQLAdapter [])
(defrecord SqliteAdapter [])
(defrecord SomethingElse [])

(deftest ^:unit adapter-label-test
  (testing "a record is named by the database it speaks to, not by its class"
    ;; `(str (type x))` used to put the fully qualified name — prefixed with
    ;; the word "class" — on the dashboard (BOU-396).
    (is (= "PostgreSQL" (overview/adapter-label (->PostgreSQLAdapter))))
    (is (= "Sqlite" (overview/adapter-label (->SqliteAdapter)))))

  (testing "a keyword adapter is used as it is"
    (is (= "postgresql" (overview/adapter-label :postgresql))))

  (testing "a record not following the Adapter suffix keeps its simple name"
    (is (= "SomethingElse" (overview/adapter-label (->SomethingElse)))))

  (testing "no adapter, no label"
    (is (nil? (overview/adapter-label nil)))))

(deftest ^:unit the-profile-is-the-boot-profile
  ;; It read WAG_ENV raw, so `development` showed as itself and a blank one as
  ;; nothing (BOU-686).
  (let [profile #(:profile (#'overview/system-data %))]
    (is (= "dev" (profile {:profile :dev})))
    (with-redefs [config/active-profile (constantly :test)]
      (is (= "test" (profile {})) "without one, active-profile decides"))))
