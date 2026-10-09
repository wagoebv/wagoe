(ns wagoe.platform.shell.adapters.database.sqlite.core
  "SQLite adapter.

   Booleans as integers, no information_schema — introspection reads
   sqlite_master and PRAGMA table_info — and a set of PRAGMAs applied per
   connection, see sqlite.connection."
  (:require [wagoe.platform.shell.adapters.database.common.adapter :as adapter]
            [wagoe.platform.shell.adapters.database.common.introspection :as introspection]
            [wagoe.platform.shell.adapters.database.sqlite.connection :as connection]))

(def spec
  "What SQLite does differently. See common.adapter for the keys."
  {:label              "SQLite"
   ;; It answered nil, which callers doing (or (dialect a) :postgresql) read as
   ;; PostgreSQL — SQLite was handed UUID and VARCHAR columns (BOU-430). The
   ;; mapping from :sqlite to "no HoneySQL dialect" lives in
   ;; wagoe.platform.core.database.query.
   :dialect            :sqlite
   :driver             "org.sqlite.JDBC"
   :jdbc-url           connection/build-jdbc-url
   :pool               connection/pool-defaults
   :session-statements connection/session-statements
   :booleans           :int
   :string-match       :like
   :engine             :sqlite
   :capabilities       #{:transactional-ddl :index-if-not-exists :on-conflict}
   :column-types       {:uuid "CHAR(36)" :instant "TEXT" :json "TEXT"
                        :boolean "INTEGER" :text "TEXT"}
   :table-exists?      introspection/sqlite-table-exists?
   :table-info         introspection/sqlite-table-info})

(defn new-adapter
  "Create a SQLite adapter."
  []
  (adapter/new-adapter spec))
