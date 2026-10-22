(ns wagoe.admin.shell.form-inputs-test
  "BOU-798: a decimal field takes fractions, and a file field posts its file
   and stores it. BOU-811: a read loads no file, only its size, and a stored
   file can be removed. Swept over H2 (BYTEA and BLOB), SQLite and PostgreSQL,
   and MySQL with WAGOE_TEST_DB=mysql: each names its binary column type
   differently."
  (:require [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.service :as service]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.shell.http.handlers.crud :as crud]
            [wagoe.admin.shell.http.handlers.detail :as detail]
            [wagoe.admin.test.embedded-pg :as epg]
            [wagoe.i18n.shell.catalogue :as catalogue]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.database :as db]
            [support.mysql :as mysql]
            [support.test-db :as test-db]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [wagoe.test.logging :refer [logged-errors]])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]))

^{:kaocha.testable/meta {:integration true :admin true}}

(def ^:private admin-config
  {:entity-discovery {:mode :allowlist :allowlist #{:fi-products :fi-kits :fi-kit-files}}
   :entities         {:fi-products  {:label "Products" :title-field :name}
                      ;; A kit is created with its first file (a has-many :min)
                      :fi-kits      {:label "Kits"
                                     :has-many [{:entity :fi-kit-files :table :fi_kit_files :foreign-key :fi-kit-id
                                                 :label "Files" :fields [:label :content] :editable true
                                                 :on-delete :cascade :min 1}]}
                      :fi-kit-files {:label "Kit file"}}})

(def ^:private admin-user
  {:id #uuid "00000000-0000-0000-0000-000000000001"
   :email "admin@example.com" :name "Admin" :role :admin :active true})

(defn- ddl [binary-type]
  [(str "CREATE TABLE fi_products ("
        "id VARCHAR(36) PRIMARY KEY, "
        "name VARCHAR(50) NOT NULL, "
        "price DECIMAL(10,2), "
        "datasheet " binary-type ")")
   "CREATE TABLE fi_kits (id VARCHAR(36) PRIMARY KEY, name VARCHAR(50) NOT NULL)"
   (str "CREATE TABLE fi_kit_files (id VARCHAR(36) PRIMARY KEY, "
        "fi_kit_id VARCHAR(36) NOT NULL REFERENCES fi_kits(id), "
        "label VARCHAR(50) NOT NULL, "
        "content " binary-type ")")])

(def ^:private tables ["fi_kit_files" "fi_kits" "fi_products"])

(defonce ^:private backends (atom {}))

(use-fixtures :once
  (fn [f]
    (let [sqlite-file (File/createTempFile "bou798" ".db")
          pg          (epg/start!)
          ctxs        {:h2         [(db-factory/db-context {:adapter :h2 :database-path "mem:bou798;DB_CLOSE_DELAY=-1"}) "BYTEA"]
                       ;; H2's BLOB, read back as a java.sql.Blob, not bytes
                       :h2-blob    [(db-factory/db-context {:adapter :h2 :database-path "mem:bou811blob;DB_CLOSE_DELAY=-1"}) "BINARY LARGE OBJECT"]
                       :sqlite     [(db-factory/db-context {:adapter :sqlite :database-path (.getPath sqlite-file)}) "BLOB"]
                       :postgresql [(epg/db-context pg) "BYTEA"]}
          run         (fn [ctxs]
                        (doseq [[ctx binary-type] (vals ctxs)]
                          (doseq [t tables] (db/execute-update! ctx {:raw (str "DROP TABLE IF EXISTS " t)}))
                          (doseq [stmt (ddl binary-type)] (db/execute-update! ctx {:raw stmt})))
                        (reset! backends (update-vals ctxs first))
                        (f))]
      (try
        (if (= :mysql test-db/engine)
          (mysql/with-database "bou811" #(run (assoc ctxs :mysql [% "LONGBLOB"])))
          (run ctxs))
        (finally
          (doseq [[ctx] (vals ctxs)] (db-factory/close-db-context! ctx))
          (epg/stop! pg)
          (.delete sqlite-file)
          (reset! backends {}))))))

(use-fixtures :each
  (fn [f]
    (doseq [ctx (vals @backends) t tables]
      (db/execute-update! ctx {:raw (str "DELETE FROM " t)}))
    (f)))

(defn- system [db-ctx & [config]]
  (let [config (or config admin-config)
        schema (schema-repo/create-schema-repository db-ctx config)]
    {:schema schema :svc (service/create-admin-service db-ctx schema nil nil config)}))

(def ^:private i18n-catalogue
  (catalogue/create-map-catalogue (catalogue/load-catalogue "wagoe/i18n/translations")))

(defn- upload
  "A file part as Ring's multipart decoder hands it over."
  [filename content]
  (let [f (File/createTempFile "bou798" ".upload")]
    (.deleteOnExit f)
    (spit f content)
    {:filename filename :content-type "text/plain" :tempfile f :size (.length f)}))

(defn- multipart-request
  "What the router passes on for a multipart body: the fields in
   :multipart-params, and in :params with the query string, and an empty
   :form-params. The edit form's action carries return_to."
  [method form & [id]]
  {:request-method   method
   :uri              (str "/web/admin/fi-products" (when id (str "/" id)))
   :user             admin-user
   :path-params      (cond-> {:entity "fi-products"} id (assoc :id (str id)))
   :form-params      {}
   :multipart-params form
   :params           (assoc form "return_to" "/web/admin/fi-products")
   :i18n/catalogue   i18n-catalogue
   :i18n/default-locale :en})

(defn- the-row [db-ctx]
  (first (db/execute-query! db-ctx {:select [:*] :from [:fi_products]})))

(defn- bytes->str [v]
  (when v
    (let [^bytes b (if (instance? java.sql.Blob v) (.getBytes ^java.sql.Blob v 1 (int (.length ^java.sql.Blob v))) v)]
      (String. b StandardCharsets/UTF_8))))

(deftest ^:integration a-decimal-field-takes-fractions
  (doseq [[backend db-ctx] @backends
          :let [{:keys [schema svc]} (system db-ctx)]
          price ["12.50" "0.01"]]
    (testing (str backend " " price)
      (db/execute-update! db-ctx {:raw "DELETE FROM fi_products"})
      (let [response ((crud/create-entity-handler svc schema admin-config)
                      (multipart-request :post {"name" "Widget" "price" price}))]
        (is (not= 422 (:status response)) (str (:body response)))
        (is (== (bigdec price) (:price (the-row db-ctx))))))))

(deftest ^:integration a-file-field-stores-the-uploaded-file
  (doseq [[backend db-ctx] @backends
          :let [{:keys [schema svc]} (system db-ctx)]]
    (testing (str backend)
      (let [response ((crud/create-entity-handler svc schema admin-config)
                      (multipart-request :post {"name"      "Widget"
                                                "datasheet" (upload "sheet.txt" "hello datasheet")}))]
        (is (not= 422 (:status response)) (str (:body response)))
        (is (= "hello datasheet" (bytes->str (:datasheet (the-row db-ctx)))))))))

(deftest ^:integration an-edit-without-a-new-file-keeps-the-stored-one
  (doseq [[backend db-ctx] @backends
          :let [{:keys [schema svc]} (system db-ctx)]]
    (testing (str backend)
      ((crud/create-entity-handler svc schema admin-config)
       (multipart-request :post {"name" "Widget" "datasheet" (upload "sheet.txt" "v1")}))
      (let [id       (:id (the-row db-ctx))
            page     (str (:body ((detail/entity-detail-handler svc schema admin-config)
                                  (multipart-request :get {} id))))
            _        (is (str/includes? page "A file of 2 bytes is stored") "the edit form says a file is stored")
            ;; A browser sends an empty part for a file input left alone.
            response ((crud/update-entity-handler svc schema admin-config)
                      (multipart-request :put {"name" "Renamed" "datasheet" (upload "" "")} id))]
        (is (not= 422 (:status response)) (str (:body response)))
        (is (= "Renamed" (:name (the-row db-ctx))))
        (is (= "v1" (bytes->str (:datasheet (the-row db-ctx)))))))))

(deftest ^:integration the-form-posts-multipart
  (let [db-ctx (:h2 @backends)
        {:keys [schema svc]} (system db-ctx)
        body (str (:body ((detail/new-entity-handler svc schema admin-config)
                          (assoc (multipart-request :get {}) :uri "/web/admin/fi-products/new"))))]
    (is (re-find #"<form class=\"entity-form\"[^>]*hx-encoding=\"multipart/form-data\"" body))
    (is (re-find #"<input[^>]*name=\"datasheet\"[^>]*type=\"file\"" body)
        "the binary column gets a file input")))

(deftest ^:integration a-rejected-form-does-not-show-its-upload-as-stored
  (let [db-ctx   (:h2 @backends)
        {:keys [schema svc]} (system db-ctx)
        response ((crud/create-entity-handler svc schema admin-config)
                  (multipart-request :post {"name" "Widget" "price" "x" "datasheet" (upload "sheet.txt" "v1")}))]
    (is (= 422 (:status response)))
    (is (not (str/includes? (str (:body response)) "is stored"))
        "nothing was stored, so a resubmit must choose the file again")))

;; =============================================================================
;; BOU-811
;; =============================================================================

(defn- create-with-file! [svc schema content]
  ((crud/create-entity-handler svc schema admin-config)
   (multipart-request :post {"name" "Widget" "datasheet" (upload "sheet.txt" content)})))

(deftest ^:integration a-read-loads-the-file-size-not-the-file
  (doseq [[backend db-ctx] @backends
          :let [{:keys [schema svc]} (system db-ctx)]]
    (testing (str backend)
      (create-with-file! svc schema "hello datasheet")
      (let [id (:id (the-row db-ctx))]
        (testing "a record holds the size"
          (is (= 15 (:datasheet (ports/get-entity svc :fi-products id)))))
        (testing "a list does not read a file that is not one of its columns"
          (let [record (first (:records (ports/list-entities svc :fi-products {})))]
            (is (= "Widget" (:name record)))
            (is (not (contains? record :datasheet)))))
        (testing "where it is a column, the list reads its size"
          (let [config (assoc-in admin-config [:entities :fi-products :list-fields] [:name :datasheet])
                {:keys [svc]} (system db-ctx config)]
            (is (= 15 (:datasheet (first (:records (ports/list-entities svc :fi-products {}))))))))))))

(deftest ^:integration a-stored-file-can-be-removed
  (doseq [[backend db-ctx] @backends
          :let [{:keys [schema svc]} (system db-ctx)
                update! (fn [id form]
                          ((crud/update-entity-handler svc schema admin-config)
                           (multipart-request :put form id)))]]
    (testing (str backend)
      (db/execute-update! db-ctx {:raw "DELETE FROM fi_products"})
      (create-with-file! svc schema "v1")
      (let [id   (:id (the-row db-ctx))
            page (str (:body ((detail/entity-detail-handler svc schema admin-config)
                              (multipart-request :get {} id))))]
        (is (re-find #"<input[^>]*name=\"__remove\"[^>]*value=\"datasheet\"" page)
            "the edit form offers to remove the stored file")
        (testing "removing it and choosing a new one keeps the new one"
          (update! id {"name" "Widget" "datasheet" (upload "v2.txt" "v2") "__remove" "datasheet"})
          (is (= "v2" (bytes->str (:datasheet (the-row db-ctx))))))
        (testing "removing it clears the column"
          (let [response (update! id {"name" "Widget" "datasheet" (upload "" "") "__remove" "datasheet"})]
            (is (not= 422 (:status response)) (str (:body response)))
            (is (nil? (:datasheet (the-row db-ctx))))
            (is (not (str/includes? (str (:body response)) "is stored")))))))))

(deftest ^:integration a-required-file-cannot-be-removed
  (let [db-ctx (:h2 @backends)
        config (assoc-in admin-config [:entities :fi-products :fields] {:datasheet {:required true}})
        {:keys [schema svc]} (system db-ctx config)
        _      ((crud/create-entity-handler svc schema config)
                (multipart-request :post {"name" "Widget" "datasheet" (upload "sheet.txt" "v1")}))
        id     (:id (the-row db-ctx))
        page   (str (:body ((detail/entity-detail-handler svc schema config) (multipart-request :get {} id))))]
    (is (str/includes? page "is stored"))
    (is (not (str/includes? page "__remove")) "no remove control on a required file")
    (testing "and a request that removes it anyway is refused"
      (let [response ((crud/update-entity-handler svc schema config)
                      (multipart-request :put {"name" "Widget" "datasheet" (upload "" "") "__remove" "datasheet"} id))]
        (is (= 422 (:status response)))
        (is (= "v1" (bytes->str (:datasheet (the-row db-ctx)))))))))

(deftest ^:integration a-mismatched-file-field-leaves-the-form-and-nothing-else
  ;; A 1.x app with :file-input on a text field worked except on upload; it
  ;; must not lose its admin over it.
  (let [db-ctx (:h2 @backends)
        config (assoc-in admin-config [:entities :fi-products :fields]
                         {:name      {:type :string :widget :file-input}
                          :datasheet {:widget :textarea}})
        {:keys [schema svc]} (system db-ctx config)
        [cfg logged] (logged-errors #(ports/get-entity-config schema :fi-products))]
    (testing "logged once, naming entity, field and fix"
      (is (= 2 (count logged)))
      (is (some #(str/includes? % ":widget :file-input needs a :binary field") logged))
      (is (some #(str/includes? % "its :widget must be :file-input") logged))
      (is (= [] (second (logged-errors #(ports/get-entity-config schema :fi-products))))
          "cached: not computed, nor logged, again"))
    (testing "the fields are off the form and not written"
      (is (= [:datasheet :name] (map :field (:upload-config-errors cfg))))
      (is (not-any? #{:name :datasheet} (:editable-fields cfg)))
      (is (every? (set (:readonly-fields cfg)) [:name :datasheet])))
    (testing "the rest of the admin keeps working"
      (let [page ((detail/new-entity-handler svc schema config)
                  (assoc (multipart-request :get {}) :uri "/web/admin/fi-products/new"))]
        (is (= 200 (:status page)))
        (is (str/includes? (str (:body page)) "is not on this form")))
      (is (= 0 (:total-count (ports/list-entities svc :fi-products {}))))
      (is (= 200 (:status ((detail/new-entity-handler svc schema config)
                           (assoc (multipart-request :get {}) :uri "/web/admin/fi-kits/new"
                                  :path-params {:entity "fi-kits"}))))))))

(deftest ^:integration a-child-row-uploads-its-file
  (doseq [[backend db-ctx] @backends
          :let [{:keys [schema svc]} (system db-ctx)]]
    (testing (str backend)
      (let [form     (str (:body ((detail/new-entity-handler svc schema admin-config)
                                  (assoc (multipart-request :get {}) :uri "/web/admin/fi-kits/new"
                                         :path-params {:entity "fi-kits"}))))
            response ((crud/create-entity-handler svc schema admin-config)
                      (assoc (multipart-request :post {"name"                          "Kit"
                                                       "__child.fi-kit-files.0.label"   "Manual"
                                                       "__child.fi-kit-files.0.content" (upload "m.txt" "manual")
                                                       ;; A second row left blank, file input included
                                                       "__child.fi-kit-files.1.label"   ""
                                                       "__child.fi-kit-files.1.content" (upload "" "")})
                             :uri "/web/admin/fi-kits" :path-params {:entity "fi-kits"}))
            rows     (db/execute-query! db-ctx {:select [:*] :from [:fi_kit_files]})]
        (is (re-find #"<form class=\"entity-form\"[^>]*hx-encoding=\"multipart/form-data\"" form)
            "a parent form whose child rows take a file posts multipart")
        (is (not= 422 (:status response)) (str (:body response)))
        (is (= ["manual"] (map (comp bytes->str :content) rows)))))))
