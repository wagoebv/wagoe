(ns wagoe.platform.shell.boot-logging-test
  "What a boot says at INFO: not every CREATE TABLE, and that the dev dashboard
   is off outside dev (BOU-588)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging :as log]
            [clojure.tools.logging.impl :as impl]
            [next.jdbc :as jdbc]
            [wagoe.platform.shell.adapters.database.common.core :as db]
            [wagoe.platform.shell.adapters.database.h2.core :as h2]
            [wagoe.platform.shell.system.config :as system-config]))

(defn- capturing-factory [out]
  (let [logger (reify impl/Logger
                 (enabled? [_ _] true)
                 (write! [_ level _ msg] (swap! out conj [level (str msg)])))]
    (reify impl/LoggerFactory
      (name [_] "capture")
      (get-logger [_ _] logger))))

(defn- logged
  "[[level message]] logged while `f` runs, at every level."
  [f]
  (let [out (atom [])]
    (binding [log/*logger-factory* (capturing-factory out)]
      (f))
    @out))

(deftest ^:integration ddl-is-logged-at-debug
  (let [ctx  {:adapter    (h2/new-adapter)
              :datasource (jdbc/get-datasource {:jdbcUrl (str "jdbc:h2:mem:ddl-log-" (random-uuid) ";DB_CLOSE_DELAY=-1")})}
        ddl  "CREATE TABLE invoices (id UUID PRIMARY KEY, number VARCHAR(255) NOT NULL, secret_column INT)"
        logs (logged #(db/execute-ddl! ctx ddl))]
    (is (seq logs))
    (is (empty? (filter (fn [[level msg]] (and (not= :debug level) (str/includes? msg "CREATE TABLE"))) logs))
        (pr-str logs))
    (testing "and the preview is a preview"
      (is (not-any? (fn [[_ msg]] (str/includes? msg "secret_column")) logs) (pr-str logs)))))

(defn- config [profile]
  {:wagoe/profile profile
   :active {:wagoe/settings {:name "test"}
            :wagoe/h2       {:memory true}}})

(deftest ^:unit a-boot-outside-dev-says-the-dashboard-is-off
  (doseq [profile [:prod :acc :test]]
    (testing profile
      (let [lines (filter (fn [[level msg]] (and (= :info level) (re-find #"(?i)dashboard" msg)))
                          (logged #(system-config/system-config (config profile))))]
        (is (= 1 (count lines)) (pr-str lines))
        (is (re-find #"(?i)off" (second (first lines)))))))
  (testing "in dev it is not said"
    (is (not-any? (fn [[_ msg]] (re-find #"(?i)dashboard is off" msg))
                  (logged #(system-config/system-config (config :dev)))))))
