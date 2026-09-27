(ns wagoe.admin.shell.delete-children-test
  "Deleting a record with has-many children (BOU-563): hard delete is the
   default whatever the columns, children follow their parent, and a has-many
   `:min` refuses to delete the last child."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.http.handlers.delete :as delete]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.shell.service :as service]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]))

^{:kaocha.testable/meta {:integration true :admin true}}

(def ^:private lines-rel
  {:entity :dc-lines :table :dc_lines :foreign-key :dc-invoice-id
   :label "Lines" :fields [:description] :editable true})

(defn- config [invoice-cfg & [lines-cfg]]
  {:entity-discovery {:mode :allowlist :allowlist #{:dc-invoices :dc-lines}}
   :entities         {:dc-invoices (merge {:label "Invoices" :has-many [lines-rel]} invoice-cfg)
                      :dc-lines    (merge {:label "Lines"} lines-cfg)}
   :pagination       {:default-page-size 20 :max-page-size 200}})

(def ^:private ^:dynamic *db* nil)

(use-fixtures :once
  (fn [f]
    (let [ctx (db-factory/db-context {:adapter       :h2
                                      :database-path "mem:admin_delete_children;DB_CLOSE_DELAY=-1"
                                      :pool          {:minimum-idle 1 :maximum-pool-size 3}})]
      (db/execute-update! ctx {:raw "CREATE TABLE dc_invoices (id UUID PRIMARY KEY, number VARCHAR(20) NOT NULL,
                                                              deleted_at TIMESTAMP)"})
      ;; No ON DELETE CASCADE: the admin removes the children itself.
      (db/execute-update! ctx {:raw "CREATE TABLE dc_lines (id UUID PRIMARY KEY,
                                                           dc_invoice_id UUID NOT NULL REFERENCES dc_invoices(id),
                                                           description VARCHAR(50) NOT NULL,
                                                           deleted_at TIMESTAMP)"})
      (try (binding [*db* ctx] (f))
           (finally
             (db/execute-update! ctx {:raw "DROP TABLE dc_lines"})
             (db/execute-update! ctx {:raw "DROP TABLE dc_invoices"})
             (db-factory/close-db-context! ctx))))))

(use-fixtures :each
  (fn [f]
    (db/execute-update! *db* {:raw "DELETE FROM dc_lines"})
    (db/execute-update! *db* {:raw "DELETE FROM dc_invoices"})
    (f)))

(defn- svc [cfg]
  (let [sp (schema-repo/create-schema-repository *db* cfg)]
    {:sp sp :svc (service/create-admin-service *db* sp nil nil cfg)}))

(defn- invoice! [& line-count]
  (let [id (random-uuid)]
    (db/execute-update! *db* {:raw (str "INSERT INTO dc_invoices (id, number) VALUES ('" id "', 'INV')")})
    {:id    id
     :lines (vec (for [n (range (or (first line-count) 2))]
                   (let [line (random-uuid)]
                     (db/execute-update! *db* {:raw (str "INSERT INTO dc_lines (id, dc_invoice_id, description) VALUES ('"
                                                         line "', '" id "', 'line " n "')")})
                     line)))}))

(defn- row [table id]
  (db/execute-one! *db* {:select [:*] :from [table] :where [:= :id (str id)]}))

(deftest ^:integration hard-delete-is-the-default
  (testing "a table with deleted_at is not soft-deleted unless the config says so"
    (let [{:keys [sp svc]} (svc (config {}))
          {:keys [id lines]} (invoice!)]
      (is (false? (:soft-delete (ports/get-entity-config sp :dc-invoices))))
      (is (true? (ports/delete-entity svc :dc-invoices id)))
      (is (nil? (row :dc_invoices id)) "the parent row is gone")
      (testing "and its children go with it"
        (is (every? nil? (map #(row :dc_lines %) lines)))))))

(deftest ^:integration a-soft-deleted-parent-soft-deletes-its-children
  (let [{:keys [svc]} (svc (config {:soft-delete true} {:soft-delete true}))
        {:keys [id lines]} (invoice!)
        other (invoice!)]
    (is (true? (ports/delete-entity svc :dc-invoices id)))
    (is (some? (:deleted-at (row :dc_invoices id))))
    (is (every? #(some? (:deleted-at (row :dc_lines %))) lines))
    (testing "another parent's children are untouched"
      (is (every? #(nil? (:deleted-at (row :dc_lines %))) (:lines other))))
    (testing "the child list no longer shows them"
      (is (= 2 (:total-count (ports/list-entities svc :dc-lines {})))))))

(deftest ^:integration bulk-delete-takes-the-children-too
  (let [{:keys [svc]} (svc (config {}))
        a (invoice!)
        b (invoice!)]
    (is (= 2 (:success-count (ports/bulk-delete-entities svc :dc-invoices [(:id a) (:id b)]))))
    (is (every? nil? (map #(row :dc_lines %) (concat (:lines a) (:lines b)))))))

(def ^:private min-one (config {:has-many [(assoc lines-rel :min 1)]}))

(deftest ^:integration a-has-many-min-refuses-to-delete-the-last-child
  (let [{:keys [svc]} (svc min-one)
        {:keys [id lines]} (invoice!)]
    (testing "a child above the minimum can go"
      (is (true? (ports/delete-entity svc :dc-lines (first lines)))))
    (testing "the last one cannot"
      (let [e (try (ports/delete-entity svc :dc-lines (second lines)) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :conflict (:type (ex-data e))))
        (is (some? (row :dc_lines (second lines))))))
    (testing "deleting the parent still takes every child"
      (is (true? (ports/delete-entity svc :dc-invoices id)))
      (is (nil? (row :dc_lines (second lines)))))))

(deftest ^:integration a-has-many-min-refuses-a-bulk-delete-that-would-cross-it
  (let [{:keys [svc]} (svc min-one)
        {:keys [lines]} (invoice! 2)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"at least 1"
                          (ports/bulk-delete-entities svc :dc-lines lines)))
    (is (every? some? (map #(row :dc_lines %) lines)) "nothing was deleted")))

(deftest ^:integration the-delete-handler-answers-a-refusal-with-409
  (let [{:keys [sp svc]} (svc min-one)
        {:keys [lines]} (invoice! 1)
        admin  {:id (random-uuid) :role :admin :active true}
        resp   ((delete/delete-entity-handler svc sp min-one)
                {:request-method :delete :user admin :session {:user admin} :headers {}
                 :path-params {:entity "dc-lines" :id (str (first lines))}})]
    (is (= 409 (:status resp)))
    (is (str/includes? (get-in resp [:headers "HX-Trigger"]) "showToast"))
    (is (some? (row :dc_lines (first lines))))))
