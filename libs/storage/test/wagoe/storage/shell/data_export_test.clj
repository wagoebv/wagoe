(ns wagoe.storage.shell.data-export-test
  "Uploads in data export and upload retention, over a real index (WAGOE_TEST_DB)
   and the local adapter."
  (:require [clojure.test :refer [deftest is testing]]
            [support.test-db :as test-db]
            [wagoe.platform.ports.privacy :as privacy]
            [wagoe.storage.ports :as ports]
            [wagoe.storage.shell.adapters.local :as local]
            [wagoe.storage.shell.data-export :as data-export]
            [wagoe.storage.shell.service :as service]
            [wagoe.storage.shell.upload-index :as upload-index])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]
           [java.time.temporal ChronoUnit]))

(defn- with-storage
  "Call `f` with {:service :storage :index} on a fresh database and directory."
  [f]
  (test-db/with-db "storage_export"
    (fn [ctx]
      (upload-index/create-tables! ctx)
      (let [root    (str (Files/createTempDirectory "wagoe-export" (make-array FileAttribute 0)))
            storage (local/create-local-storage {:base-path root})
            index   (upload-index/create-upload-index ctx)]
        (f {:service (service/create-storage-service {:storage storage :upload-index index})
            :storage storage
            :index   index})))))

(defn- upload! [svc text owner]
  (-> (service/upload-file svc {:bytes (.getBytes ^String text) :content-type "text/plain"}
                           {:filename "note.txt" :owner owner} {})
      :data :key))

(deftest ^:integration a-subjects-uploads-are-exported-with-their-files
  (with-storage
    (fn [{:keys [service storage index]}]
      (let [user-id  (random-uuid)
            mine     (upload! service "mine" {:user-id user-id :tenant-id "t1"})
            _theirs  (upload! service "theirs" {:user-id (random-uuid)})
            exporter (data-export/->UploadDataExporter index storage)
            section  (privacy/export-data exporter {:type :user :id user-id})]
        (is (= [mine] (map :key (:uploads section))))
        (is (= {:size 4 :content-type "text/plain"} (select-keys (first (:uploads section)) [:size :content-type])))
        (is (inst? (:stored-at (first (:uploads section)))))
        (testing "a tenant's uploads"
          (is (= [mine] (map :key (:uploads (privacy/export-data exporter {:type :tenant :id "t1"})))))
          (is (= [] (:uploads (privacy/export-data exporter {:type :tenant :id "t2"})))))
        (testing "a subject type that owns no uploads"
          (is (nil? (privacy/export-data exporter {:type :order :id "o1"}))))
        (testing "the files behind the section"
          (let [[file] (privacy/export-files exporter {:type :user :id user-id} section)]
            (is (= mine (:path file)))
            (is (= "mine" (String. ^bytes ((:content file)))))
            (ports/delete-file storage mine)
            (is (nil? ((:content file))) "a file that is gone reads as nil")))))))

(deftest ^:integration removing-a-file-forgets-its-upload
  (with-storage
    (fn [{:keys [service index]}]
      (let [user-id (random-uuid)
            k       (upload! service "x" {:user-id user-id})]
        (is (true? (service/remove-file service k)))
        (is (empty? (ports/find-uploads index {:user-id user-id} {})))))))

(deftest ^:integration upload-retention-deletes-old-files-and-their-records
  (with-storage
    (fn [{:keys [service storage index]}]
      (let [user-id (random-uuid)
            old     (upload! service "old" {:user-id user-id})
            new     (upload! service "new" {:user-id user-id})
            day-ago (.minus (Instant/now) 1 ChronoUnit/DAYS)
            policy  (data-export/->UploadRetention index storage)]
        ;; Backdate one record: the index is what retention reads.
        (ports/record-upload! index (assoc (first (filter #(= old (:key %))
                                                          (ports/find-uploads index {:user-id user-id} {})))
                                           :stored-at (.minus day-ago 1 ChronoUnit/DAYS)))
        (is (= 1 (privacy/purge-before! policy day-ago)))
        (is (false? (ports/file-exists? storage old)))
        (is (true? (ports/file-exists? storage new)))
        (is (= [new] (map :key (ports/find-uploads index {:user-id user-id} {}))))
        (is (zero? (privacy/purge-before! policy day-ago)))))))

(deftest ^:integration a-file-storage-cannot-delete-keeps-its-record
  (with-storage
    (fn [{:keys [service storage index]}]
      (let [user-id (random-uuid)
            k       (upload! service "stuck" {:user-id user-id})
            stuck   (reify ports/IFileStorage
                      (delete-file [_ _] false)
                      (file-exists? [_ key] (ports/file-exists? storage key))
                      (store-file [_ _ _] nil)
                      (retrieve-file [_ _] nil)
                      (generate-signed-url [_ _ _] nil))]
        (is (thrown? clojure.lang.ExceptionInfo
                     (privacy/purge-before! (data-export/->UploadRetention index stuck)
                                            (.plusSeconds (Instant/now) 60))))
        (is (= [k] (map :key (ports/find-uploads index {:user-id user-id} {}))))))))

(deftest ^:integration one-file-that-will-not-delete-does-not-hold-back-the-rest
  (with-storage
    (fn [{:keys [service storage index]}]
      (let [user-id (random-uuid)
            stuck-k (upload! service "stuck" {:user-id user-id})
            other   (upload! service "other" {:user-id user-id})
            stuck   (reify ports/IFileStorage
                      (delete-file [_ key] (when-not (= key stuck-k) (ports/delete-file storage key)))
                      (file-exists? [_ key] (ports/file-exists? storage key))
                      (store-file [_ _ _] nil)
                      (retrieve-file [_ _] nil)
                      (generate-signed-url [_ _ _] nil))]
        (is (= {:keys [stuck-k] :deleted 1}
               (select-keys (ex-data (is (thrown? clojure.lang.ExceptionInfo
                                                  (privacy/purge-before! (data-export/->UploadRetention index stuck)
                                                                         (.plusSeconds (Instant/now) 60)))))
                            [:keys :deleted])))
        (is (false? (ports/file-exists? storage other)))
        (is (= [stuck-k] (map :key (ports/find-uploads index {:user-id user-id} {}))))))))
