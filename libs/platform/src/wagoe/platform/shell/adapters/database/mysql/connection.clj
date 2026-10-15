(ns wagoe.platform.shell.adapters.database.mysql.connection
  "MySQL connection settings and JDBC URL."
  (:require [clojure.string :as str]
            [wagoe.core.utils.type-conversion :as tc])
  (:import [java.time Instant LocalDateTime ZoneOffset]))

(def ^:private default-sql-mode
  "Strict SQL mode.

   NO_AUTO_CREATE_USER was dropped here because MySQL 8 rejects the whole SET
   when it appears — and the statement used to share a try with the timezone
   and charset settings, so those never ran either and connections stayed on
   the server's local timezone (BOU-367)."
  "STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION")

(defn build-jdbc-url
  "Build the MySQL JDBC URL from `db-config`."
  ;; :name is bound as db-name — destructuring it as `name` shadowed
  ;; clojure.core/name, and the parameter join below called the database name
  ;; as a function. Every MySQL connection threw (BOU-430).
  [{:keys [host port connection-params] db-name :name}]
  (let [base-url       (str "jdbc:mysql://" host ":" port "/" db-name)
        default-params {:serverTimezone "UTC"
                        :useSSL "true"
                        :requireSSL "false"
                        :verifyServerCertificate "false"
                        :useUnicode "true"
                        :characterEncoding "utf8"
                        :zeroDateTimeBehavior "convertToNull"}
        all-params     (merge default-params (or connection-params {}))
        param-str      (str/join "&" (map (fn [[k v]] (str (name k) "=" v)) all-params))]
    (str base-url "?" param-str)))

(defn param->db
  "MySQL Java-serialises a UUID and strict mode rejects an ISO instant string,
   which persistence writes; send text and a UTC DATETIME (BOU-648)."
  [value]
  (let [v (or (tc/iso-instant value) value)]
    (cond
      (uuid? v)              (str v)
      (instance? Instant v)  (LocalDateTime/ofInstant v ZoneOffset/UTC)
      :else                  v)))

(defn session-statements
  "Strict mode, UTC, and UTF-8. Each runs on its own — see
   common.adapter/run-session-statements!."
  [db-config]
  (let [sql-mode (get db-config :sql-mode default-sql-mode)]
    (cond-> []
      (seq sql-mode) (conj ["SET SESSION sql_mode = ?" sql-mode])
      :always        (conj ["SET SESSION time_zone = '+00:00'"]
                           ["SET NAMES utf8mb4"]))))

(def pool-defaults
  "HikariCP defaults for a server database."
  {:minimum-idle          5
   :maximum-pool-size     15
   :connection-timeout-ms 30000
   :idle-timeout-ms       600000
   :max-lifetime-ms       1800000})
