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
   :generated-columns [:extra]})

(def spec
  "What MySQL does differently. See common.adapter for the keys."
  {:label              "MySQL"
   :dialect            :mysql
   :driver             "com.mysql.cj.jdbc.Driver"
   :jdbc-url           connection/build-jdbc-url
   :pool               connection/pool-defaults
   :session-statements connection/session-statements
   :booleans           :int
   :string-match       :like
   :table-exists?      (partial introspection/information-schema-table-exists?
                                introspection-spec)
   :table-info         (partial introspection/information-schema-table-info
                                introspection-spec)})

(defn new-adapter
  "Create a MySQL adapter."
  []
  (adapter/new-adapter spec))
