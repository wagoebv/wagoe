(ns wagoe.storage.shell.export-jobs-test
  "Data export as a background job, over a real store (WAGOE_TEST_DB), the
   local adapter and the in-memory job queue."
  (:require [clojure.test :refer [deftest is testing]]
            [reitit.ring :as ring]
            [support.test-db :as test-db]
            [wagoe.jobs.ports :as job-ports]
            [wagoe.jobs.shell.adapters.in-memory :as in-memory]
            [wagoe.platform.ports.privacy :as privacy]
            [wagoe.platform.shell.privacy :as platform-privacy]
            [wagoe.storage.ports :as ports]
            [wagoe.storage.shell.adapters.local :as local]
            [wagoe.storage.shell.data-export :as data-export]
            [wagoe.storage.shell.export-job-http :as http]
            [wagoe.storage.shell.export-job-store :as store]
            [wagoe.storage.shell.export-jobs :as sut]
            [wagoe.storage.shell.service :as service]
            [wagoe.storage.shell.upload-index :as upload-index])
  (:import [java.io ByteArrayInputStream InputStream]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Duration Instant]
           [java.util.zip ZipEntry ZipInputStream]))

(defn- with-export-jobs
  "Call `f` with {:ctx :service :index :app}: export jobs over a fresh
   database and directory, and an app serving their routes."
  [f]
  (test-db/with-db "export_jobs"
    (fn [db]
      (upload-index/create-tables! db)
      (store/create-tables! db)
      (store/create-tables! db)               ; the boot runs it every time
      (let [root    (str (Files/createTempDirectory "wagoe-export-jobs" (make-array FileAttribute 0)))
            storage (local/create-local-storage {:base-path root})
            index   (upload-index/create-upload-index db)
            ctx     {:store        (store/create-export-job-store db)
                     :file-storage storage
                     :provider     :local
                     :data-export  (platform-privacy/create-data-export
                                    {:storage (data-export/->UploadDataExporter index storage)})
                     :job-queue    (in-memory/create-in-memory-job-queue)
                     :queue        :default}]
        (f {:ctx     ctx
            :index   index
            :service (service/create-storage-service {:storage storage :upload-index index})
            :app     (ring/ring-handler (ring/router ["/api/v1" (http/routes ctx)]))})))))

(defn- call [app method uri user]
  (app {:request-method method :uri uri :user user :headers {}}))

(defn- request! [app user]
  (get-in (call app :post "/api/v1/me/export-jobs" user) [:body :id]))

(defn- run-queued-job!
  "Run the job on the queue, as a worker would."
  [{:keys [job-queue] :as ctx}]
  (let [job (job-ports/dequeue-job! job-queue :default "w1")]
    (is (= sut/job-type (:job-type job)))
    (sut/run-export-job ctx (:args job))))

(defn- zip-entries [^bytes bytes]
  (with-open [zip (ZipInputStream. (ByteArrayInputStream. bytes))]
    (into #{} (map #(.getName ^ZipEntry %)) (take-while some? (repeatedly #(.getNextEntry zip))))))

(defn- upload! [service user-id]
  (-> (service/upload-file service {:bytes (.getBytes "mine") :content-type "text/plain"}
                           {:filename "note.txt" :owner {:user-id user-id}} {})
      :data :key))

(deftest ^:integration a-user-asks-for-an-export-and-downloads-it-when-done
  (with-export-jobs
    (fn [{:keys [ctx service index app]}]
      (let [user {:id (random-uuid)}
            k    (upload! service (:id user))
            r    (call app :post "/api/v1/me/export-jobs" user)
            id   (get-in r [:body :id])]
        (is (= 202 (:status r)))
        (is (= :pending (get-in r [:body :status])))
        (is (nil? (get-in r [:body :download-path])))

        (testing "asking again while it is open returns the same job"
          (is (= id (request! app user))))

        (testing "nothing to download before the job ran"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No archive"
                                (call app :get (str "/api/v1/me/export-jobs/" id "/download") user))))

        (is (:success? (run-queued-job! ctx)))

        (let [status (call app :get (str "/api/v1/me/export-jobs/" id) user)
              dl     (call app :get (get-in status [:body :download-path]) user)]
          (is (= :done (get-in status [:body :status])))
          (is (= 200 (:status dl)))
          (is (= "no-store" (get-in dl [:headers "Cache-Control"])))
          (is (= #{"data-export.json" (str "storage/" k)}
                 (zip-entries (.readAllBytes ^InputStream (:body dl))))))

        (testing "the archive is not one of the user's uploads, so no later export holds it"
          (is (= [k] (map :key (ports/find-uploads index {:user-id (str (:id user))} {})))))

        (testing "once done, a new request is a new job"
          (is (not= id (request! app user))))))))

(deftest ^:integration ^:security a-job-is-only-visible-to-who-asked
  (with-export-jobs
    (fn [{:keys [ctx app]}]
      (let [owner {:id (random-uuid)}
            id    (request! app owner)]
        (run-queued-job! ctx)
        (doseq [path [(str "/api/v1/me/export-jobs/" id) (str "/api/v1/me/export-jobs/" id "/download")]]
          (is (= :not-found (:type (ex-data (is (thrown? clojure.lang.ExceptionInfo
                                                         (call app :get path {:id (random-uuid)})))))))
          (is (= :unauthorized (:type (ex-data (is (thrown? clojure.lang.ExceptionInfo
                                                            (call app :get path nil))))))))))))

(deftest ^:integration an-expired-archive-is-not-served
  (with-export-jobs
    (fn [{:keys [ctx app]}]
      (let [user {:id (random-uuid)}
            id   (request! app user)
            st   (:store ctx)]
        (run-queued-job! ctx)
        (ports/save-export-job! st (assoc (ports/find-export-job st id) :expires-at (.minusSeconds (Instant/now) 1)))
        (is (nil? (get-in (call app :get (str "/api/v1/me/export-jobs/" id) user) [:body :download-path])))
        (is (thrown? clojure.lang.ExceptionInfo (call app :get (str "/api/v1/me/export-jobs/" id "/download") user)))))))

(deftest ^:integration a-failed-export-says-so-and-does-not-block-the-next
  (with-export-jobs
    (fn [{:keys [ctx app]}]
      (let [user   {:id (random-uuid)}
            id     (request! app user)
            broken (assoc ctx :data-export (platform-privacy/create-data-export
                                            {:boom (reify privacy/IDataExporter
                                                     (export-data [_ _] (throw (ex-info "down" {:type :database-error}))))}))]
        (is (false? (:success? (run-queued-job! broken))))
        (is (= :failed (get-in (call app :get (str "/api/v1/me/export-jobs/" id) user) [:body :status])))
        (is (not= id (request! app user)))))))

(deftest ^:integration a-job-that-could-not-be-queued-is-not-left-open
  (with-export-jobs
    (fn [{:keys [ctx app]}]
      (let [user {:id (random-uuid)}]
        (with-redefs [job-ports/enqueue-job! (fn [& _] (throw (ex-info "queue down" {:type :internal-error})))]
          (is (thrown? clojure.lang.ExceptionInfo (request! app user))))
        (is (nil? (ports/open-export-job (:store ctx) {:type :user :id (:id user)})))))))

(deftest ^:integration a-stale-open-job-does-not-block-a-new-request
  (with-export-jobs
    (fn [{:keys [ctx app]}]
      (let [user {:id (random-uuid)}
            id   (request! app user)
            st   (:store ctx)]
        (ports/save-export-job! st (assoc (ports/find-export-job st id)
                                          :status :running
                                          :created-at (.minus (Instant/now) (Duration/ofDays 2))))
        (is (not= id (request! app user)))))))

(deftest ^:integration retention-deletes-old-archives-and-their-jobs
  (with-export-jobs
    (fn [{:keys [ctx app]}]
      (let [{:keys [store file-storage]} ctx
            id     (request! app {:id (random-uuid)})
            _      (run-queued-job! ctx)
            k      (:file-key (ports/find-export-job store id))
            policy (sut/->ExportArchiveRetention store file-storage)]
        (is (true? (ports/file-exists? file-storage k)))
        (is (zero? (privacy/purge-before! policy (.minusSeconds (Instant/now) 3600))))
        (is (= 1 (privacy/purge-before! policy (.plusSeconds (Instant/now) 60))))
        (is (false? (ports/file-exists? file-storage k)))
        (is (nil? (ports/find-export-job store id)))))))

(deftest ^:unit s3-and-gcs-redirect-to-a-short-lived-signed-url
  (let [signed (atom nil)
        storage (reify ports/IFileStorage
                  (generate-signed-url [_ k secs] (reset! signed [k secs]) (str "https://bucket/" k "?sig"))
                  (retrieve-file [_ _] (throw (AssertionError. "streamed instead")))
                  (store-file [_ _ _] nil)
                  (delete-file [_ _] false)
                  (file-exists? [_ _] false))
        r (sut/download-response {:file-storage storage :provider :s3} {:id "x" :file-key "data-exports/x.zip"})]
    (is (= 302 (:status r)))
    (is (= "https://bucket/data-exports/x.zip?sig" (get-in r [:headers "Location"])))
    (is (= ["data-exports/x.zip" 300] @signed))))
