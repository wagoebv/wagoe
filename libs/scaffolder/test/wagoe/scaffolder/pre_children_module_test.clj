(ns wagoe.scaffolder.pre-children-module-test
  "A module generated before BOU-578 has a service without children: its
   `create-service` takes the repository alone. Adding an entity or a
   subscriber to it must leave a module that boots, and `--min`, which needs
   the children, is refused with the fix spelled out."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.walk :as walk]
            [integrant.core :as ig]
            [wagoe.events.shell.module-wiring]
            [wagoe.scaffolder.cli :as cli]
            [wagoe.scaffolder.multi-entity-test :as multi]
            [wagoe.scaffolder.shell.service :as service]))

(def ^:private svc (service/create-scaffolder-service))

(defn- old-service
  "shell/service.clj as `bb scaffold generate` wrote it before BOU-578."
  [base]
  (str "(ns " base ".billing.shell.service
  (:require [" base ".billing.ports :as ports]
            [" base ".billing.core.invoice :as core])
  (:import [java.time Instant]
           [java.util UUID]))

(defrecord InvoiceService [repository]
  ports/IInvoiceService
  (create-invoice [_this data]
    (ports/create repository (core/prepare-new-invoice data (UUID/randomUUID) (Instant/now))))
  (get-invoice [_this id]
    (ports/find-by-id repository id))
  (list-invoices [_this opts]
    (ports/find-all repository opts))
  (update-invoice [_this id data]
    (ports/update-entity repository (assoc data :id id)))
  (delete-invoice [_this id]
    (ports/delete repository id)))

(defn create-service [repository]
  (->InvoiceService repository))
"))

(defn- old-module! [base]
  (let [dir (#'multi/temp-dir)]
    (with-out-str (cli/run-cli! svc ["generate" "--module-name" "billing" "--entity" "Invoice"
                                     "--field" "number:string:required" "--base-ns" base
                                     "--output-dir" (.getPath dir)]))
    (spit (io/file dir "src" base "billing/shell/service.clj") (old-service base))
    dir))

(defn- scaffold! [dir base & args]
  (let [status (atom nil)
        out    (with-out-str (binding [*err* *out*]
                               (reset! status (cli/run-cli! svc (into (vec args) ["--base-ns" base "--output-dir" (.getPath dir)])))))]
    {:status @status :out out}))

(defn- boot! [dir base active]
  (#'multi/load-and-test! dir)
  (let [build (ns-resolve (symbol (str base ".billing.shell.module-wiring")) 'ig-config)
        graph (:components (build {:enabled? true} {:config {:active active}}))
        system (ig/init (merge (walk/postwalk-replace {(ig/ref :wagoe/db-context) :stand-in} graph)
                               {:wagoe/events {:provider :memory}}))]
    (ig/halt! system)
    system))

(deftest ^:integration an-entity-added-to-an-old-module-leaves-it-booting
  (let [base "bou578o"
        dir  (old-module! base)
        read #(slurp (io/file dir "src" base "billing/shell" %))
        before (mapv read ["service.clj" "http.clj"])
        {:keys [status out]} (scaffold! dir base "entity" "--module-name" "billing" "--entity" "InvoiceLineItem"
                                        "--belongs-to" "invoice" "--field" "description:string:required")]
    (is (= 0 status) out)
    (is (= before (mapv read ["service.clj" "http.clj"])) "its service and http files are left alone (BOU-581)")
    (is (str/includes? out "does not take invoice-line-items") "it says the create does not take them")
    (is (not (str/includes? (slurp (io/file dir "src" base "billing/schema.clj")) "[:invoice-line-items"))
        "and the create request does not offer them")
    (is (contains? (boot! dir base {}) :wagoe/billing-service))))

(deftest ^:integration a-subscriber-added-to-an-old-module-leaves-it-booting
  (let [base "bou578p"
        dir  (old-module! base)
        {:keys [status out]} (scaffold! dir base "subscriber" "--module-name" "billing"
                                        "--event" ":admin/entity-created")]
    (is (= 0 status) out)
    (is (contains? (boot! dir base {:wagoe/events {}}) :wagoe/billing-entity-created-subscriber))))

(deftest ^:unit a-minimum-on-an-old-module-is-refused-with-the-fix
  (let [base   "bou578q"
        dir    (old-module! base)
        before (#'multi/files-under dir)
        {:keys [status out]} (scaffold! dir base "entity" "--module-name" "billing" "--entity" "InvoiceLineItem"
                                        "--belongs-to" "invoice" "--min" "1" "--field" "description:string:required")]
    (is (= 1 status))
    (is (str/includes? out "service.clj") out)
    (is (= before (#'multi/files-under dir)) "nothing is written")))
