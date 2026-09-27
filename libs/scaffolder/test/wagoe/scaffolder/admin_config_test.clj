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
                   :label "Invoice line items" :fields [:description :quantity] :editable true
                   :on-delete :cascade}]
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
          (is (= :cascade (get-in admin [:entities :invoices :has-many 0 :on-delete])))
          (is (= {:label "Invoice" :fields [:number :status]}
                 (get-in admin [:entities :invoice-line-items :parent-context])))))
      (is (.isFile (io/file dir "resources/conf/prod/admin/invoice-line-items.edn"))))))

(defn- entity-file [dir env plural]
  (get (aero/read-config (io/file dir "resources/conf" env "admin" (str plural ".edn")))
       (keyword plural)))

(deftest ^:unit secret-columns-stay-hidden
  ;; A manual :hide-fields used to replace the admin's own sensitive set, so a
  ;; generated #{:deleted-at} showed and edited password hashes (BOU-562).
  (let [dir (project!)
        r   (ports/generate-module
             svc {:module-name "accounts" :base-ns "bou562s" :output-dir (.getPath dir)
                  :entities [{:name "Account" :fields [{:name :api-key :type :string}
                                                       {:name :password-hash :type :string}
                                                       {:name :name :type :string}]}
                             {:name "Session" :belongs-to "account"
                              :fields [{:name :refresh-token :type :string}
                                       {:name :label :type :string}]}]})
        account (entity-file dir "dev" "accounts")
        session (entity-file dir "dev" "sessions")
        secret? #{:api-key :password-hash :refresh-token}]
    (is (:success r) (pr-str (:errors r)))
    (is (= #{:deleted-at :api-key :password-hash} (:hide-fields account)))
    (is (= #{:deleted-at :refresh-token} (:hide-fields session)))
    (doseq [fields [(:list-fields account) (:search-fields account)
                    (:list-fields session) (:search-fields session)
                    (get-in account [:has-many 0 :fields])
                    (get-in session [:parent-context :fields])]]
      (is (not-any? secret? fields) (pr-str fields)))
    (is (= [:name] (get-in session [:parent-context :fields])))))

(deftest ^:unit an-existing-admin-file-is-never-overwritten
  ;; --force regenerated every profile's file over the user's edits (BOU-562).
  (let [dir  (project!)
        gen  #(ports/generate-module svc (merge {:module-name "billing" :base-ns "bou562f"
                                                 :entities [(first entities)] :output-dir (.getPath dir)}
                                                %))
        file (io/file dir "resources/conf/dev/admin/invoices.edn")]
    (is (:success (gen {})))
    (spit file "{:invoices {:label \"Mine\"}}\n")
    (let [r (gen {:force true})]
      (is (:success r) (pr-str (:errors r)))
      (is (= "{:invoices {:label \"Mine\"}}\n" (slurp file)))
      (is (some #(and (= :skip (:action %)) (str/ends-with? (:path %) "dev/admin/invoices.edn"))
                (:files r))
          "the report says it was left alone"))
    (testing "without --force an existing admin file does not refuse the run"
      (let [dir2 (project!)
            f2   (io/file dir2 "resources/conf/dev/admin/invoices.edn")]
        (spit f2 "{:invoices {:label \"Mine\"}}\n")
        (is (:success (ports/generate-module svc {:module-name "billing" :base-ns "bou562f2"
                                                  :entities [(first entities)]
                                                  :output-dir (.getPath dir2)})))
        (is (= "{:invoices {:label \"Mine\"}}\n" (slurp f2)))))))

(defn- project-with-dev-config! [text]
  (let [dir (project!)]
    (spit (io/file dir "resources/conf/dev/config.edn") text)
    dir))

(deftest ^:unit a-profile-wrapped-admin-is-left-alone
  ;; `:wagoe/admin #profile {...}` is not a map to edit; treating it as one
  ;; wrote into the tag (BOU-562).
  (let [text (str/replace admin-config "  :wagoe/admin\n  {"
                          "  :wagoe/admin\n  #profile {:default {:enabled? false}}\n  :wagoe/unused\n  {")
        dir  (project-with-dev-config! text)
        r    (ports/generate-module svc {:module-name "billing" :base-ns "bou562p"
                                         :entities [(first entities)] :output-dir (.getPath dir)})]
    (is (:success r) (pr-str (:errors r)))
    (is (= text (slurp (io/file dir "resources/conf/dev/config.edn"))))
    (is (some? (read-admin dir "dev")) "still reads")))

(deftest ^:unit a-discarded-entry-does-not-desync-the-edit
  ;; `#_#_ :entities {...}` was read as a key/value pair, so the walk found the
  ;; wrong :entities and wrote a second one (BOU-562).
  (let [text (str/replace admin-config "   :entities         #merge"
                          "   #_#_ :entities {:old {:label \"Old\"}}\n   :entities         #merge")
        dir  (project-with-dev-config! text)
        r    (ports/generate-module svc {:module-name "billing" :base-ns "bou562u"
                                         :entities [(first entities)] :output-dir (.getPath dir)})
        out  (slurp (io/file dir "resources/conf/dev/config.edn"))]
    (is (:success r) (pr-str (:errors r)))
    (is (= 2 (count (re-seq #":entities" out))) out)
    (is (= #{:users :invoices} (set (keys (:entities (read-admin dir "dev"))))))
    (is (= #{:users :invoices} (get-in (read-admin dir "dev") [:entity-discovery :allowlist])))))

(deftest ^:unit no-admin-no-admin-config
  (let [dir (project!)]
    (doseq [env ["dev" "test"]]
      (spit (io/file dir "resources/conf" env "config.edn") plain-config))
    (is (:success (ports/generate-module svc {:module-name "billing" :base-ns "bou562c"
                                              :entities entities :output-dir (.getPath dir)})))
    (is (not (.exists (io/file dir "resources/conf/dev/admin/invoices.edn"))))))
