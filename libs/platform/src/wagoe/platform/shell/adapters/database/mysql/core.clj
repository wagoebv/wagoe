(ns wagoe.platform.shell.adapters.database.mysql.core
  "MySQL adapter.

   LIKE (case-insensitive by MySQL's default collation), booleans as TINYINT(1),
   and a table name that is not folded — on a case-sensitive filesystem MySQL
   table names are case-sensitive."
  (:require [wagoe.platform.shell.adapters.database.common.adapter :as adapter]
            [wagoe.platform.shell.adapters.database.common.introspection :as introspection]
            [wagoe.platform.shell.adapters.database.mysql.connection :as connection]))

(def ^:private introspection-spec
  {:schema [:database] :honey {:dialect :mysql} :fold-table-name? false
   :generated-columns [:extra]
   ;; data_type drops the (1) and (36) that mark a boolean and a UUID (BOU-649).
   :type-column :column_type})

(def spec
  "What MySQL does differently. See common.adapter for the keys."
  {:label              "MySQL"
   :dialect            :mysql
   :driver             "com.mysql.cj.jdbc.Driver"
   :jdbc-url           connection/build-jdbc-url
   :pool               connection/pool-defaults
   :session-statements connection/session-statements
   :param->db          connection/param->db
   :db->value          connection/db->value
   :booleans           :int
   :string-match       :like
   :engine             :mysql
   :now-ms-sql         "CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS SIGNED)"
   :capabilities       #{:row-locks :alter-foreign-key}
   ;; DATETIME(6): plain DATETIME drops fractional seconds (BOU-574).
   :column-types       {:uuid "CHAR(36)" :instant "DATETIME(6)" :json "JSON"
                        :boolean "TINYINT(1)" :text "LONGTEXT"
                        :int "INT" :bigint "BIGINT" :double "DOUBLE" :enum "VARCHAR(50)"}
   :table-exists?      (partial introspection/information-schema-table-exists?
                                introspection-spec)
   :table-info         (partial introspection/information-schema-table-info
                                introspection-spec)})

(defn new-adapter
  "Create a MySQL adapter."
  []
  (adapter/new-adapter spec))
