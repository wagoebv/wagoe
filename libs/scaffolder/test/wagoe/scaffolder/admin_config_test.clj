(ns wagoe.scaffolder.admin-config-test
  "With the admin UI on, the scaffolder writes each entity's admin config:
   the entity file in every profile, the :allowlist entry and the `#include`.
   A belongs-to child gets :parent-context, its parent an editable :has-many
   (BOU-562)."
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [wagoe.admin.ports :as admin-ports]
            [wagoe.admin.schema :as admin-schema]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.scaffolder.ports :as ports]
            [wagoe.scaffolder.shell.service :as service]))

(def ^:private svc (service/create-scaffolder-service))

(def ^:private admin-config
  ;; What `bb setup` writes (wagoe.tools.setup/admin-template).
  (str "{:active\n"
       " {:wagoe/settings {:name \"app\"}\n"
       "  :wagoe/admin\n"
       "  {:enabled?         true\n"
       "   :base-path        \"/web/admin\"\n"
       "   :require-role     :admin\n"
       "   :entity-discovery {:mode      :allowlist\n"
       "                      :allowlist #{:users}}\n"
       "   :entities         #merge [#include \"admin/users.edn\"]\n"
       "   :pagination       {:default-page-size 20\n"
       "                      :max-page-size     200}}}\n"
       "\n"
       " :inactive\n"
       " {}}\n"))

(def ^:private plain-config "{:active\n {:wagoe/settings {:name \"app\"}}\n\n :inactive\n {}}\n")

(defn- project!
  "A project directory whose dev and test profiles have the admin on and whose
   prod profile does not."
  []
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "wagoe-admin-config" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (doseq [[env text] {"dev" admin-config "test" admin-config "prod" plain-config}]
      (let [f (io/file dir "resources/conf" env "config.edn")]
        (io/make-parents f)
        (spit f text)
        (when (= text admin-config)
          (let [users (io/file dir "resources/conf" env "admin/users.edn")]
            (io/make-parents users)
            (spit users "{:users {:label \"Users\"}}\n")))))
    dir))

(def ^:private entities
  [{:name "Invoice" :fields [{:name :number :type :string}
                             {:name :status :type :enum :enum-values [:entered :paid]}]}
   {:name "InvoiceLineItem" :belongs-to "invoice"
    :fields [{:name :description :type :string} {:name :quantity :type :int}]}])

(defn- read-admin [dir env]
  (get-in (aero/read-config (io/file dir "resources/conf" env "config.edn") {:profile :dev})
          [:active :wagoe/admin]))

(defn- statements [sql]
  (->> (str/split sql #";")
       (map #(str/join "\n" (remove (fn [l] (str/starts-with? (str/trim l) "--")) (str/split-lines %))))
       (map str/trim)
       (remove str/blank?)))

(defn- migrated-ctx [dir]
  (let [ctx (db-factory/db-context {:adapter :h2
                                    :database-path (str "mem:admincfg" (System/nanoTime) ";DB_CLOSE_DELAY=-1")
                                    :pool {:minimum-idle 1 :maximum-pool-size 2}})]
    (doseq [f (sort (.listFiles (io/file dir "migrations")))
            :when (str/ends-with? (.getName ^java.io.File f) ".up.sql")
            s (statements (slurp f))]
      (jdbc/execute! (:datasource ctx) [s]))
    ctx))

(deftest ^:integration generate-writes-the-admin-config
  (let [dir (project!)
        r   (ports/generate-module svc {:module-name "billing" :base-ns "bou562a"
                                        :entities entities :output-dir (.getPath dir)})]
    (is (:success r) (pr-str (:errors r)))

    (testing "every profile gets the entity files"
      (doseq [env ["dev" "test" "prod"] plural ["invoices" "invoice-line-items"]]
        (is (.isFile (io/file dir "resources/conf" env "admin" (str plural ".edn"))) (str env "/" plural))))

    (testing "a profile with the admin gets the allowlist entry and the #include"
      (doseq [env ["dev" "test"]]
        (let [admin (read-admin dir env)]
          (is (= #{:users :invoices :invoice-line-items} (get-in admin [:entity-discovery :allowlist])))
          (is (= #{:users :invoices :invoice-line-items} (set (keys (:entities admin))))))))

    (testing "a profile without it is left alone"
      (is (= plain-config (slurp (io/file dir "resources/conf/prod/config.edn")))))

    (testing "a second run adds nothing twice"
      (ports/generate-module svc {:module-name "billing" :base-ns "bou562a" :force true
                                  :entities entities :output-dir (.getPath dir)})
      (let [text (slurp (io/file dir "resources/conf/dev/config.edn"))]
        (is (= 1 (count (re-seq #"admin/invoices\.edn" text))) text)
        (is (= 1 (count (re-seq #":invoice-line-items" text))) text)))

    (testing "the admin takes it"
      (let [admin  (read-admin dir "dev")
            ctx    (migrated-ctx dir)
            repo   (schema-repo/create-schema-repository ctx admin)
            config #(admin-ports/get-entity-config repo %)]
        (try
          (doseq [e [:invoices :invoice-line-items]]
            (is (:valid? (admin-schema/validate-entity-config (config e))) (str e)))
          (is (= [{:entity :invoice-line-items :table :invoice_line_items :foreign-key :invoice-id
                   :label "Invoice line items" :fields [:description :quantity] :editable true}]
                 (:has-many (config :invoices))))
          (is (= {:label "Invoice" :fields [:number :status]}
                 (:parent-context (config :invoice-line-items))))
          (is (= :select (get-in (config :invoices) [:fields :status :widget])))
          (finally (db-factory/close-db-context! ctx)))))))

(deftest ^:integration an-added-entity-joins-its-parent-s-admin-config
  (let [dir (project!)]
    (is (:success (ports/generate-module svc {:module-name "billing" :base-ns "bou562b"
                                              :entities [(first entities)] :output-dir (.getPath dir)})))
    (let [r (ports/add-entity svc {:module-name "billing" :base-ns "bou562b"
                                   :entity (second entities) :output-dir (.getPath dir)})]
      (is (:success r) (pr-str (:errors r)))
      (doseq [env ["dev" "test"]]
        (let [admin (read-admin dir env)]
          (is (contains? (get-in admin [:entity-discovery :allowlist]) :invoice-line-items))
          (is (= [:invoice-line-items] (map :entity (get-in admin [:entities :invoices :has-many]))))
          (is (true? (get-in admin [:entities :invoices :has-many 0 :editable])))
          (is (= {:label "Invoice" :fields [:number :status]}
                 (get-in admin [:entities :invoice-line-items :parent-context])))))
      (is (.isFile (io/file dir "resources/conf/prod/admin/invoice-line-items.edn"))))))

(deftest ^:unit no-admin-no-admin-config
  (let [dir (project!)]
    (doseq [env ["dev" "test"]]
      (spit (io/file dir "resources/conf" env "config.edn") plain-config))
    (is (:success (ports/generate-module svc {:module-name "billing" :base-ns "bou562c"
                                              :entities entities :output-dir (.getPath dir)})))
    (is (not (.exists (io/file dir "resources/conf/dev/admin/invoices.edn"))))))
