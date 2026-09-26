(ns wagoe.platform.shell.adapters.database.postgresql.core
  "PostgreSQL adapter.

   Case-insensitive matching with ILIKE, native booleans, and session settings
   carried on the JDBC URL rather than applied per connection — see
   postgresql.connection."
  (:require [wagoe.platform.shell.adapters.database.common.adapter :as adapter]
            [wagoe.platform.shell.adapters.database.common.introspection :as introspection]
            [wagoe.platform.shell.adapters.database.postgresql.connection :as connection]))

(def ^:private introspection-spec
  {:schema "public" :honey nil :fold-table-name? true
   :generated-columns [:is_identity :is_generated]})

(def spec
  "What PostgreSQL does differently. See common.adapter for the keys."
  {:label              "PostgreSQL"
   ;; nil, not :postgresql — HoneySQL's default dialect is what PostgreSQL
   ;; wants, and callers resolve nil with (or (dialect a) :postgresql).
   :dialect            nil
   :driver             "org.postgresql.Driver"
   :jdbc-url           connection/build-jdbc-url
   :pool               connection/pool-defaults
   :session-statements connection/session-statements
   :booleans           :native
   :string-match       :ilike
   :table-exists?      (partial introspection/information-schema-table-exists?
                                introspection-spec)
   :table-info         (partial introspection/information-schema-table-info
                                introspection-spec)})

(defn new-adapter
  "Create a PostgreSQL adapter."
  []
  (adapter/new-adapter spec))
