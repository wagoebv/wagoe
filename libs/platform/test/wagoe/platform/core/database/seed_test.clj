(ns wagoe.platform.core.database.seed-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.platform.core.database.seed :as seed]))

(deftest ^:unit validate-seed-accepts-well-formed-data
  (testing "a map of table -> uniform rows is valid"
    (let [data {:tasks [{:title "a" :done false}
                        {:title "b" :done true}]}]
      (is (= {:ok data} (seed/validate-seed data))))))

(deftest ^:unit validate-seed-rejects-bad-shapes
  (testing "non-map input"
    (is (= :validation-error (get-in (seed/validate-seed [1 2 3]) [:error :type]))))

  (testing "empty file — nothing to do, and silence would look like success"
    (is (= :validation-error (get-in (seed/validate-seed {}) [:error :type]))))

  (testing "rows must be sequential"
    (is (= :validation-error
           (get-in (seed/validate-seed {:tasks {:title "a"}}) [:error :type]))))

  (testing "a table with no rows is a mistake, not a no-op"
    (is (= :validation-error
           (get-in (seed/validate-seed {:tasks []}) [:error :type]))))

  (testing "every row must be a map"
    (is (= :validation-error
           (get-in (seed/validate-seed {:tasks [{:title "a"} "nope"]}) [:error :type]))))

  (testing "ragged rows are rejected — the missing key would insert NULL silently"
    (let [result (seed/validate-seed {:tasks [{:title "a" :done false}
                                              {:title "b"}]})]
      (is (= :validation-error (get-in result [:error :type])))
      (is (= :tasks (get-in result [:error :table]))))))

(deftest ^:unit row-and-table-names-convert-to-snake-case
  (testing "column keys"
    (is (= {:created_at "x" :user_id 1}
           (seed/row->columns {:created-at "x" :user-id 1}))))

  (testing "table names"
    (is (= "audit_logs" (seed/table->name :audit-logs)))
    (is (= "tasks" (seed/table->name :tasks)))))

(deftest ^:unit a-keyword-value-is-stored-as-its-name
  ;; A workflow state is written :delivered, and HoneySQL reads a keyword
  ;; value as a column name (BOU-578).
  (is (= {:status "delivered" :kind "a/b" :n 1}
         (seed/row->columns {:status :delivered :kind :a/b :n 1}))))

(deftest ^:unit ordered-vector-form-is-accepted-and-kept-in-order
  (testing "a vector of [table rows] pairs validates"
    (let [data [[:users [{:email "a@b.c"}]]
                [:tasks [{:title "t" :user-id 1}]]]]
      (is (= {:ok data} (seed/validate-seed data)))
      (is (= ["users" "tasks"] (mapv :table (seed/seed-plan data))))))

  (testing "pairs must be pairs"
    (is (= :validation-error
           (get-in (seed/validate-seed [[:users]]) [:error :type])))))

(deftest ^:unit large-maps-are-rejected-rather-than-silently-reordered
  ;; Clojure reads =<8 pairs as a PersistentArrayMap (insertion order) and 9+ as
  ;; a PersistentHashMap (hash order). A seed file that crosses that line would
  ;; otherwise start inserting children before parents with no warning.
  (let [rows  [{:a 1}]
        mk    (fn [n] (into {} (for [i (range n)] [(keyword (format "t%02d" i)) rows])))]
    (testing "8 tables still round-trips in order"
      (let [data (mk 8)]
        (is (nil? (:error (seed/validate-seed data))))
        (is (= (mapv (comp seed/table->name key) data)
               (mapv :table (seed/seed-plan data))))))

    (testing "9 tables is refused, with the ordered form named in the message"
      (let [result (seed/validate-seed (mk 9))]
        (is (= :validation-error (get-in result [:error :type])))
        (is (re-find #"do not keep their written order" (get-in result [:error :message])))
        (is (re-find #"\[\[:users" (get-in result [:error :message])))))

    (testing "the same 9 tables are fine in the ordered form"
      (let [data (mapv (fn [i] [(keyword (format "t%02d" i)) rows]) (range 9))]
        (is (nil? (:error (seed/validate-seed data))))
        (is (= 9 (count (seed/seed-plan data))))))))

;; =============================================================================
;; Defaults and symbolic ids (BOU-588)
;; =============================================================================

(def ^:private tables
  {"invoices"   {:columns #{"id" "number" "created_at" "updated_at"} :uuid-id? true}
   "line_items" {:columns #{"id" "invoice_id" "description" "created_at"} :uuid-id? true}
   "tags"       {:columns #{"id" "name"} :uuid-id? false}})

(defn- resolve-with [data]
  (let [n (volatile! 0)]
    (seed/resolve-seed data {:tables tables
                             :now    "2026-09-28T10:00:00Z"
                             :new-id #(str "id-" (vswap! n inc))})))

(deftest ^:unit rows-without-ids-or-timestamps-get-them
  (let [{:keys [ok error]} (resolve-with [[:invoices [{:number "A"} {:number "B"}]]])]
    (is (nil? error))
    (is (= [[:invoices [{:number "A" :id "id-1" :created-at "2026-09-28T10:00:00Z" :updated-at "2026-09-28T10:00:00Z"}
                        {:number "B" :id "id-2" :created-at "2026-09-28T10:00:00Z" :updated-at "2026-09-28T10:00:00Z"}]]]
           ok)))
  (testing "only the columns the table has"
    (is (= [[:line-items [{:description "x" :id "id-1" :created-at "2026-09-28T10:00:00Z"}]]]
           (:ok (resolve-with [[:line-items [{:description "x"}]]])))))
  (testing "an integer id is left to the database"
    (is (= [[:tags [{:name "t"}]]] (:ok (resolve-with [[:tags [{:name "t"}]]])))))
  (testing "what the row gives wins"
    (is (= [[:invoices [{:id "given" :number "A" :created-at "then" :updated-at "2026-09-28T10:00:00Z"}]]]
           (:ok (resolve-with [[:invoices [{:id "given" :number "A" :created-at "then"}]]]))))))

(deftest ^:unit a-child-refers-to-its-parent-by-a-symbolic-id
  (let [{:keys [ok error]} (resolve-with [[:invoices [{:id :invoice/acme :number "A"}
                                                      {:id :invoice/globex :number "B"}]]
                                          [:line-items [{:invoice-id :invoice/globex :description "x"}
                                                        {:invoice-id :invoice/acme :description "y"}]]])
        [[_ invoices] [_ items]] ok]
    (is (nil? error))
    (is (= ["id-1" "id-2"] (map :id invoices)))
    (is (= ["id-2" "id-1"] (map :invoice-id items)))
    (is (= ["id-3" "id-4"] (map :id items))))
  (testing "a workflow state and other keywords are left alone"
    (is (= :paid (-> (resolve-with [[:invoices [{:id :invoice/a :number :paid}]]]) :ok first second first :number))))
  (testing "a qualified keyword nobody declared is left alone"
    (is (= :a/b (-> (resolve-with [[:invoices [{:number :a/b}]]]) :ok first second first :number))))
  (testing "a mistyped reference is refused"
    (is (re-find #":invoice/acmee"
                 (get-in (resolve-with [[:invoices [{:id :invoice/acme :number "A"}]]
                                        [:line-items [{:invoice-id :invoice/acmee :description "x"}]]])
                         [:error :message])))))

(deftest ^:unit a-symbolic-id-is-refused-where-it-cannot-work
  (doseq [[why data] [["unqualified"         [[:invoices [{:id :acme :number "A"}]]]]
                      ["declared twice"      [[:invoices [{:id :invoice/a :number "A"} {:id :invoice/a :number "B"}]]]]
                      ["not a uuid id"       [[:tags [{:id :tag/a :name "t"}]]]]
                      ["some rows, not all"  [[:tags [{:id 1 :name "t"} {:name "u"}]]]]]]
    (testing why
      (is (= :validation-error (get-in (resolve-with data) [:error :type]))))))

(deftest ^:unit rows-may-leave-out-what-is-defaulted
  (is (nil? (:error (seed/validate-seed {:invoices [{:id :invoice/a :number "A"} {:number "B" :created-at "x"}]})))))

(deftest ^:unit seed-plan-preserves-file-order
  (testing "parents can be listed before children, and that order is kept"
    (let [plan (seed/seed-plan (array-map :users [{:email "a@b.c"}]
                                          :tasks [{:title "t" :user-id 1}]))]
      (is (= ["users" "tasks"] (mapv :table plan)))
      (is (= [1 1] (mapv :count plan)))
      (is (= [{:email "a@b.c"}] (:rows (first plan))))
      (is (= [{:title "t" :user_id 1}] (:rows (second plan)))))))
