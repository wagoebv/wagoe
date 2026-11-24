(ns wagoe.storage.shell.data-export-test
  "Uploads in data export and upload retention, over a real index (WAGOE_TEST_DB)
   and the local adapter."
  (:require [clojure.test :refer [deftest is testing]]
            [support.test-db :as test-db]
            [wagoe.platform.database :as db]
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

(deftest ^:unit a-lost-connection-ends-the-purge
  ;; BOU-970: each item after it would wait out the connection timeout
  (doseq [[what error lost?] [["pool timeout" #(java.sql.SQLTransientConnectionException. "Connection is not available") true]
                              ["one item" #(ex-info "will not delete" {:type :internal-error}) false]]]
    (let [tried (atom [])
          e     (try (data-export/purge-in-pages! (constantly [1 2 3]) identity
                                                  (fn [id] (swap! tried conj id) (when (= 2 id) (throw (error))))
                                                  "uploads")
                     nil
                     (catch clojure.lang.ExceptionInfo e e))]
      (is (= (if lost? [1 2] [1 2 3]) @tried) what)
      (is (= {:keys (if lost? [] [2]) :deleted (if lost? 1 2)} (select-keys (ex-data e) [:keys :deleted])) what)
      (is (= lost? (db/connection-lost? e)) what))))

(deftest ^:integration ^:security upload-retention-keeps-what-the-app-still-uses
  ;; BOU-1081
  (with-storage
    (fn [{:keys [service storage index]}]
      (let [owner  {:user-id (random-uuid)}
            shared (upload! service "shared in a conversation" owner)
            stale  (upload! service "nobody refers to it" owner)
            asked  (atom [])
            policy (data-export/map->UploadRetention
                    {:upload-index index :storage storage
                     :in-use? (fn [{:keys [key]}] (swap! asked conj key) (= key shared))})]
        (is (= 1 (privacy/purge-before! policy (.plusSeconds (Instant/now) 60))))
        (is (true? (ports/file-exists? storage shared)) "kept, however old")
        (is (false? (ports/file-exists? storage stale)))
        (is (= [shared] (map :key (ports/find-uploads index owner {}))))
        (is (= #{shared stale} (set @asked)))
        (testing "a file the app cannot answer for is kept, and the run says so"
          (let [e (try (privacy/purge-before! (data-export/map->UploadRetention
                                               {:upload-index index :storage storage
                                                :in-use? (fn [_] (throw (ex-info "app down" {})))})
                                              (.plusSeconds (Instant/now) 60))
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
            (is (= [shared] (:keys (ex-data e))))
            (is (true? (ports/file-exists? storage shared)))))))))

(deftest ^:unit kept-items-do-not-hold-back-newer-ones
  ;; BOU-1081: a full page of kept items used to end the run before anything newer
  (let [rows   (atom (vec (range 1200)))
        reads  (atom 0)
        fetch  (fn [after limit]
                 (let [page (vec (take limit (cond->> @rows after (drop-while #(<= % after)))))]
                   (swap! reads + (count page))
                   page))
        result (data-export/purge-after! fetch identity
                                         (fn [id] (if (< id 1000)
                                                    data-export/kept
                                                    (swap! rows (fn [r] (vec (remove #{id} r))))))
                                         "uploads")]
    (is (= {:deleted 200 :kept 1000} result))
    (is (= (range 1000) @rows))
    (is (= 1200 @reads) "a kept row is read once, not again with every page")))

(deftest ^:unit an-outage-ends-the-run-after-one-page
  ;; Every delete failing, a storage outage say: one page of attempts, not all of them
  (let [tried (atom 0)
        e     (try (data-export/purge-after! (fn [_ limit] (vec (range limit))) identity
                                             (fn [_] (swap! tried inc) (throw (ex-info "S3 down" {})))
                                             "uploads")
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
    (is (= 500 @tried))
    (is (= "S3 down" (ex-message (ex-cause e))) "the first failure's reason travels with it")))

(deftest ^:integration old-uploads-page-by-time-then-key
  ;; BOU-1081: the cursor retention pages with, ties on stored-at broken by key
  (with-storage
    (fn [{:keys [index]}]
      (let [t   (.minus (Instant/now) 2 ChronoUnit/DAYS)
            ups (for [k ["c" "a" "b" "d"]]
                  {:key k :user-id nil :tenant-id nil :size 1 :content-type "text/plain"
                   :stored-at (if (= k "d") (.plusSeconds t 1) t)})]
        (doseq [u ups] (ports/record-upload! index u))
        (let [cutoff (Instant/now)
              page1  (ports/uploads-stored-after index cutoff nil 2)
              page2  (ports/uploads-stored-after index cutoff (last page1) 2)]
          (is (= ["a" "b"] (map :key page1)))
          (is (= ["c" "d"] (map :key page2)))
          (is (empty? (ports/uploads-stored-after index cutoff (last page2) 2))))))))

(deftest ^:unit a-lost-connection-names-what-failed-before-it
  (let [e (try (data-export/purge-after! (fn [after _] (if after [] [1 2 3])) identity
                                         (fn [id] (case id
                                                    1 (throw (ex-info "S3 down" {}))
                                                    2 (throw (java.sql.SQLTransientConnectionException. "gone"))
                                                    nil))
                                         "uploads")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= [1] (:keys (ex-data e))))))

(deftest ^:unit an-index-without-pages-still-keeps-what-is-in-use
  ;; BOU-1081: the fallback, over an index on the stable port only
  (let [rows   (atom [{:key "kept"} {:key "gone"}])
        index  (reify ports/IUploadIndex
                 (uploads-stored-before [_ _ limit] (vec (take limit @rows)))
                 (remove-upload! [_ k] (swap! rows (fn [r] (vec (remove #(= k (:key %)) r)))) true)
                 (record-upload! [_ u] u)
                 (find-uploads [_ _ _] []))
        files  (atom #{"kept" "gone"})
        store  (reify ports/IFileStorage
                 (delete-file [_ k] (swap! files disj k) true)
                 (file-exists? [_ k] (contains? @files k))
                 (store-file [_ _ _] nil) (retrieve-file [_ _] nil) (generate-signed-url [_ _ _] nil))
        policy (data-export/map->UploadRetention {:upload-index index :storage store
                                                  :in-use? #(= "kept" (:key %))})]
    (is (= 1 (privacy/purge-before! policy (Instant/now))))
    (is (= #{"kept"} @files))))
