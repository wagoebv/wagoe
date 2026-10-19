(ns wagoe.ai.core.sql-copilot
  "The schema the SQL copilot is shown, and the check its answer must pass.

   Without the project's tables the model guessed every name from the English
   description (BOU-513). It now sees the tables and columns the migrations
   create, and an answer naming anything else is refused."
  (:require [wagoe.core.utils.text :as text]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [honey.sql :as sql]))

;; =============================================================================
;; Schema rendering
;; =============================================================================

(def max-schema-chars
  "Budget for the schema in the prompt. A large project's migrations run to
   hundreds of tables; the ones the description names are sent first."
  12000)

(def ^:private constraint-words
  #{"NOT" "NULL" "DEFAULT" "PRIMARY" "REFERENCES" "UNIQUE" "CHECK" "GENERATED"
    "CONSTRAINT" "COLLATE" "AUTO_INCREMENT" "AUTOINCREMENT" "ON"})

(defn- bare-type
  "\"VARCHAR(255) NOT NULL DEFAULT ''\" -> \"VARCHAR(255)\"."
  [sql-type]
  (->> (str/split (str/trim (str sql-type)) #"\s+")
       (take-while #(not (constraint-words (text/upper-case %))))
       (str/join " ")))

(defn- relevance
  "How many of the table name's words the description mentions, singular or
   plural."
  [d table]
  (count (filter #(str/includes? d (str/replace % #"s$" ""))
                 (remove str/blank? (str/split table #"_")))))

(defn incomplete
  "`cols` marked as possibly missing columns, as for a table a Clojure
   migration may alter: its listed columns are shown, others are not refused."
  [cols]
  (vary-meta cols assoc ::incomplete true))

(defn- incomplete? [cols] (::incomplete (meta cols)))

(defn render-schema
  "`tables`, {table {column sql-type}}, as one line per table, those
   `description` names first, cut off at `max-chars`. A table whose columns
   are nil is known by name only. nil when there are none."
  ([tables description] (render-schema tables description max-schema-chars))
  ([tables description max-chars]
   (when (seq tables)
     (let [d     (text/lower-case (str description))
           lines (->> tables
                      (map (fn [[t _ :as e]] [[(- (relevance d t)) t] e]))
                      (sort-by first)
                      (map second)
                      (map (fn [[t cols]]
                             (str t ": " (if (nil? cols)
                                           "(columns not listed)"
                                           (cond-> (str/join ", " (map (fn [[c ty]] (str/trim (str c " " (bare-type ty))))
                                                                       (sort-by key cols)))
                                             (incomplete? cols) (str ", (and columns added in code)")))))))
           kept  (reduce (fn [acc line]
                           (if (> (+ (:n acc) (count line) 1) max-chars)
                             (reduced acc)
                             (-> acc (update :lines conj line) (update :n + (count line) 1))))
                         {:lines [] :n 0}
                         lines)
           left  (- (count tables) (count (:lines kept)))]
       (str/join "\n" (cond-> (:lines kept)
                        (pos? left) (conj (str "(" left " more tables not shown)"))))))))

;; =============================================================================
;; Answer check
;; =============================================================================

(def ^:private token-re
  #"'(?:[^']|'')*'|\"(?:[^\"]|\"\")*\"(?:\.(?:\"(?:[^\"]|\"\")*\"|\*))*|\w+|\S")

(def ^:private clause-words
  #{"SELECT" "FROM" "JOIN" "WHERE" "ON" "USING" "GROUP" "ORDER" "HAVING" "LIMIT"
    "OFFSET" "UPDATE" "INTO" "SET" "VALUES" "WITH" "RETURNING" "UNION"})

(def ^:private table-clauses #{"FROM" "JOIN" "UPDATE" "INTO" "USING"})

(defn- ident-parts
  "[\"t\" \"col\"] for the token \"t\".\"col\", nil for a token that is not a
   quoted identifier."
  [token]
  (when (str/starts-with? token "\"")
    (mapv #(-> % (str/replace #"^\"|\"$" "") (str/replace "\"\"" "\"") text/lower-case)
          (re-seq #"\"(?:[^\"]|\"\")*\"|\*" token))))

(defn- classify
  "Every quoted identifier in `sql-str` as {:kind :table|:alias|:cte|:column
   :parts [..]}, read from where it stands: after FROM or JOIN a table, after
   AS an alias, before AS ( a CTE. A parenthesis opens a scope of its own, so
   an INSERT's column list or the list after a subquery is not read as tables."
  [sql-str]
  (let [tokens (vec (re-seq token-re sql-str))
        up     #(some-> (get tokens %) text/upper-case)]
    (loop [i 0 clause nil outer () out []]
      (if (>= i (count tokens))
        out
        (let [tok   (tokens i)
              word  (up i)
              parts (ident-parts tok)]
          (cond
            (= "(" tok) (recur (inc i) nil (conj outer clause) out)

            (= ")" tok) (recur (inc i) (peek outer) (if (seq outer) (pop outer) outer) out)

            (clause-words word) (recur (inc i) word outer out)

            (nil? parts) (recur (inc i) clause outer out)

            (= "AS" (up (dec i)))
            (recur (inc i) clause outer
                   (conj out {:kind  :alias :parts parts
                              :of    (when (table-clauses clause)
                                       (some-> (get tokens (- i 2)) ident-parts peek))}))

            (and (= "AS" (up (inc i))) (= "(" (get tokens (+ i 2))))
            (recur (inc i) clause outer (conj out {:kind :cte :parts parts}))

            (and (table-clauses clause) (= 1 (count parts))
                 (or (table-clauses (up (dec i))) (= "," (get tokens (dec i)))))
            (recur (inc i) clause outer (conj out {:kind :table :parts parts}))

            :else (recur (inc i) clause outer (conj out {:kind :column :parts parts}))))))))

(defn unknown-identifiers
  "The tables and columns `sql-str` names that `tables`, {table {column type}},
   does not have, as messages. Not checked: the columns of a CTE, of a
   subquery, or of a table whose columns are nil."
  [sql-str tables]
  (let [ids      (classify sql-str)
        of-kind  (fn [k] (filter #(= k (:kind %)) ids))
        ctes     (set (map (comp first :parts) (of-kind :cte)))
        used     (set (map (comp first :parts) (of-kind :table)))
        alias-of (into {} (map (fn [a] [(first (:parts a)) (:of a)])) (of-kind :alias))
        known?   #(contains? tables %)
        has-col? (fn [cols c] (or (nil? cols) (incomplete? cols) (contains? cols c)))
        ;; A subquery's columns are its own; only check that they exist somewhere.
        derived? (or (seq ctes) (str/includes? sql-str "(SELECT"))
        col-in?  (fn [c ts] (some #(has-col? (get tables %) c) ts))]
    (->> (concat
          (for [t used :when (not (or (known? t) (ctes t)))]
            (str "table " t " does not exist"))
          (for [{:keys [parts]} (of-kind :column)
                :let [c (peek parts) q (when (> (count parts) 1) (first parts))
                      t (if (contains? alias-of q) (alias-of q) q)]
                :when (not= "*" c)]
            (cond
              (nil? q)
              (when-not (or (col-in? c (filter known? used)) (contains? alias-of c)
                            (and derived? (col-in? c (keys tables))))
                (str "column " c " is in none of " (str/join ", " (sort used))))

              (or (ctes q) (and (contains? alias-of q) (nil? t)))
              nil

              (not (known? t))
              (when-not (used t) (str "table " t " does not exist"))

              (not (used t))
              (str "table " t " is not in the query's FROM or JOIN")

              (not (has-col? (tables t) c))
              (str "column " t "." c " does not exist"))))
         (remove nil?)
         distinct
         vec)))

(defn check-answer
  "Read the model's HoneySQL `edn-str` and check it against `tables`.

   Returns {:sql str}, the query as HoneySQL renders it, or {:errors [str]}."
  [edn-str tables]
  (let [query (try (edn/read-string edn-str) (catch Exception _ nil))]
    (if-not (map? query)
      {:errors ["the honeysql value is not a readable EDN map"]}
      (let [[quoted failure] (try [(first (sql/format query {:quoted true :quoted-snake true :inline true}))]
                                  (catch Exception e [nil (or (ex-message e) (str e))]))
            errors (if failure
                     [(str "HoneySQL cannot format it: " failure)]
                     (unknown-identifiers quoted tables))]
        (if (seq errors)
          {:errors errors}
          {:sql (first (sql/format query {:inline true}))})))))
