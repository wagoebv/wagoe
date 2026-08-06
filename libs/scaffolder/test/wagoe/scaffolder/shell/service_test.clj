(ns wagoe.scaffolder.shell.service-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [wagoe.scaffolder.shell.service :as service]
            [wagoe.scaffolder.ports :as ports]
            [clojure.java.io :as io]
            [clojure.set]))

(def test-output-dir ".test-output")

(defn cleanup-test-output
  "Remove test output directory."
  []
  (when (.exists (io/file test-output-dir))
    (let [dir (io/file test-output-dir)]
      (doseq [f (file-seq dir)]
        (when (.isFile f)
          (io/delete-file f)))
      (doseq [d (reverse (file-seq dir))]
        (when (.isDirectory d)
          (io/delete-file d))))))

(defn test-fixture [f]
  (cleanup-test-output)
  (f)
  (cleanup-test-output))

(use-fixtures :each test-fixture)

(deftest ^:unit generate-customer-module-test
  (testing "generates complete customer module"
    (let [svc (service/create-scaffolder-service)

          request {:module-name "customer"
                   :entities [{:name "Customer"
                               :fields [{:name :name
                                         :type :string
                                         :required true}
                                        {:name :email
                                         :type :email
                                         :required true
                                         :unique true}
                                        {:name :phone
                                         :type :string
                                         :required false}
                                        {:name :active
                                         :type :boolean
                                         :required true
                                         :default true}]}]
                   :interfaces {:http true :cli true :web true}
                   :features {:audit true :pagination true}
                   :dry-run true}  ;; Always dry-run in tests

          result (ports/generate-module svc request)]

      ;; Check result structure
      (is (true? (:success result)))
      (is (= "customer" (:module-name result)))
      (is (= 13 (count (:files result))))

      ;; Check files are listed in result
      (is (some #(str/ends-with? (:path %) "schema.clj") (:files result)))
      (is (some #(str/ends-with? (:path %) "ports.clj") (:files result)))
      (is (some #(str/ends-with? (:path %) "customer.clj") (:files result)))
      (is (some #(str/ends-with? (:path %) "ui.clj") (:files result)))
      (is (some #(str/ends-with? (:path %) "service.clj") (:files result)))
      (is (some #(str/ends-with? (:path %) "persistence.clj") (:files result)))
      (is (some #(str/ends-with? (:path %) "http.clj") (:files result)))
      (is (some #(str/ends-with? (:path %) "web_handlers.clj") (:files result)))
      (is (some #(str/includes? (:path %) "-create-customers.up.sql") (:files result)))
      ;; migratus only discovers `<id>-<name>.up.sql` / `.down.sql`; the old
      ;; `create_customers.sql` shape was silently never applied (BOU-256).
      (is (some #(str/includes? (:path %) "-create-customers.down.sql") (:files result)))
      (is (every? #(re-find #"migrations/\d{14}-" (:path %))
                  (filter #(str/includes? (:path %) "migrations/") (:files result))))

      ;; Check schema file content
      (let [schema-file (first (filter #(str/ends-with? (:path %) "schema.clj") (:files result)))]
        (is (some? schema-file))
        (is (str/includes? (:content schema-file) "(ns wagoe.customer.schema"))
        (is (str/includes? (:content schema-file) "(def Customer"))
        (is (str/includes? (:content schema-file) "(def CreateCustomerRequest"))
        (is (str/includes? (:content schema-file) "(def UpdateCustomerRequest")))

      ;; Check ports file content
      (let [ports-file (first (filter #(str/ends-with? (:path %) "ports.clj") (:files result)))]
        (is (some? ports-file))
        (is (str/includes? (:content ports-file) "(defprotocol ICustomerRepository"))
        (is (str/includes? (:content ports-file) "(defprotocol ICustomerService")))

      ;; Check core file content
      (let [core-file (first (filter #(str/includes? (:path %) "core/customer.clj") (:files result)))]
        (is (some? core-file))
        (is (str/includes? (:content core-file) "(defn prepare-new-customer"))
        (is (str/includes? (:content core-file) "(defn apply-customer-update")))

      ;; Check migration file content
      (let [migration-file (first (filter #(str/includes? (:path %) "-create-customers.up.sql") (:files result)))]
        (is (some? migration-file))
        (is (str/includes? (:content migration-file) "CREATE TABLE IF NOT EXISTS customers"))
        (is (str/includes? (:content migration-file) "name VARCHAR(255) NOT NULL"))
        (is (str/includes? (:content migration-file) "email VARCHAR(255) NOT NULL UNIQUE"))
        (is (str/includes? (:content migration-file) "phone VARCHAR(255)"))
        (is (str/includes? (:content migration-file) "active BOOLEAN NOT NULL"))))))

(deftest ^:unit generate-module-dry-run-test
  (testing "dry run does not write files"
    (let [svc (service/create-scaffolder-service)

          request {:module-name "test-module"
                   :entities [{:name "TestEntity"
                               :fields [{:name :name :type :string}]}]
                   :interfaces {:http true}
                   :dry-run true}

          result (ports/generate-module svc request)]

      ;; Check result
      (is (true? (:success result)))
      (is (= 13 (count (:files result))))
      (is (some #(str/includes? % "Dry run") (:warnings result))))))

(deftest ^:unit generate-module-validation-test
  (testing "validates request schema"
    (let [svc (service/create-scaffolder-service)

          ;; Invalid request - missing required fields
          invalid-request {:module-name "test"}

          result (ports/generate-module svc invalid-request)]

      ;; Check error result
      (is (false? (:success result)))
      (is (seq (:errors result))))))

;; =============================================================================
;; add-field command tests
;; =============================================================================

(deftest ^:unit add-field-test
  (testing "generates migration for adding a field"
    (let [svc (service/create-scaffolder-service)

          request {:module-name "product"
                   :entity "Product"
                   :field {:name :description
                           :type :text
                           :required false
                           :unique false}
                   :dry-run true}  ;; Always dry-run in tests

          result (ports/add-field svc request)]

      (is (true? (:success result)))
      (is (= "product" (:module-name result)))
      (is (= 3 (count (:files result))))

      ;; Check migration file information
      (let [migration-file (first (filter #(str/starts-with? (:path %) "migrations/")
                                          (:files result)))]
        (is (some? migration-file))
        (is (str/includes? (:path migration-file) "-add-description-to-products.up.sql"))
        (is (str/includes? (:content migration-file) "ALTER TABLE"))
        (is (str/includes? (:content migration-file) "ADD COLUMN description"))))))

(deftest ^:unit add-field-dry-run-test
  (testing "dry run does not write migration file"
    (let [svc (service/create-scaffolder-service)

          request {:module-name "product"
                   :entity "Product"
                   :field {:name :sku
                           :type :string
                           :required true
                           :unique true}
                   :dry-run true}

          result (ports/add-field svc request)]

      (is (true? (:success result)))
      (is (some #(str/includes? % "Dry run") (:warnings result))))))

;; =============================================================================
;; add-endpoint command tests
;; =============================================================================

(deftest ^:unit add-endpoint-test
  (testing "generates endpoint instructions"
    (let [svc (service/create-scaffolder-service)

          request {:module-name "product"
                   :path "/products/export"
                   :method :get
                   :handler-name "export-products"
                   :dry-run true}  ;; Always dry-run in tests

          result (ports/add-endpoint svc request)]

      (is (true? (:success result)))
      (is (= "product" (:module-name result)))
      (is (= 1 (count (:files result))))

      ;; Check instructions content
      (let [http-file (first (:files result))]
        (is (str/ends-with? (:path http-file) "http.clj"))
        (is (str/includes? (:content http-file) "/products/export"))
        (is (str/includes? (:content http-file) ":get"))
        (is (str/includes? (:content http-file) "export-products"))))))

;; =============================================================================
;; add-adapter command tests
;; =============================================================================

(deftest ^:unit add-adapter-test
  (testing "generates adapter implementation file"
    (let [svc (service/create-scaffolder-service)

          request {:module-name "notifications"
                   :port "INotificationSender"
                   :adapter-name "slack"
                   :methods [{:name "send-notification" :args ["user-id" "message"]}
                             {:name "send-bulk" :args ["user-ids" "message"]}]
                   :dry-run true}  ;; Always dry-run in tests

          result (ports/add-adapter svc request)]

      (is (true? (:success result)))
      (is (= "notifications" (:module-name result)))
      (is (= 1 (count (:files result))))

      ;; Check adapter file information
      (let [adapter-file (first (:files result))]
        (is (str/ends-with? (:path adapter-file) "slack.clj"))
        (is (str/includes? (:path adapter-file) "adapters/"))

        ;; Check file content
        (is (str/includes? (:content adapter-file) "defrecord Slack")) ;; Record name is based on adapter-name
        (is (str/includes? (:content adapter-file) "INotificationSender"))
        (is (str/includes? (:content adapter-file) "send-notification"))
        (is (str/includes? (:content adapter-file) "send-bulk"))))))

(deftest ^:unit add-adapter-dry-run-test
  (testing "dry run does not write adapter file"
    (let [svc (service/create-scaffolder-service)

          request {:module-name "storage"
                   :port "IFileStorage"
                   :adapter-name "s3"
                   :methods [{:name "store-file" :args ["path" "content"]}]
                   :dry-run true}

          result (ports/add-adapter svc request)]

      (is (true? (:success result)))
      (is (some #(str/includes? % "Dry run") (:warnings result))))))

;; =============================================================================
;; base-ns path parameterization (BOU-205) — endpoint + adapter honour --base-ns
;; =============================================================================

(deftest ^:unit add-endpoint-path-honours-base-ns
  (let [svc (service/create-scaffolder-service)
        req {:module-name "product" :path "/p" :method :get
             :handler-name "h" :dry-run true}]
    (testing "default base-ns -> src/wagoe/<module>/"
      (let [p (:path (first (:files (ports/add-endpoint svc req))))]
        (is (= "src/wagoe/product/shell/http.clj" p))))
    (testing "custom base-ns -> src/<base-ns>/<module>/"
      (let [p (:path (first (:files (ports/add-endpoint svc (assoc req :base-ns "myapp")))))]
        (is (= "src/myapp/product/shell/http.clj" p))))))

(deftest ^:unit add-adapter-path-and-ns-honour-base-ns
  (let [svc (service/create-scaffolder-service)
        req {:module-name "notifications" :port "INotificationSender"
             :adapter-name "slack" :methods [{:name "send" :args ["x"]}]
             :dry-run true}]
    (testing "default base-ns -> path + adapter ns under wagoe"
      (let [f (first (:files (ports/add-adapter svc req)))]
        (is (= "src/wagoe/notifications/shell/adapters/slack.clj" (:path f)))
        (is (str/includes? (:content f) "(ns wagoe.notifications.shell.adapters.slack"))))
    (testing "custom base-ns -> path + adapter ns under <base-ns>"
      (let [f (first (:files (ports/add-adapter svc (assoc req :base-ns "myapp"))))]
        (is (= "src/myapp/notifications/shell/adapters/slack.clj" (:path f)))
        (is (str/includes? (:content f) "(ns myapp.notifications.shell.adapters.slack"))))))

(deftest ^:unit migration-ids-do-not-collide-within-a-second
  ;; Second-precision ids are not unique on their own: two scaffold operations
  ;; in the same second produce different filenames sharing one id, and migratus
  ;; throws "Multiple migrations with id N" — which fails the entire migration
  ;; run, not just the offending pair.
  (let [next-id #'service/next-migration-id]

    (testing "a free timestamp is used as-is"
      (is (= "20260801120000" (next-id #{} "20260801120000"))))

    (testing "a taken timestamp steps to the next free second"
      (is (= "20260801120001" (next-id #{"20260801120000"} "20260801120000"))))

    (testing "consecutive collisions keep stepping"
      (is (= "20260801120003"
             (next-id #{"20260801120000" "20260801120001" "20260801120002"}
                      "20260801120000"))))

    (testing "ids stay 14 digits — a longer id would sort after every existing migration forever"
      (is (= 14 (count (next-id #{"20260801120000"} "20260801120000")))))

    (testing "unrelated ids do not push the timestamp forward"
      (is (= "20260801120000" (next-id #{"19990101000000" "20250101000000"} "20260801120000"))))))

;; =============================================================================
;; BOU-275: the report has to match what happened
;; =============================================================================
;;
;; `bb scaffold field` listed `:update: src/…/schema.clj` and never opened the
;; file. The entry was appended to `:files` unconditionally while the write loop
;; only handled `:action :create`, so the report was composed independently of
;; the work — which is how it drifted, and how it stayed wrong.
;;
;; Every test above passes `:dry-run true` ("Always dry-run in tests"), so
;; nothing here ever wrote a file, and nothing ever compared the report to the
;; filesystem. That is the gap these close: they write for real, into a temp
;; directory, and diff the two.

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "wagoe-scaffolder-test"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [dir]
  (doseq [f (reverse (file-seq dir))] (.delete f)))

(defn- relative-to
  "Path of `f` relative to `dir`.

   nio relativize on canonical paths, not string arithmetic on lengths: an
   earlier version subtracted a prefix length and produced \"own.sql\" for
   .../add-colour-to-widgets.down.sql, because macOS reaches the temp directory
   through a /var -> /private/var symlink and the two paths did not share the
   prefix it assumed."
  [dir f]
  (str (.relativize (.toPath (io/file (.getCanonicalPath dir)))
                    (.toPath (io/file (.getCanonicalPath f))))))

(defn- files-on-disk
  "Every file under `dir`, as paths relative to it."
  [dir]
  (set (for [f (file-seq dir) :when (.isFile f)] (relative-to dir f))))

(defn- reported
  "Reported paths for `action`, relative to `dir`."
  [result dir action]
  (set (for [e (:files result) :when (= action (:action e))]
         (relative-to dir (io/file (:path e))))))

(deftest ^:unit generated-report-matches-the-filesystem
  (testing "every file reported as created exists, and every file created is reported"
    (let [dir (temp-dir)]
      (try
        (let [svc    (service/create-scaffolder-service)
              result (ports/generate-module
                      svc
                      {:module-name "widget"
                       :entities [{:name "Widget"
                                   :fields [{:name :label :type :string :required true}]}]
                       :interfaces {:http true :cli true :web true}
                       :features {:audit true :pagination true}
                       :output-dir (.getPath dir)
                       :dry-run false})]
          (is (true? (:success result)))
          (is (= (files-on-disk dir) (reported result dir :create))
              "the report and the working tree must not be able to disagree"))
        (finally (delete-tree! dir))))))

(deftest ^:unit add-field-report-matches-the-filesystem
  (testing "the schema file is reported as updated only when it was updated"
    (let [dir (temp-dir)]
      (try
        (let [svc (service/create-scaffolder-service)
              _   (ports/generate-module
                   svc {:module-name "widget"
                        :entities [{:name "Widget"
                                    :fields [{:name :label :type :string :required true}]}]
                        :interfaces {:http true :cli true :web true}
                        :features {:audit true :pagination true}
                        :output-dir (.getPath dir) :dry-run false})
              before (files-on-disk dir)
              result (ports/add-field
                      svc {:module-name "widget" :entity "Widget"
                           :field {:name :colour :type :string :required false :unique false}
                           :output-dir (.getPath dir) :dry-run false})
              after  (files-on-disk dir)
              schema (first (filter #(str/ends-with? (:path %) "schema.clj") (:files result)))]
          (is (true? (:success result)))
          (is (= :update (:action schema))
              "this said :update while never opening the file — the whole bug")
          (is (= (reported result dir :create) (clojure.set/difference after before))
              "new files on disk are exactly the ones reported as created")
          (is (str/includes? (slurp (io/file (:path schema))) "[:colour {:optional true} :string]")
              "and :update has to mean the field is actually in the file"))
        (finally (delete-tree! dir))))))

(deftest ^:unit add-field-dry-run-claims-nothing
  (testing "a dry run reports no file as created or updated, and writes none"
    (let [dir (temp-dir)]
      (try
        (let [svc (service/create-scaffolder-service)
              _   (ports/generate-module
                   svc {:module-name "widget"
                        :entities [{:name "Widget"
                                    :fields [{:name :label :type :string :required true}]}]
                        :interfaces {:http true :cli true :web true}
                        :features {:audit true :pagination true}
                        :output-dir (.getPath dir) :dry-run false})
              before (files-on-disk dir)
              result (ports/add-field
                      svc {:module-name "widget" :entity "Widget"
                           :field {:name :colour :type :string :required false :unique false}
                           :output-dir (.getPath dir) :dry-run true})]
          ;; The dry-run path had the same defect one branch over: it printed
          ;; ":create: migrations/…up.sql" for two files it deliberately did
          ;; not write.
          (is (empty? (filter #(#{:create :update} (:action %)) (:files result)))
              "nothing was written, so nothing may be reported as written")
          (is (= before (files-on-disk dir)) "a dry run must not touch the tree")
          (is (some #(str/includes? % "Dry run") (:warnings result))))
        (finally (delete-tree! dir))))))

(deftest ^:unit output-dir-is-honoured
  (testing "files land in --output-dir, not in the working directory"
    ;; The CLI accepted --output-dir and passed it; the service ignored it and
    ;; wrote into the current project instead. Measured before the fix:
    ;; 0 files in the target directory, a full module in the cwd.
    (let [dir (temp-dir)]
      (try
        (let [svc    (service/create-scaffolder-service)
              result (ports/generate-module
                      svc {:module-name "widget"
                           :entities [{:name "Widget"
                                       :fields [{:name :label :type :string :required true}]}]
                           :interfaces {:http true :cli true :web true}
                           :features {:audit true :pagination true}
                           :output-dir (.getPath dir) :dry-run false})]
          (is (seq (files-on-disk dir)) "the target directory received the module")
          (is (every? #(str/starts-with? (:path %) (.getPath dir)) (:files result))
              "and the report names where they actually are"))
        (finally (delete-tree! dir))))))
