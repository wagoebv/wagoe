(ns wagoe.scaffolder.field-default-test
  "`--field status:enum:values=entered,paid:required:default=entered` — a
   column default.

   A required column the admin marks `:readonly-fields` is left out of the
   create form, so the insert omits it and NOT NULL refuses the row. The
   database default is what keeps it creatable (BOU-494)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.cli]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [wagoe.scaffolder.cli :as cli]
            [wagoe.scaffolder.core.generators :as gen]
            [wagoe.scaffolder.core.template :as template]
            [wagoe.scaffolder.schema :as schema]))

(defn- migration-for [& fields]
  (gen/generate-migration-file
   (template/build-module-context
    {:module-name "orders" :base-ns "app"
     :entities    [{:name "Order" :fields (vec fields)}]})
   "20260926000000"))

;; =============================================================================
;; The field spec
;; =============================================================================

(deftest ^:unit default-is-a-field-spec-modifier
  (testing "default= is parsed next to the other modifiers"
    (is (= {:name :status :type :enum :required true :unique false
            :enum-values [:entered :paid] :default "entered"}
           (cli/parse-field-spec "status:enum:values=entered,paid:required:default=entered"))))

  (testing "a value that does not suit the type is refused, not pasted into DDL"
    (doseq [spec ["qty:int:default=many"
                  "price:decimal:default=cheap"
                  "active:boolean:default=yes"
                  "status:enum:values=entered,paid:default=lost"
                  "invoice:relation:references=invoice:default=x"
                  "token:uuid:default=not-a-uuid"
                  "due:datetime:default=tomorrow"
                  "due:datetime:default=2026-13-45T00:00:00Z"
                  "meta:json:default={}"]]
      (is (:error (cli/parse-field-spec spec)) spec)))

  (testing "a default may contain colons"
    (is (= "https://example.org"
           (:default (cli/parse-field-spec "link:string:default=https://example.org"))))
    (is (= "2026-01-01T00:00:00Z"
           (:default (cli/parse-field-spec "due:datetime:default=2026-01-01T00:00:00Z"))))
    (is (= {:default "https://example.org" :required true :unique true}
           (select-keys (cli/parse-field-spec "link:string:default=https://example.org:required:unique")
                        [:default :required :unique]))
        "modifiers after the default are still modifiers"))

  (testing "an unquoted default swallows only the pieces that are not modifiers"
    (is (= {:default "a" :unique true}
           (select-keys (cli/parse-field-spec "note:string:default=a:unique")
                        [:default :unique]))))

  (testing "a quoted default is literal, colons and modifier words included"
    (is (= {:default "a:unique" :unique false :required true}
           (select-keys (cli/parse-field-spec "note:string:default='a:unique':required")
                        [:default :unique :required])))
    (is (= "https://x.org:8080"
           (:default (cli/parse-field-spec "link:string:default='https://x.org:8080'"))))
    (is (:error (cli/parse-field-spec "note:string:default='a:unique"))
        "an unclosed quote is refused, not kept as part of the value"))

  (testing "a datetime default needs an offset: a bare date resolves in the session's zone"
    (let [{:keys [error]} (cli/parse-field-spec "due:datetime:default=2026-01-01")]
      (is (str/includes? (str error) "offset") (pr-str error)))
    (is (not (m/validate schema/FieldDefinition {:name :due :type :inst :default "2026-01-01"})))
    (is (= "'2026-01-01'" (template/default-literal {:type :date} "2026-01-01"))
        "a DATE column still takes a date"))

  (testing "`date` is a DATE, and takes a bare date as its default (BOU-547)"
    (is (= {:name :due :type :date :required false :unique false :default "2026-01-01"}
           (cli/parse-field-spec "due:date:default=2026-01-01")))
    (is (str/includes? (migration-for (cli/parse-field-spec "due:date")) "due DATE"))
    (is (= :inst (:type (cli/parse-field-spec "at:datetime"))) "datetime stays an instant"))

  (testing "a well-formed uuid default is accepted"
    (is (= "00000000-0000-0000-0000-000000000000"
           (:default (cli/parse-field-spec "token:uuid:default=00000000-0000-0000-0000-000000000000"))))))

(deftest ^:unit the-field-command-takes-a-default
  (let [{:keys [options errors]} (clojure.tools.cli/parse-opts
                                  ["--module-name" "orders" "--entity" "Order"
                                   "--name" "qty" "--type" "int" "--default" "many"]
                                  cli/field-options)]
    (is (nil? errors))
    (let [[ok? errs] (cli/validate-field-options options)]
      (is (false? ok?))
      (is (some #(str/includes? % "--default") errs) (pr-str errs))))
  (testing "--type date takes a bare date (BOU-547)"
    (let [{:keys [options]} (clojure.tools.cli/parse-opts
                             ["--module-name" "orders" "--entity" "Order"
                              "--name" "due" "--type" "date" "--default" "2026-01-01"]
                             cli/field-options)]
      (is (= [true []] (cli/validate-field-options options))))))

(deftest ^:unit the-schema-refuses-a-default-that-does-not-suit-the-type
  ;; The MCP tool and direct callers skip the CLI parser; generate-module
  ;; validates against this schema.
  (is (m/validate schema/FieldDefinition {:name :qty :type :int :default 3}))
  (is (m/validate schema/FieldDefinition {:name :qty :type :int :default "3"}))
  (is (not (m/validate schema/FieldDefinition {:name :qty :type :int :default "many"})))
  (is (not (m/validate schema/FieldDefinition {:name :s :type :enum :enum-values [:a]
                                               :default "b"}))))

;; =============================================================================
;; The migration
;; =============================================================================

(deftest ^:unit the-migration-quotes-the-default-by-type
  (testing "strings and enums are quoted"
    (is (str/includes? (migration-for {:name :note :type :string :required false :default "n/a"})
                       "note VARCHAR(255) DEFAULT 'n/a'"))
    (is (str/includes? (migration-for {:name :status :type :enum :enum-values [:entered :paid]
                                       :required true :default "paid"})
                       "status VARCHAR(50) DEFAULT 'paid' NOT NULL")))

  (testing "a quote in the value is escaped"
    (is (str/includes? (migration-for {:name :note :type :string :required false :default "it's"})
                       "DEFAULT 'it''s'")))

  (testing "numbers and booleans are not"
    (is (str/includes? (migration-for {:name :qty :type :int :required true :default "3"})
                       "qty INTEGER DEFAULT 3 NOT NULL"))
    (is (str/includes? (migration-for {:name :price :type :decimal :required true :default "9.95"})
                       "DEFAULT 9.95 NOT NULL"))
    (is (str/includes? (migration-for {:name :active :type :boolean :required true :default "false"})
                       "active BOOLEAN DEFAULT false NOT NULL")))

  (testing "no default, no DEFAULT"
    (is (not (str/includes? (migration-for {:name :note :type :string :required true})
                            "DEFAULT")))))

(deftest ^:unit a-required-enum-defaults-to-its-first-value
  (is (str/includes? (migration-for {:name :status :type :enum
                                     :enum-values [:entered :paid] :required true})
                     "status VARCHAR(50) DEFAULT 'entered' NOT NULL"))
  (testing "but not when it is optional — null is its default"
    (is (not (str/includes? (migration-for {:name :status :type :enum
                                            :enum-values [:entered :paid] :required false})
                            "DEFAULT")))))

(deftest ^:unit adding-a-field-carries-its-default
  (is (str/includes? (gen/generate-add-field-migration
                      "orders" "Order"
                      {:name :status :type :enum :enum-values [:entered :paid]
                       :required true :default "paid"}
                      "1")
                     "ADD COLUMN status VARCHAR(50) DEFAULT 'paid' NOT NULL;")))

;; =============================================================================
;; The promise
;; =============================================================================

(deftest ^:integration an-insert-that-omits-a-required-defaulted-column-succeeds
  ;; What the admin's create form does with a :readonly-fields column.
  (let [ds  (jdbc/get-datasource {:jdbcUrl (str "jdbc:h2:mem:dflt" (System/nanoTime) ";DB_CLOSE_DELAY=-1")})
        sql (migration-for {:name :reference :type :string :required true}
                           {:name :status :type :enum :enum-values [:entered :paid] :required true}
                           {:name :qty :type :int :required true :default 1}
                           {:name :token :type :uuid :required true
                            :default "00000000-0000-0000-0000-000000000001"}
                           {:name :due :type :inst :required true
                            :default "2026-01-01T00:00:00Z"})]
    (jdbc/execute! ds [(first (str/split sql #";"))])
    (jdbc/execute! ds ["INSERT INTO orders (id, reference, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)"
                       (random-uuid) "R-1"])
    (let [row (jdbc/execute-one! ds ["SELECT status, qty, token, due FROM orders"]
                                 {:builder-fn rs/as-unqualified-lower-maps})]
      (is (= {:status "entered" :qty 1 :token #uuid "00000000-0000-0000-0000-000000000001"}
             (select-keys row [:status :qty :token])))
      (is (= #inst "2026-01-01T00:00:00Z"
             (java.util.Date/from (.toInstant ^java.time.OffsetDateTime (:due row))))))))

;; =============================================================================
;; indexed, optional, and modifiers nobody knows (BOU-535)
;; =============================================================================

(deftest ^:unit indexed-writes-an-index
  (let [field (cli/parse-field-spec "sku:string:required:indexed")]
    (is (true? (:indexed field)))
    (testing "in the create migration"
      (is (str/includes? (migration-for field)
                         "CREATE INDEX IF NOT EXISTS idx_orders_sku ON orders(sku);")))
    (testing "in the add-field migration"
      (is (str/includes? (gen/generate-add-field-migration "orders" "Order" field "20260926000000")
                         "CREATE INDEX IF NOT EXISTS idx_orders_sku ON orders(sku);")))
    (testing "and only when asked for"
      (is (not (str/includes? (migration-for (cli/parse-field-spec "sku:string")) "idx_orders_sku"))))
    (testing "the field command takes --indexed"
      (is (true? (:indexed (:options (clojure.tools.cli/parse-opts ["--indexed"] cli/field-options))))))))

(deftest ^:integration an-indexed-field-migrates-on-h2-and-sqlite
  (let [sql (migration-for (cli/parse-field-spec "sku:string:indexed"))
        f   (java.io.File/createTempFile "bou535" ".db")]
    (try
      (doseq [ds [(jdbc/get-datasource {:jdbcUrl (str "jdbc:h2:mem:bou535" (System/nanoTime) ";DB_CLOSE_DELAY=-1")})
                  (jdbc/get-datasource {:jdbcUrl (str "jdbc:sqlite:" (.getPath f))})]
              s   (remove str/blank? (map str/trim (str/split sql #";")))
              :let [s (->> (str/split-lines s) (remove #(str/starts-with? (str/trim %) "--")) (str/join "\n"))]
              :when (not (str/blank? s))]
        (is (some? (jdbc/execute! ds [s])) s))
      (finally (.delete f)))))

(deftest ^:unit optional-is-the-default-and-says-so
  (is (false? (:required (cli/parse-field-spec "note:string:optional"))))
  (is (str/includes? (str (:error (cli/parse-field-spec "note:string:required:optional"))) "optional")
      "required and optional together contradict"))

(deftest ^:unit an-unknown-modifier-is-refused-by-name
  (doseq [spec ["note:string:requird" "note:string:required:bogus" "sku:string:index"]]
    (let [{:keys [error]} (cli/parse-field-spec spec)]
      (is (str/includes? (str error) (last (str/split spec #":"))) (str spec " -> " (pr-str error)))))
  (testing "a default's colons are still the default's"
    (is (= "a:b" (:default (cli/parse-field-spec "note:string:default=a:b"))))
    (is (= "a:unique" (:default (cli/parse-field-spec "note:string:default='a:unique':indexed"))))
    (is (true? (:indexed (cli/parse-field-spec "note:string:default='a:unique':indexed"))))))
