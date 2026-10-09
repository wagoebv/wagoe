(ns wagoe.platform.shell.adapters.database.common.adapter
  "One `DBAdapter` implementation, over a per-engine spec.

   There used to be four, and they were copies: each `query.clj` was the same
   sixty lines turning on two decisions, and each `core.clj` was thirty lines of
   delegation. A fix landed in one and not its neighbours — that is how H2 came
   to carry a `table-exists?` workaround while SQLite and MySQL reported every
   table as absent (BOU-430).

   A spec names what the port says may differ between engines, and nothing else:

     :label              for log lines — \"MySQL\"
     :dialect            what callers dispatch on; nil means PostgreSQL
     :driver             JDBC driver class name
     :jdbc-url           (fn [db-config] -> String)
     :pool               HikariCP defaults
     :session-statements (fn [db-config] -> seq of JDBC param vectors)
     :booleans           :native or :int
     :string-match       :like or :ilike
     :table-exists?      (fn [datasource table-name] -> boolean)
     :table-info         (fn [datasource table-name] -> vector of column maps)
     :engine             :postgresql, :mysql, :sqlite or :h2
     :capabilities       a subset of protocols/capability-keywords
     :column-types       {logical-type DDL-type} for protocols/logical-column-types"
  (:require [wagoe.core.utils.type-conversion :as tc]
            [wagoe.platform.ports.database :as protocols]
            [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]))

;; =============================================================================
;; Boolean representation
;; =============================================================================

(defn boolean->db
  "Encode `value` the way `representation` (:native or :int) stores booleans."
  [representation value]
  (if (= :int representation)
    (tc/boolean->int value)
    value))

(defn db->boolean
  "Decode a stored boolean written by `boolean->db`."
  [representation value]
  (if (= :int representation)
    (tc/int->boolean value)
    value))

;; =============================================================================
;; WHERE building
;; =============================================================================

(defn case-insensitive-like
  "`field` LIKE `pattern`, ignoring case. ILIKE where the engine has it;
   elsewhere both sides lowered, since H2's LIKE is case-sensitive (ADR-039).
   The pattern is lowered in SQL too: the JVM's locale folds differently (a
   Turkish one turns \"ID\" into \"ıd\")."
  [string-match field pattern]
  (if (= :ilike string-match)
    [:ilike field pattern]
    [:like [:lower field] [:lower pattern]]))

(defn build-where-clause
  "Build a WHERE fragment from `filters`, in this engine's terms.

   `string-match` is the containment operator (PostgreSQL matches
   case-insensitively with ILIKE; the rest use LIKE) and `booleans` is how the
   engine stores a boolean. Those two were the whole of the difference between
   the four copies this replaces."
  [{:keys [string-match booleans]} filters]
  (when (seq filters)
    (let [conditions (for [[field value] filters
                           :when (some? value)]
                       (cond
                         (string? value)  (case-insensitive-like string-match field (str "%" value "%"))
                         (vector? value)  [:in field value]
                         (boolean? value) [:= field (boolean->db booleans value)]
                         :else            [:= field value]))]
      (when (seq conditions)
        (if (= 1 (count conditions))
          (first conditions)
          (cons :and conditions))))))

;; =============================================================================
;; Connection initialization
;; =============================================================================

(defn run-session-statements!
  "Run each statement, and keep going when one fails.

   Session settings are best-effort: a connection that could not be told to use
   UTC still works. They are run independently because they used to share a
   single try — MySQL's `sql_mode` is rejected by MySQL 8, which meant the
   timezone and charset statements after it never ran at all (BOU-367)."
  [label datasource statements]
  (when (nil? datasource)
    (throw (IllegalArgumentException. "Datasource cannot be nil")))
  (let [failures (reduce (fn [failed statement]
                           (try
                             (jdbc/execute! datasource statement)
                             failed
                             (catch Exception e
                               (log/warn "Session statement failed, continuing"
                                         {:adapter label
                                          :statement (first statement)
                                          :error (.getMessage e)})
                               (inc failed))))
                         0
                         statements)]
    (log/debug "Session initialization completed"
               {:adapter label :statements (count statements) :failed failures})
    nil))

;; =============================================================================
;; The adapter
;; =============================================================================

(defrecord SqlAdapter [spec]
  protocols/DBAdapter

  (dialect [_] (:dialect spec))

  (jdbc-driver [_] (:driver spec))

  (jdbc-url [_ db-config] ((:jdbc-url spec) db-config))

  (pool-defaults [_] (:pool spec))

  (init-connection! [_ datasource db-config]
    (run-session-statements! (:label spec)
                             datasource
                             ((:session-statements spec) db-config)))

  (build-where [_ filters] (build-where-clause spec filters))

  (boolean->db [_ value] (boolean->db (:booleans spec) value))

  (db->boolean [_ value] (db->boolean (:booleans spec) value))

  (table-exists? [_ datasource table-name]
    ((:table-exists? spec) datasource table-name))

  (get-table-info [_ datasource table-name]
    ((:table-info spec) datasource table-name))

  (engine [_] (:engine spec))

  (capabilities [_] (:capabilities spec))

  (column-type [_ logical]
    (or (get (:column-types spec) logical)
        (throw (ex-info (str "No column type for " logical)
                        {:type :validation-error :logical logical}))))

  (like [_ field pattern] (case-insensitive-like (:string-match spec) field pattern)))

(defn new-adapter
  "Build the adapter described by `spec`. See this namespace's docstring."
  [spec]
  (->SqlAdapter spec))
