(ns wagoe.admin.shell.form-inputs-test
  "BOU-798: a decimal field takes fractions, and a file field posts its file
   and stores it. Swept over H2, SQLite and PostgreSQL: each names its binary
   column type differently."
  (:require [wagoe.admin.shell.service :as service]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.shell.http.handlers.crud :as crud]
            [wagoe.admin.shell.http.handlers.detail :as detail]
            [wagoe.admin.test.embedded-pg :as epg]
            [wagoe.i18n.shell.catalogue :as catalogue]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.database :as db]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]))

^{:kaocha.testable/meta {:integration true :admin true}}

(def ^:private admin-config
  {:entity-discovery {:mode :allowlist :allowlist #{:fi-products}}
   :entities         {:fi-products {:label "Products" :title-field :name}}})

(def ^:private admin-user
  {:id #uuid "00000000-0000-0000-0000-000000000001"
   :email "admin@example.com" :name "Admin" :role :admin :active true})

(defn- ddl [binary-type]
  (str "CREATE TABLE fi_products ("
       "id VARCHAR(36) PRIMARY KEY, "
       "name VARCHAR(50) NOT NULL, "
       "price DECIMAL(10,2), "
       "datasheet " binary-type ")"))

(defonce ^:private backends (atom {}))

(use-fixtures :once
  (fn [f]
    (let [sqlite-file (File/createTempFile "bou798" ".db")
          pg          (epg/start!)
          ctxs        {:h2         [(db-factory/db-context {:adapter :h2 :database-path "mem:bou798;DB_CLOSE_DELAY=-1"}) "BYTEA"]
                       :sqlite     [(db-factory/db-context {:adapter :sqlite :database-path (.getPath sqlite-file)}) "BLOB"]
                       :postgresql [(epg/db-context pg) "BYTEA"]}]
      (try
        (doseq [[ctx binary-type] (vals ctxs)]
          (db/execute-update! ctx {:raw "DROP TABLE IF EXISTS fi_products"})
          (db/execute-update! ctx {:raw (ddl binary-type)}))
        (reset! backends (update-vals ctxs first))
        (f)
        (finally
          (doseq [[ctx] (vals ctxs)] (db-factory/close-db-context! ctx))
          (epg/stop! pg)
          (.delete sqlite-file)
          (reset! backends {}))))))

(use-fixtures :each
  (fn [f]
    (doseq [ctx (vals @backends)]
      (db/execute-update! ctx {:raw "DELETE FROM fi_products"}))
    (f)))

(defn- system [db-ctx]
  (let [schema (schema-repo/create-schema-repository db-ctx admin-config)]
    {:schema schema :svc (service/create-admin-service db-ctx schema nil nil admin-config)}))

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
  (some-> ^bytes v (String. StandardCharsets/UTF_8)))

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

(deftest ^:integration the-form-posts-multipart-and-steps-decimals-freely
  (let [db-ctx (:h2 @backends)
        {:keys [schema svc]} (system db-ctx)
        body (str (:body ((detail/new-entity-handler svc schema admin-config)
                          (assoc (multipart-request :get {}) :uri "/web/admin/fi-products/new"))))]
    (is (re-find #"<form class=\"entity-form\"[^>]*hx-encoding=\"multipart/form-data\"" body))
    (is (re-find #"<input[^>]*name=\"datasheet\"[^>]*type=\"file\"" body)
        "the binary column gets a file input")
    (is (re-find #"<input[^>]*name=\"price\"[^>]*step=\"any\"|<input[^>]*step=\"any\"[^>]*name=\"price\"" body))))

