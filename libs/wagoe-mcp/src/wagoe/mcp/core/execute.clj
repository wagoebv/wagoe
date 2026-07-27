(ns wagoe.mcp.core.execute
  "Pure policy for the Tier 2 execute tools (BOU-102): read-only SQL
   classification, row-limit clamping, and migration-direction validation.

   No I/O and no throwing — the shell executors (wagoe.mcp.shell.tools) call
   these to decide, then run the side effect and enforce the decision. These are
   defense-in-depth: the real guards are the security gate (only :full context
   reaches Tier 2) and, for query-db, a read-only database role. The SQL
   classifier is a coarse keyword check, not a parser; it can be fooled by a
   write keyword used as an identifier, which is why the read-only role is the
   primary control."
  (:require [clojure.string :as str]))

;; --- query-db: read-only classification + row limit -------------------------

(def default-row-limit
  "Rows returned when the caller gives no limit."
  100)

(def max-row-limit
  "Hard ceiling on rows returned, regardless of the requested limit."
  1000)

(def ^:private read-leading-re
  ;; A read-only statement begins with one of these verbs (case-insensitive,
  ;; dotall so a leading newline still anchors).
  #"(?is)^\s*(select|with|explain|show|values|table|pragma)\b")

(def ^:private write-token-re
  ;; Any of these as a whole word marks the statement as a write/DDL. \b will not
  ;; fire mid-identifier, so `updated_at` / `created_by` are not flagged.
  #"(?i)\b(insert|update|delete|drop|alter|truncate|create|grant|revoke|merge|replace|call|exec|execute|attach|detach|vacuum|reindex|copy|lock|comment|set|into)\b")

(defn- strip-trailing-semicolon [s]
  (str/replace s #";\s*$" ""))

(defn sql-violation
  "Why `sql` is not an acceptable read-only single statement, or nil if it is.
   Reasons: :empty | :multiple-statements | :not-read-only."
  [sql]
  (let [s (some-> sql str/trim)]
    (cond
      (str/blank? s)                                    :empty
      ;; After dropping one optional trailing `;`, a remaining `;` with content
      ;; after it means a second statement was smuggled in.
      (re-find #";\s*\S" (strip-trailing-semicolon s))  :multiple-statements
      (not (re-find read-leading-re s))                 :not-read-only
      (re-find write-token-re s)                        :not-read-only
      :else                                             nil)))

(defn read-only-sql?
  "True when `sql` is a single read-only statement."
  [sql]
  (nil? (sql-violation sql)))

(defn clamp-limit
  "Clamp a requested row limit into [1, max-row-limit]; nil/non-numeric falls
   back to default-row-limit."
  [requested]
  (let [n (cond
            (int? requested)    requested
            (string? requested) (parse-long requested)
            :else               nil)]
    (-> (or n default-row-limit) (max 1) (min max-row-limit))))

;; --- run-migration: direction validation ------------------------------------

(def migration-directions
  "Migration actions the tool exposes. Deliberately conservative: `up` applies
   pending migrations, `status` reports state. Destructive rollbacks are not
   exposed through the MCP surface."
  #{"up" "status"})

(defn valid-direction?
  "True when `direction` is a supported migration action."
  [direction]
  (contains? migration-directions direction))
