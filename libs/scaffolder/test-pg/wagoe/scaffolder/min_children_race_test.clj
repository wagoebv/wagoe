(ns wagoe.scaffolder.min-children-race-test
  "Two deletes of an invoice's last two line items, at once, with `--min 1`:
   exactly one is refused, on every database a project can use (BOU-578). The
   count and the delete are one transaction, holding the invoice's row."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.scaffolder.cli :as cli]
            [wagoe.scaffolder.multi-entity-test :as multi]
            [wagoe.scaffolder.shell.service :as service])
  (:import [io.zonky.test.db.postgres.embedded EmbeddedPostgres]
           [java.util.concurrent CountDownLatch]))

(def ^:private svc (service/create-scaffolder-service))

(defn- module!
  "Module billing under `base`: Invoice, and line items with --min 1. Loaded."
  [base]
  (let [dir (#'multi/temp-dir)
        run #(with-out-str (cli/run-cli! svc (into % ["--base-ns" base "--output-dir" (.getPath dir)])))]
    (run ["generate" "--module-name" "billing" "--entity" "Invoice" "--field" "number:string:required"])
    (run ["entity" "--module-name" "billing" "--entity" "InvoiceLineItem" "--belongs-to" "invoice"
          "--min" "1" "--field" "description:string:required"])
    (#'multi/load-and-test! dir)
    dir))

(defn- statements [sql]
  (->> (str/split sql #"--;;")
       (map #(str/join "\n" (remove (fn [l] (str/starts-with? (str/trim l) "--")) (str/split-lines %))))
       (map str/trim)
       (remove str/blank?)))

(defn- backends
  "[label make], where `make` returns [db-ctx stop!]."
  []
  [["h2" (fn [] (let [ctx (db-factory/db-context {:adapter :h2 :database-path (str "mem:race" (System/nanoTime))
                                                  :pool {:minimum-idle 1 :maximum-pool-size 4}})]
                  [ctx #(db-factory/close-db-context! ctx)]))]
   ["sqlite" (fn [] (let [f   (java.io.File/createTempFile "race" ".db")
                          ctx (db-factory/db-context {:adapter :sqlite :database-path (.getPath f)
                                                      :pool {:minimum-idle 1 :maximum-pool-size 4}})]
                      [ctx #(do (db-factory/close-db-context! ctx) (.delete f))]))]
   ["postgresql" (fn [] (let [pg  (.start (EmbeddedPostgres/builder))
                              ctx (db-factory/db-context {:adapter :postgresql :host "localhost"
                                                          :port (.getPort pg) :name "postgres"
                                                          :username "postgres" :password "postgres"
                                                          :pool {:minimum-idle 1 :maximum-pool-size 4}})]
                          [ctx #(do (db-factory/close-db-context! ctx) (.close pg))]))]])

(defn- slowly-counted
  "Call `f` with the count slowed down, so two deletes that count before either
   deletes both see two line items unless the first holds the invoice."
  [f]
  (let [v     (ns-resolve 'bou578r.billing.ports 'count-invoice-line-items-by-invoice)
        count @v]
    (with-redefs-fn {v (fn [repo id] (let [n (count repo id)] (Thread/sleep 300) n))} f)))

(deftest ^:integration two-deletes-of-the-last-line-items-at-once-leave-one
  (let [dir (module! "bou578r")
        at  (fn [n s] @(ns-resolve (symbol (str "bou578r.billing." n)) s))]
    (doseq [[label make] (backends)]
      (testing label
        (let [[ctx stop!] (make)]
          (try
            (doseq [[path sql] (#'multi/files-under dir)
                    :when (str/ends-with? path ".up.sql")
                    s (statements sql)]
              (jdbc/execute! (:datasource ctx) [s]))
            (let [line-repo ((at "shell.invoice-line-item-persistence" 'create-repository) ctx)
                  lines     ((at "shell.invoice-line-item-service" 'create-service) line-repo)
                  invoices  ((at "shell.service" 'create-service)
                             ((at "shell.persistence" 'create-repository) ctx)
                             {:invoice-line-items {:foreign-key :invoice-id
                                                   :create #((at "ports" 'create-invoice-line-item) lines %)}})
                  inv       ((at "ports" 'create-invoice) invoices
                                                          {:number "A-1" :invoice-line-items [{:description "a"} {:description "b"}]})
                  go        (CountDownLatch. 1)
                  del       (fn [line] (future (.await go)
                                               (try ((at "ports" 'delete-invoice-line-item) lines (:id line)) :deleted
                                                    (catch Exception e (or (:type (ex-data e)) e)))))
                  results   (slowly-counted
                             #(let [rs (mapv del (:invoice-line-items inv))]
                                (.countDown go)
                                (mapv deref rs)))]
              (is (= #{:deleted :conflict} (set results)) (pr-str results))
              (is (= 1 (count ((at "ports" 'list-invoice-line-items) lines {})))))
            (finally (stop!))))))))
