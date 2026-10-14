(ns wagoe.platform.shell.adapters.database.postgresql.connection
  "PostgreSQL connection settings and JDBC URL.")

(def ^:private default-application-name
  "Default application name for PostgreSQL connections."
  "wagoe-app")

(def ^:private default-statement-timeout-ms
  "Default statement timeout in milliseconds (30 seconds)."
  30000)

(defn build-jdbc-url
  "Build the PostgreSQL JDBC URL from `db-config`.

   Session settings ride on the URL so every physical connection in the pool
   gets them:
   - stringtype=unspecified: let the server infer the type of string literals
   - ApplicationName: identifies the connection in pg_stat_activity
   - options: statement_timeout guard, and a TimeZone that does not take —
     pgjdbc sends the JVM's timezone in the startup packet after `options`,
     so sessions run in local time (BOU-431)
   - reWriteBatchedInserts: batches multi-row INSERTs into single statements
   - logServerErrorDetail=false: keeps the server's Detail (the row, the key's
     value) out of exception messages and so out of logs; `reported` reads it
     from the exception for parsing (BOU-626)"
  [{:keys [host port name application-name statement-timeout-ms]}]
  (let [timeout (or statement-timeout-ms default-statement-timeout-ms)
        options (cond-> "-c%20TimeZone=UTC"
                  (pos? timeout) (str "%20-c%20statement_timeout=" timeout))]
    (str "jdbc:postgresql://" host ":" port "/" name
         "?stringtype=unspecified"
         "&ApplicationName=" (or application-name default-application-name)
         "&reWriteBatchedInserts=true"
         "&logServerErrorDetail=false"
         "&options=" options)))

(defn session-statements
  "A connectivity probe only. The session settings are on the JDBC URL above —
   a SET here would reach just the one pooled connection that ran it."
  [_db-config]
  [["SELECT 1"]])

(def pool-defaults
  "HikariCP defaults for a server database."
  {:minimum-idle          5
   :maximum-pool-size     20
   :connection-timeout-ms 30000
   :idle-timeout-ms       600000
   :max-lifetime-ms       1800000})
