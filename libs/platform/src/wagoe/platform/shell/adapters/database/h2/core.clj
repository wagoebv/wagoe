(ns wagoe.platform.shell.adapters.database.h2.core
  "H2 adapter.

   Runs in PostgreSQL compatibility mode with DATABASE_TO_LOWER, so identifiers
   behave like PostgreSQL's; native booleans; ANSI SQL."
  (:require [wagoe.platform.shell.adapters.database.common.adapter :as adapter]
            [wagoe.platform.shell.adapters.database.common.core :refer [with-transaction*]]
            [wagoe.platform.shell.adapters.database.common.introspection :as introspection]
            [wagoe.platform.shell.adapters.database.h2.connection :as connection]))

(def ^:private introspection-spec
  {:schema "public" :honey {:dialect :ansi} :fold-table-name? true
   :generated-columns [:is_identity :is_generated]})

(def spec
  "What H2 does differently. See common.adapter for the keys."
  {:label              "H2"
   :dialect            :ansi
   :driver             "org.h2.Driver"
   :jdbc-url           connection/build-jdbc-url
   :pool               connection/pool-defaults
   :session-statements connection/session-statements
   :booleans           :native
   :string-match       :like
   :table-exists?      (partial introspection/information-schema-table-exists?
                                introspection-spec)
   :table-info         (partial introspection/information-schema-table-info
                                introspection-spec)})

(defn new-adapter
  "Create an H2 adapter."
  []
  (adapter/new-adapter spec))

(defmacro with-transaction
  "Run `body` in a transaction on `datasource`.

   Example:
     (with-transaction [tx datasource]
       (execute-update! tx query))"
  [binding & body]
  `(with-transaction* ~(second binding)
     (fn [~(first binding)]
       ~@body)))
