(ns wagoe.platform.shell.adapters.database.sqlite.connection
  "SQLite connection settings and JDBC URL."
  (:import [java.lang.reflect Method]))

(def ^:private mmap-size-bytes
  "Memory-mapped I/O size in bytes (256MB)."
  268435456)

(def ^:private cache-size-pages
  "Page cache size in pages (~10MB with 1KB pages)."
  10000)

(def ^:private busy-timeout-ms
  "Busy timeout in milliseconds (5 seconds)."
  5000)

(defn build-jdbc-url
  "Build the SQLite JDBC URL from `db-config`."
  [db-config]
  (str "jdbc:sqlite:" (:database-path db-config)))

(defn session-statements
  "PRAGMAs for concurrency, durability and referential integrity. Each runs on
   its own — see common.adapter/run-session-statements!."
  [db-config]
  (into [["PRAGMA journal_mode=WAL"]        ; Write-Ahead Logging, better concurrency
         ["PRAGMA synchronous=NORMAL"]      ; balance safety against speed
         ["PRAGMA foreign_keys=ON"]
         ["PRAGMA temp_store=MEMORY"]
         [(str "PRAGMA mmap_size=" mmap-size-bytes)]
         [(str "PRAGMA cache_size=" cache-size-pages)]
         [(str "PRAGMA busy_timeout=" busy-timeout-ms)]]
        (map vector (:pragmas db-config))))

(defn- protected-method
  "A protected method of org.sqlite.Function, which a proxy cannot call directly."
  ^Method [^Class function name & arg-types]
  (doto (.getDeclaredMethod function name (into-array Class arg-types))
    (.setAccessible true)))

(def ^:private register-lower
  "Compiled on first use, not at load: platform does not ship the SQLite
   driver, and this namespace must load without it."
  (delay
    (let [function    (Class/forName "org.sqlite.Function")
          value-text  (protected-method function "value_text" Integer/TYPE)
          result-text (protected-method function "result" String)
          register    (binding [*ns* (the-ns 'wagoe.platform.shell.adapters.database.sqlite.connection)]
                        (eval '(fn [connection ^java.lang.reflect.Method value-text
                                    ^java.lang.reflect.Method result-text]
                                 (org.sqlite.Function/create
                                  connection "lower"
                                  (proxy [org.sqlite.Function] []
                                    (xFunc []
                                      (let [s (.invoke value-text this (object-array [(int 0)]))]
                                        (.invoke result-text this
                                                 (object-array [(some-> ^String s
                                                                        (.toLowerCase java.util.Locale/ROOT))])))))
                                  1
                                  org.sqlite.Function/FLAG_DETERMINISTIC))))]
      #(register % value-text result-text))))

(defn register-unicode-lower!
  "Replace lower() on `connection` with one that folds all of Unicode. SQLite's
   own folds ASCII only, so `like` missed \"Élodie\" for \"%élodie%\". Root locale,
   so the JVM's (a Turkish \"I\") does not change the answer (BOU-606)."
  [connection]
  (@register-lower connection))

(def pool-defaults
  "HikariCP defaults for an embedded database. SQLite serialises writes, so the
   pool stays small."
  {:minimum-idle          1
   :maximum-pool-size     5
   :connection-timeout-ms 30000
   :idle-timeout-ms       600000
   :max-lifetime-ms       1800000})
