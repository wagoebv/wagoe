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
      (is (= 14 (count (:files result))))

      ;; Named, not just counted: a count that goes up tells you nothing about
      ;; which file appeared, and the one that was missing for so long —
      ;; module_wiring.clj — is the one integrate needs (BOU-309).
      (is (some #(str/ends-with? (:path %) "shell/module_wiring.clj") (:files result))
          "the module must ship its own Integrant wiring")

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
      (is (= 14 (count (:files result))))
      (is (some #(str/ends-with? (:path %) "shell/module_wiring.clj") (:files result)))
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

(deftest ^:unit migration-ids-are-unique-within-the-output-dir
  ;; The collision guard scanned `migrations/` and `resources/migrations/` in
  ;; the working directory only, so --output-dir defeated it: two runs into the
  ;; same directory inside one second saw no existing ids, took the same
  ;; timestamp, and wrote two different migrations under one id. migratus then
  ;; refuses the whole run, which is BOU-256's failure mode again:
  ;;
  ;;   Multiple migrations with id 20260806060706 ("create-betas" "create-alphas")
  (testing "consecutive generates into one directory get distinct ids"
    (let [dir (temp-dir)]
      (try
        (let [svc (service/create-scaffolder-service)
              gen (fn [n]
                    (ports/generate-module
                     svc {:module-name n
                          :entities [{:name (str/capitalize n)
                                      :fields [{:name :x :type :string :required true}]}]
                          :interfaces {:http true :cli true :web true}
                          :features {:audit true :pagination true}
                          :output-dir (.getPath dir) :dry-run false}))
              _   (dorun (map gen ["alpha" "beta" "gamma"]))
              ids (->> (files-on-disk dir)
                       (filter #(str/starts-with? % "migrations/"))
                       (keep #(second (re-find #"migrations/(\d+)-" %)))
                       set)]
          (is (= 3 (count ids))
              "three modules, three ids — sharing one makes migratus reject every migration"))
        (finally (delete-tree! dir))))))

(deftest ^:unit dry-run-previews-the-output-dir
  ;; The dry-run branch kept the original relative paths while the write branch
  ;; reported resolved ones, so `--dry-run --output-dir /tmp/x` previewed a run
  ;; into the working directory — a description of something that would not
  ;; happen, which is the same class of defect as the false :update this ticket
  ;; is about.
  (testing "previewed paths point where the files would actually be written"
    (let [dir (temp-dir)]
      (try
        (let [svc    (service/create-scaffolder-service)
              result (ports/generate-module
                      svc {:module-name "widget"
                           :entities [{:name "Widget"
                                       :fields [{:name :label :type :string :required true}]}]
                           :interfaces {:http true :cli true :web true}
                           :features {:audit true :pagination true}
                           :output-dir (.getPath dir) :dry-run true})]
          (is (every? #(str/starts-with? (:path %) (.getPath dir)) (:files result))
              "a preview that names the wrong directory is worse than none")
          (is (empty? (files-on-disk dir)) "and it still must not write anything"))
        (finally (delete-tree! dir))))))

(deftest ^:unit dry-run-schema-note-follows-the-real-outcome
  ;; A dedicated dry-run arm short-circuited ahead of the computed edit and
  ;; always said it "would add the field to the entity and request schemas".
  ;; Against a hand-restructured schema the real run then reported it could not,
  ;; and added a manual step the preview never mentioned — a preview
  ;; contradicting the run it previews, which is the false-success report this
  ;; ticket exists to remove.
  (testing "an unplaceable schema is previewed as unplaceable, not as a success"
    (let [dir (temp-dir)]
      (try
        (let [svc (service/create-scaffolder-service)
              _   (ports/generate-module
                   svc {:module-name "box"
                        :entities [{:name "Box"
                                    :fields [{:name :w :type :string :required true}]}]
                        :interfaces {:http true :cli true :web true}
                        :features {:audit true :pagination true}
                        :output-dir (.getPath dir) :dry-run false})
              schema (io/file dir "src/wagoe/box/schema.clj")
              ;; Restructure the request schemas beyond what the inserter reads.
              ;; Matches whatever the entry looks like — the update schema now
              ;; carries {:optional true}, and a regex pinned to the old shape
              ;; silently restructured only one of the two.
              _   (spit schema (str/replace (slurp schema)
                                            #"\(def (Create|Update)BoxRequest\n[^\n]*\n  \[:map \{:title \"[^\"]+\"\}\n   \[:w [^\n]*\]\]\)"
                                            "(def $1BoxRequest\n  \"hand-restructured\"\n  (m/schema [:map [:w :string]]))"))
              req {:module-name "box" :entity "Box"
                   :field {:name :h :type :string :required false :unique false}
                   :output-dir (.getPath dir)}
              preview (ports/add-field svc (assoc req :dry-run true))
              real    (ports/add-field svc (assoc req :dry-run false))
              entry-of #(first (filter (fn [f] (str/ends-with? (:path f) "schema.clj")) (:files %)))
              p (entry-of preview)
              r (entry-of real)]
          (is (= (:manual? p) (:manual? r))
              "the preview must agree with the run about whether work remains")
          (is (= (:manual-note p) (:manual-note r))
              "and about what that work is")
          (is (str/includes? (:note p) "could not place")
              "not 'would add the field to the entity and request schemas'")
          (is (str/starts-with? (:note p) "dry run — ")
              "while still being marked as a preview"))
        (finally (delete-tree! dir))))))

(deftest ^:unit dry-run-previews-a-successful-schema-edit-as-such
  (testing "a healthy schema is previewed as an edit that would happen"
    (let [dir (temp-dir)]
      (try
        (let [svc (service/create-scaffolder-service)
              _   (ports/generate-module
                   svc {:module-name "box"
                        :entities [{:name "Box"
                                    :fields [{:name :w :type :string :required true}]}]
                        :interfaces {:http true :cli true :web true}
                        :features {:audit true :pagination true}
                        :output-dir (.getPath dir) :dry-run false})
              before (slurp (io/file dir "src/wagoe/box/schema.clj"))
              result (ports/add-field
                      svc {:module-name "box" :entity "Box"
                           :field {:name :h :type :string :required false :unique false}
                           :output-dir (.getPath dir) :dry-run true})
              entry  (first (filter #(str/ends-with? (:path %) "schema.clj") (:files result)))]
          (is (= :skip (:action entry)) "a preview writes nothing")
          (is (str/includes? (:note entry) "would add") "and says what it would do")
          (is (str/includes? (:note entry) "CreateBoxRequest"))
          (is (nil? (:manual? entry)) "nothing would be left over")
          (is (= before (slurp (io/file dir "src/wagoe/box/schema.clj")))
              "and the file is untouched"))
        (finally (delete-tree! dir))))))

(deftest ^:unit manual-instructions-keep-update-requests-optional
  ;; The manual note reused one entry string for every target, so a --required
  ;; field produced "add [:sku :string] to UpdateItemRequest". That note is the
  ;; only instruction the user gets, and following it makes the field mandatory
  ;; on every partial update — the tool causing, by instruction, the breakage
  ;; the file edit was changed to avoid.
  (testing "an unplaceable update schema is quoted in its optional form"
    (let [dir (temp-dir)]
      (try
        (let [svc (service/create-scaffolder-service)
              _   (ports/generate-module
                   svc {:module-name "item"
                        :entities [{:name "Item"
                                    :fields [{:name :name :type :string :required true}]}]
                        :interfaces {:http true :cli true :web true}
                        :features {:audit true :pagination true}
                        :output-dir (.getPath dir) :dry-run false})
              schema (io/file dir "src/wagoe/item/schema.clj")
              _   (spit schema (str/replace (slurp schema)
                                            #"\(def UpdateItemRequest\n[^\n]*\n  \[:map \{:title \"[^\"]+\"\}\n   \[:name[^\n]*\]\]\)"
                                            "(def UpdateItemRequest\n  \"hand-restructured\"\n  (m/schema [:map [:name {:optional true} :string]]))"))
              result (ports/add-field
                      svc {:module-name "item" :entity "Item"
                           :field {:name :sku :type :string :required true :unique false}
                           :output-dir (.getPath dir) :dry-run false})
              entry  (first (filter #(str/ends-with? (:path %) "schema.clj") (:files result)))]
          (is (:manual? entry))
          (is (str/includes? (:manual-note entry) "[:sku {:optional true} :string]")
              "the update request must be quoted optional")
          (is (not (str/includes? (:manual-note entry) "add [:sku :string] to UpdateItemRequest"))
              "following the required form here breaks every partial update"))
        (finally (delete-tree! dir)))))

  (testing "a missing schema file names each target with the form it needs"
    (let [dir (temp-dir)]
      (try
        (let [svc    (service/create-scaffolder-service)
              result (ports/add-field
                      svc {:module-name "ghost" :entity "Ghost"
                           :field {:name :sku :type :string :required true :unique false}
                           :output-dir (.getPath dir) :dry-run false})
              entry  (first (filter #(str/ends-with? (:path %) "schema.clj") (:files result)))
              note   (:manual-note entry)]
          (is (:manual? entry))
          (is (str/includes? note "[:sku :string] to Ghost, CreateGhostRequest"))
          (is (str/includes? note "[:sku {:optional true} :string] to UpdateGhostRequest")))
        (finally (delete-tree! dir)))))

  (testing "the description of what was written is per target too"
    (let [dir (temp-dir)]
      (try
        (let [svc (service/create-scaffolder-service)
              _   (ports/generate-module
                   svc {:module-name "tag"
                        :entities [{:name "Tag"
                                    :fields [{:name :label :type :string :required true}]}]
                        :interfaces {:http true :cli true :web true}
                        :features {:audit true :pagination true}
                        :output-dir (.getPath dir) :dry-run false})
              result (ports/add-field
                      svc {:module-name "tag" :entity "Tag"
                           :field {:name :slug :type :string :required true :unique false}
                           :output-dir (.getPath dir) :dry-run false})
              entry  (first (filter #(str/ends-with? (:path %) "schema.clj") (:files result)))
              file   (slurp (io/file dir "src/wagoe/tag/schema.clj"))]
          ;; The note claimed one form for all three schemas while the file
          ;; held two — a report that does not match the edit it describes.
          (is (str/includes? (:note entry) "[:slug :string] to Tag, CreateTagRequest"))
          (is (str/includes? (:note entry) "[:slug {:optional true} :string] to UpdateTagRequest"))
          (is (str/includes? file "[:slug {:optional true} :string]")
              "and the file agrees"))
        (finally (delete-tree! dir))))))

(deftest ^:unit mixed-remaining-work-is-reported-in-full
  ;; A run that inserts into one schema, cannot edit another, and finds the
  ;; update request still required must name both problems. The two `assoc`
  ;; clauses that built :manual-note overwrote each other, so only the later
  ;; one survived.
  (testing "the instruction covers unplaceable and wrongly-shaped targets"
    (let [dir (temp-dir)]
      (try
        (let [svc (service/create-scaffolder-service)
              _   (ports/generate-module
                   svc {:module-name "item"
                        :entities [{:name "Item"
                                    :fields [{:name :name :type :string :required true}]}]
                        :interfaces {:http true :cli true :web true}
                        :features {:audit true :pagination true}
                        :output-dir (.getPath dir) :dry-run false})
              schema (io/file dir "src/wagoe/item/schema.clj")
              _   (spit schema
                        (-> (slurp schema)
                            ;; Create becomes unplaceable …
                            (str/replace #"\(def CreateItemRequest\n[^\n]*\n  \[:map \{:title \"[^\"]+\"\}\n   \[:name[^\n]*\]\]\)"
                                         "(def CreateItemRequest\n  \"hand-restructured\"\n  (m/schema [:map [:name :string]]))")
                            ;; … and Update already carries the required form.
                            (str/replace "   [:name {:optional true} :string]])"
                                         "   [:name {:optional true} :string]\n   [:sku :string]])")))
              result (ports/add-field
                      svc {:module-name "item" :entity "Item"
                           :field {:name :sku :type :string :required true :unique false}
                           :output-dir (.getPath dir) :dry-run false})
              entry  (first (filter #(str/ends-with? (:path %) "schema.clj") (:files result)))
              note   (:manual-note entry)]
          (is (:manual? entry))
          (is (str/includes? note "CreateItemRequest")
              "the unplaceable schema must not be dropped")
          (is (str/includes? note "UpdateItemRequest")
              "nor the one that has it in the wrong shape")
          (is (str/includes? note "[:sku {:optional true} :string]")
              "and the update request is quoted optional")
          (is (str/includes? (:note entry) "CreateItemRequest")
              "the description names both problems too")
          (is (str/includes? (:note entry) "not as optional")))
        (finally (delete-tree! dir))))))

(deftest ^:unit manual-instructions-name-the-output-dir-path
  ;; The file list resolved through --output-dir while the instruction appended
  ;; the cwd-relative path, so the two lines named different files and the one
  ;; the user acts on pointed at the current project.
  (testing "the instruction names the same file as the report"
    (let [dir (temp-dir)]
      (try
        (let [svc    (service/create-scaffolder-service)
              result (ports/add-field
                      svc {:module-name "item" :entity "Item"
                           :field {:name :sku :type :string :required true :unique false}
                           :output-dir (.getPath dir) :dry-run false})
              entry  (first (filter #(str/ends-with? (:path %) "schema.clj") (:files result)))]
          (is (:manual? entry) "no schema file there, so there is manual work")
          (is (str/starts-with? (:path entry) (.getPath dir)))
          (is (str/includes? (:manual-note entry) (:path entry))
              "the instruction has to point at the file the report names"))
        (finally (delete-tree! dir)))))

  (testing "without an output dir the path stays relative"
    ;; Absolute paths everywhere would be correct but noisy for the common case.
    (let [svc    (service/create-scaffolder-service)
          result (ports/add-field
                  svc {:module-name "nonexistent-module" :entity "Nope"
                       :field {:name :sku :type :string :required false :unique false}
                       :dry-run true})
          entry  (first (filter #(str/ends-with? (:path %) "schema.clj") (:files result)))]
      (is (= "src/wagoe/nonexistent-module/schema.clj" (:path entry)))
      (is (str/includes? (:manual-note entry) "src/wagoe/nonexistent-module/schema.clj")))))

(deftest ^:unit next-steps-point-at-the-generated-project
  ;; The migrations and the schema edit went under --output-dir while the
  ;; persistence step named a path relative to the working directory, and the
  ;; two commands would have run against whichever project the shell was in.
  ;; Persistence transforms are the one part of a field change that cannot be
  ;; generated, so sending the user to the wrong file is how the field ends up
  ;; reading back nil with nothing reporting a problem.
  (testing "with an output dir, every step names it"
    (let [dir (temp-dir)]
      (try
        (let [svc (service/create-scaffolder-service)
              _   (ports/generate-module
                   svc {:module-name "item"
                        :entities [{:name "Item"
                                    :fields [{:name :name :type :string :required true}]}]
                        :interfaces {:http true :cli true :web true}
                        :features {:audit true :pagination true}
                        :output-dir (.getPath dir) :dry-run false})
              result (ports/add-field
                      svc {:module-name "item" :entity "Item"
                           :field {:name :sku :type :string :required false :unique false}
                           :output-dir (.getPath dir) :dry-run false})
              steps  (:next-steps result)
              persistence (first (filter #(str/includes? % "persistence.clj") steps))]
          (is (str/includes? persistence (.getPath dir))
              "the file the user has to edit by hand must be the generated one")
          ;; The step is a sentence; pull the path out of it and check something
          ;; is actually there. A path nothing is at is no better than a wrong one.
          (let [path (second (re-find #"transforms in (\S+)" persistence))]
            (is (.isFile (io/file path)) (str "no file at " path)))
          (doseq [cmd (filter #(str/includes? % "clojure -M:") steps)]
            (is (str/includes? cmd (.getPath dir))
                (str "command runs against the wrong project: " cmd))))
        (finally (delete-tree! dir)))))

  (testing "without one, the steps stay relative"
    (let [svc    (service/create-scaffolder-service)
          result (ports/add-field
                  svc {:module-name "widget" :entity "Widget"
                       :field {:name :sku :type :string :required false :unique false}
                       :dry-run true})
          steps  (:next-steps result)]
      (is (some #(str/includes? % "src/wagoe/widget/shell/persistence.clj") steps))
      (is (not-any? #(str/includes? % "(from ") steps)
          "no directory suffix when there is no directory to name"))))

;; =============================================================================
;; Overwrite protection (BOU-308)
;; =============================================================================

(defn- tmp-dir []
  (let [d (java.io.File/createTempFile "scaffold" "")]
    (.delete d) (.mkdirs d) d))

(defn- rm-r [^java.io.File f]
  (when (.isDirectory f) (run! rm-r (.listFiles f)))
  (.delete f))

(def ^:private note-request
  {:module-name "notes"
   :entities [{:name "Note"
               :fields [{:name :body :type :string :required true}]}]
   :interfaces {:http true}})

(deftest ^:integration regenerating-does-not-silently-overwrite
  ;; The framework's most-recommended command destroyed a day's work without a
  ;; prompt, a backup or a non-zero exit. --force was declared in the CLI,
  ;; threaded into the request, and read by nothing.
  (let [dir (tmp-dir)
        svc (service/create-scaffolder-service)]
    (try
      (let [first-run (ports/generate-module svc (assoc note-request :output-dir (.getPath dir)))
            edited    (io/file dir "src/wagoe/notes/core/note.clj")]
        (is (true? (:success first-run)))
        (spit edited (str (slurp edited) "\n;; hand-written, must survive\n"))

        (testing "a re-run without --force refuses, and touches nothing"
          (let [again (ports/generate-module svc (assoc note-request :output-dir (.getPath dir)))]
            (is (false? (:success again)) "must not report success")
            (is (str/includes? (slurp edited) "hand-written, must survive")
                "the edited file must be untouched")))

        (testing "and names the files it would have overwritten"
          (let [again (ports/generate-module svc (assoc note-request :output-dir (.getPath dir)))]
            (is (seq (:existing-files again)))
            (is (some #(str/includes? % "note.clj") (:existing-files again))
                "a refusal that does not say which files is not actionable")))

        (testing "--force overwrites, and says which files it replaced"
          (let [forced (ports/generate-module svc (assoc note-request
                                                        :output-dir (.getPath dir)
                                                        :force true))]
            (is (true? (:success forced)))
            (is (not (str/includes? (slurp edited) "hand-written, must survive")))
            (is (some #(= :overwrite (:action %)) (:files forced))
                "an overwritten file must not be reported as :create"))))
      (finally (rm-r dir)))))

(deftest ^:integration a-dry-run-never-refuses
  ;; It writes nothing, so existing files are not at risk and the preview is
  ;; still useful on a module that already exists.
  (let [dir (tmp-dir)
        svc (service/create-scaffolder-service)]
    (try
      (ports/generate-module svc (assoc note-request :output-dir (.getPath dir)))
      (let [preview (ports/generate-module svc (assoc note-request
                                                     :output-dir (.getPath dir)
                                                     :dry-run true))]
        (is (true? (:success preview)))
        (is (every? #(= :skip (:action %)) (:files preview))))
      (finally (rm-r dir)))))
