(ns wagoe.admin.shell.many-to-many-test
  "BOU-778: a join table is a many-to-many on both sides, not a child. Swept
   over H2, SQLite and PostgreSQL, and MySQL with WAGOE_TEST_DB=mysql."
  (:require [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.service :as service]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.test.embedded-pg :as epg]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.database :as db]
            [wagoe.observability.logging.shell.adapters.no-op :as logging-no-op]
            [wagoe.observability.errors.shell.adapters.no-op :as error-reporting-no-op]
            [support.mysql :as mysql]
            [support.test-db :as test-db]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io File]))

(def ^:private admin-config
  {:entity-discovery {:mode      :allowlist
                      :allowlist #{:products :tags :product-tags :featured-tags :memberships}}
   :entities         {:products {:title-field :name}
                      :tags     {:title-field :name}}
   :pagination       {:default-page-size 20 :max-page-size 200}})

(def ^:private ddl
  ["CREATE TABLE products (id VARCHAR(36) PRIMARY KEY, name VARCHAR(100) NOT NULL)"
   "CREATE TABLE tags (id VARCHAR(36) PRIMARY KEY, name VARCHAR(50) NOT NULL, deleted_at VARCHAR(40))"
   ;; Composite key, a timestamp: a join table
   (str "CREATE TABLE product_tags (product_id VARCHAR(36) NOT NULL REFERENCES products(id),"
        " tag_id VARCHAR(36) NOT NULL REFERENCES tags(id), created_at VARCHAR(40),"
        " PRIMARY KEY (product_id, tag_id))")
   ;; A surrogate key and a unique pair: a join table too
   (str "CREATE TABLE featured_tags (id VARCHAR(36) PRIMARY KEY,"
        " product_id VARCHAR(36) NOT NULL, tag_id VARCHAR(36) NOT NULL)")
   "CREATE UNIQUE INDEX uq_featured_tags ON featured_tags (product_id, tag_id)"
   ;; A role on the link: an ordinary child
   (str "CREATE TABLE memberships (id VARCHAR(36) PRIMARY KEY,"
        " product_id VARCHAR(36) NOT NULL, tag_id VARCHAR(36) NOT NULL, role VARCHAR(20),"
        " CONSTRAINT uq_memberships UNIQUE (product_id, tag_id))")
   ;; A join table that refuses every link: the admin stamps created_at
   (str "CREATE TABLE locked_tags (product_id VARCHAR(36) NOT NULL REFERENCES products(id),"
        " tag_id VARCHAR(36) NOT NULL REFERENCES tags(id),"
        " created_at VARCHAR(40) CONSTRAINT ck_locked_tags CHECK (created_at IS NULL),"
        " PRIMARY KEY (product_id, tag_id))")])

(defonce ^:private backends (atom {}))

(defn- create-tables! [db-ctx]
  (doseq [t ["locked_tags" "memberships" "featured_tags" "product_tags" "tags" "products"]]
    (db/execute-update! db-ctx {:raw (str "DROP TABLE IF EXISTS " t)}))
  (doseq [stmt ddl]
    (db/execute-update! db-ctx {:raw stmt})))

(defn- with-backends [f]
  (let [sqlite-file (File/createTempFile "bou778" ".db")
        pg          (epg/start!)
        ctxs        {:h2         (db-factory/db-context {:adapter :h2 :database-path "mem:bou778admin;DB_CLOSE_DELAY=-1"})
                     :sqlite     (db-factory/db-context {:adapter :sqlite :database-path (.getPath sqlite-file)})
                     :postgresql (epg/db-context pg)}
        run         (fn [ctxs]
                      (reset! backends ctxs)
                      (f))]
    (try
      (if (= :mysql test-db/engine)
        (mysql/with-database "bou778" #(run (assoc ctxs :mysql %)))
        (run ctxs))
      (finally
        (doseq [ctx (vals ctxs)] (db-factory/close-db-context! ctx))
        (epg/stop! pg)
        (.delete sqlite-file)
        (reset! backends {})))))

(use-fixtures :once with-backends)

(defn- system [db-ctx & [config writers]]
  (create-tables! db-ctx)
  (let [config (or config admin-config)
        schema (schema-repo/create-schema-repository db-ctx config {})
        svc    (service/create-admin-service db-ctx schema
                                             (logging-no-op/create-logging-component {})
                                             (error-reporting-no-op/create-error-reporting-component {})
                                             config nil nil writers)]
    {:schema schema :svc svc}))

(defn- links-of [svc entity k id]
  (mapv :title (:items (get (ports/linked-records svc entity k [id] {}) (str id)))))

(defn- tag! [svc n] (str (:id (ports/create-entity svc :tags {:name n}))))

(deftest ^:integration a-join-table-is-a-many-to-many-on-both-sides
  (doseq [[backend db-ctx] @backends
          :let [{:keys [schema]} (system db-ctx)
                product (ports/get-entity-config schema :products)
                tag     (ports/get-entity-config schema :tags)]]
    (testing (str backend)
      (testing "both join tables, keyed by join where the other side repeats"
        (is (= #{[:product-tags-by-tag-id :product-tags :tags :product-id :tag-id]
                 [:featured-tags-by-tag-id :featured-tags :tags :product-id :tag-id]}
               (set (map (juxt :key :through :entity :foreign-key :other-key) (:many-to-many product)))))
        (is (= #{:product-tags-by-product-id :featured-tags-by-product-id}
               (set (map :key (:many-to-many tag))))))
      (testing "a join table is no child; one with a role column is"
        (is (= [:memberships] (map :entity (:has-many product)))))
      (testing "the join table is hidden from the sidebar"
        (is (true? (:sidebar-hidden (ports/get-entity-config schema :product-tags))))
        (is (nil? (:sidebar-hidden (ports/get-entity-config schema :memberships))))))))

(deftest ^:integration links-are-written-with-the-record
  (doseq [[backend db-ctx] @backends
          :let [{:keys [svc]} (system db-ctx)
                red   (tag! svc "Red")
                blue  (tag! svc "Blue")
                green (tag! svc "Green")
                k     :product-tags-by-tag-id]]
    (testing (str backend)
      (let [shirt (ports/create-entity svc :products {:name "Shirt" :admin/links {k [red blue]}})
            id    (:id shirt)]
        (testing "a create links them, in one transaction with the row"
          (is (= ["Blue" "Red"] (links-of svc :products k id)) "by title"))

        (testing "an update replaces the links of the relations it names"
          (ports/update-entity svc :products id {:admin/links {k [blue green]}})
          (is (= ["Blue" "Green"] (links-of svc :products k id)))
          (ports/update-entity svc :products id {:name "T-shirt"})
          (is (= ["Blue" "Green"] (links-of svc :products k id)) "a relation not named is left"))

        (testing "a join table with its own key gets one"
          (ports/update-entity svc :products id {:admin/links {:featured-tags-by-tag-id [red]}})
          (is (= ["Red"] (links-of svc :products :featured-tags-by-tag-id id)))
          (is (some? (:id (db/execute-one! db-ctx {:select [:id] :from [:featured_tags]})))))

        (testing "an unknown record is refused on the relation, and nothing changes"
          (let [ex (try (ports/update-entity svc :products id {:name "X" :admin/links {k [blue (str (random-uuid))]}})
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
            (is (= :validation-error (:type (ex-data ex))))
            (is (contains? (:errors (ex-data ex)) k))
            (is (= "T-shirt" (:name (ports/get-entity svc :products id))))
            (is (= ["Blue" "Green"] (links-of svc :products k id)))))

        (testing "the other side sees the same links"
          (is (= ["T-shirt"] (links-of svc :tags :product-tags-by-product-id green))))

        (testing "an empty list unlinks all"
          (ports/update-entity svc :products id {:admin/links {k []}})
          (is (= [] (links-of svc :products k id))))))))

(deftest ^:integration a-list-shows-a-few-links-and-filters-on-them
  (doseq [[backend db-ctx] @backends
          :let [{:keys [svc]} (system db-ctx)
                k     :product-tags-by-tag-id
                tags  (mapv #(tag! svc %) ["A" "B" "C" "D" "E"])
                many  (ports/create-entity svc :products {:name "Many" :admin/links {k tags}})
                one   (ports/create-entity svc :products {:name "One" :admin/links {k [(tags 4)]}})
                none  (ports/create-entity svc :products {:name "None"})]]
    (testing (str backend)
      (testing "the first few of each, by title, and how many there are"
        (let [found (ports/linked-records svc :products k [(:id many) (:id one) (:id none)] {:per-owner 3})]
          (is (= ["A" "B" "C"] (mapv :title (:items (get found (str (:id many)))))))
          (is (= 5 (:total (get found (str (:id many))))))
          (is (= {:items [{:id (tags 4) :title "E"}] :total 1} (get found (str (:id one)))))
          (is (not (contains? found (str (:id none)))))))

      (testing "has X / has no X"
        (let [names (fn [filters]
                      (set (map :name (:records (ports/list-entities svc :products {:filters filters})))))]
          (is (= #{"Many" "One"} (names {k {:op :has :value (tags 4)}})))
          (is (= #{"Many"} (names {k {:op :has :value (tags 0)}})))
          (is (= #{"One" "None"} (names {k {:op :has-not :value (tags 0)}})))
          (is (= #{"Many"} (names {k {:op :has :value (tags 0)} :name {:op :contains :value "an"}})))))

      (testing "the list overview names them per cell"
        (let [overview (ports/list-overview svc :products {:overview/fields [k]} [many])]
          (is (= 5 (get-in overview [:links k (str (:id many)) :total])))))

      (testing "a hard delete takes the links with it"
        (ports/delete-entity svc :products (:id many))
        (is (= 1 (:n (db/execute-one! db-ctx {:select [[:%count.* :n]] :from [:product_tags]}))))))))

(def ^:private writer-config
  "Products written through a writer; tags soft-deleted."
  (-> admin-config
      (update-in [:entity-discovery :allowlist] conj :locked-tags)
      (assoc-in [:entities :products :writer] :products)
      (assoc-in [:entities :tags :soft-delete] true)))

(defn- products-writer
  "A writer that, like a module's service, opens its own transactions."
  [db-ctx]
  (reify ports/IEntityWriter
    (update-record! [_ id changes]
      (when-let [changes (not-empty (select-keys changes [:name]))]
        (db/with-transaction [tx db-ctx]
          (db/execute-update! tx {:update :products :set changes :where [:= :id (str id)]}))))
    (delete-records! [_ ids _opts]
      (db/with-transaction [tx db-ctx]
        (db/execute-update! tx {:delete-from :products :where [:in :id (mapv str ids)]})
        (mapv str ids)))))

(defn- refusal [f]
  (try (f) nil (catch Exception e e)))

(deftest ^:integration a-writer-entity-links-in-the-writers-transaction
  (doseq [[backend db-ctx] @backends
          :let [{:keys [svc]} (system db-ctx writer-config {:products (products-writer db-ctx)})
                red  (tag! svc "Red")
                blue (tag! svc "Blue")
                k    :product-tags-by-tag-id
                id   (:id (ports/create-entity svc :products {:name "Shirt" :admin/links {k [red]}}))]]
    (testing (str backend)
      (testing "the record and its links are written together"
        (ports/update-entity svc :products id {:name "T-shirt" :admin/links {k [red blue]}})
        (is (= "T-shirt" (:name (ports/get-entity svc :products id))))
        (is (= ["Blue" "Red"] (links-of svc :products k id))))

      (testing "a refused link rolls the writer's update back"
        (is (some? (refusal #(ports/update-entity svc :products id
                                                  {:name "Hat" :admin/links {:locked-tags-by-tag-id [red]}}))))
        (is (= "T-shirt" (:name (ports/get-entity svc :products id))))
        (is (= ["Blue" "Red"] (links-of svc :products k id))))

      (testing "a link to a soft-deleted record survives an update, which cannot show it"
        (ports/delete-entity svc :tags red)
        (ports/update-entity svc :products id {:admin/links {k [blue]}})
        (is (= #{red blue}
               (set (map :t (db/execute-query! db-ctx {:select [[:tag_id :t]] :from [:product_tags]
                                                       :where  [:= :product_id (str id)]}))))))

      (testing "the writer's hard delete takes the join rows"
        (ports/delete-entity svc :products id)
        (is (nil? (ports/get-entity svc :products id)))
        (is (= 0 (:n (db/execute-one! db-ctx {:select [[:%count.* :n]] :from [:product_tags]}))))))))
