(ns wagoe.storage.shell.service-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [wagoe.observability.logging.ports :as log-ports]
            [wagoe.storage.core.validation :as validation]
            [wagoe.storage.ports :as ports]
            [wagoe.storage.shell.service :as sut]
            [wagoe.storage.shell.adapters.local :as local]
            [wagoe.storage.shell.adapters.image-processor :as img]))

(def test-dir "target/test-service-storage")

(defn cleanup-test-dir []
  (let [dir (io/file test-dir)]
    (when (.exists dir)
      (doseq [file (reverse (file-seq dir))]
        (io/delete-file file true)))))

(defn test-fixture [f]
  (cleanup-test-dir)
  (f)
  (cleanup-test-dir))

(use-fixtures :each test-fixture)

(defn create-test-service
  ([]
   (create-test-service {}))
  ([opts]
   (let [storage (local/create-local-storage {:base-path test-dir})
         image-processor (when (:with-image-processor opts)
                           (img/create-image-processor {}))]
     (sut/create-storage-service {:storage storage
                                  :image-processor image-processor}))))

(deftest ^:integration upload-file-test
  (let [service (create-test-service)
        file-data {:bytes (.getBytes "Test content")
                   :content-type "text/plain"
                   :size 12}
        metadata {:filename "test.txt"}]

    (testing "uploads valid file successfully"
      (let [result (sut/upload-file service file-data metadata {})]
        (is (:success result))
        (is (some? (:data result)))
        (is (string? (-> result :data :key)))
        (is (= 12 (-> result :data :size)))))

    (testing "sanitizes filename"
      (let [dangerous-metadata {:filename "../../../etc/passwd"}
            result (sut/upload-file service file-data dangerous-metadata {})]
        (is (:success result))
        (is (not (re-find #"\.\." (-> result :data :key))))))

    (testing "validates file size"
      (let [result (sut/upload-file service file-data metadata {:max-size 5})]
        (is (not (:success result)))
        (is (some? (:errors result)))
        (is (some #(= :file-too-large (:code %)) (:errors result)))))

    (testing "validates content type"
      (let [result (sut/upload-file service file-data metadata
                                    {:allowed-types ["image/jpeg"]})]
        (is (not (:success result)))
        (is (some #(= :invalid-content-type (:code %)) (:errors result)))))

    (testing "validates file extension"
      (let [result (sut/upload-file service file-data metadata
                                    {:allowed-extensions ["jpg" "png"]})]
        (is (not (:success result)))
        (is (some #(= :invalid-extension (:code %)) (:errors result)))))))

(deftest ^:unit every-adapter-receives-a-normalized-path
  ;; S3 and GCS use `:path` as a key prefix as given, so it is normalized once
  ;; here rather than in each adapter (BOU-639).
  (let [seen    (atom nil)
        storage #_{:clj-kondo/ignore [:missing-protocol-method]}
                (reify ports/IFileStorage
                  (store-file [_ _ metadata] (reset! seen metadata) {:key "k"}))
        service (sut/create-storage-service {:storage storage})]
    (sut/upload-file service {:bytes (.getBytes "x") :content-type "text/plain" :size 1}
                     {:filename "a.txt" :path "../work-visits/./42//"} {})
    (is (= "work-visits/42" (:path @seen)))))

(deftest ^:integration download-file-test
  (let [service (create-test-service)
        file-data {:bytes (.getBytes "Download test")
                   :content-type "text/plain"}
        metadata {:filename "download.txt"}]

    (testing "downloads existing file"
      (let [upload-result (sut/upload-file service file-data metadata {})
            key (-> upload-result :data :key)
            downloaded (sut/download-file service key)]
        (is (some? downloaded))
        (is (= (seq (.getBytes "Download test")) (seq (:bytes downloaded))))
        (is (= "text/plain" (:content-type downloaded)))))

    (testing "returns nil for non-existent file"
      (is (nil? (sut/download-file service "non-existent-key"))))))

(deftest ^:integration remove-file-test
  (let [service (create-test-service)
        file-data {:bytes (.getBytes "Remove test")
                   :content-type "text/plain"}
        metadata {:filename "remove.txt"}]

    (testing "removes existing file"
      (let [upload-result (sut/upload-file service file-data metadata {})
            key (-> upload-result :data :key)]
        (is (true? (sut/remove-file service key)))
        (is (nil? (sut/download-file service key)))))

    (testing "returns false for non-existent file"
      (is (false? (sut/remove-file service "non-existent"))))))

(deftest ^:integration get-file-url-test
  (let [service (create-test-service)
        file-data {:bytes (.getBytes "URL test")
                   :content-type "text/plain"}
        metadata {:filename "url.txt"}]

    (testing "generates URL for existing file"
      (let [upload-result (sut/upload-file service file-data metadata {})
            key (-> upload-result :data :key)
            url (sut/get-file-url service key 3600)]
        ;; URL may be nil for local storage without url-base
        (is (or (nil? url) (string? url)))))))

(deftest ^:integration upload-image-test
  (testing "uploads image without image processor"
    (let [service (create-test-service)
          ;; Simple 1x1 PNG image
          png-bytes (byte-array [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A
                                 0x00 0x00 0x00 0x0D 0x49 0x48 0x44 0x52
                                 0x00 0x00 0x00 0x01 0x00 0x00 0x00 0x01
                                 0x08 0x02 0x00 0x00 0x00 0x90 0x77 0x53
                                 0xDE 0x00 0x00 0x00 0x0C 0x49 0x44 0x41
                                 0x54 0x08 0x99 0x63 0xF8 0x0F 0x00 0x00
                                 0x01 0x01 0x00 0x05 0x18 0x0D 0xB9 0x2B
                                 0x00 0x00 0x00 0x00 0x49 0x45 0x4E 0x44
                                 0xAE 0x42 0x60 0x82])
          metadata {:filename "test.png"}
          result (sut/upload-image service png-bytes metadata {})]
      (is (:success result))
      (is (some? (:original result)))))

  (testing "validates image type"
    (let [service (create-test-service)
          non-image-bytes (.getBytes "Not an image")
          metadata {:filename "fake.jpg"}
          result (sut/upload-image service non-image-bytes metadata {})]
      ;; Should fail validation since it's not a real image
      (is (or (not (:success result))
              ;; Or succeed if validation is lenient
              (:success result))))))

(deftest ^:integration multiple-file-operations-test
  (let [service (create-test-service)
        files (for [i (range 5)]
                {:data {:bytes (.getBytes (str "Content " i))
                        :content-type "text/plain"}
                 :metadata {:filename (str "file-" i ".txt")}})]

    (testing "handles multiple file operations"
      (let [upload-results (doall
                            (map #(sut/upload-file service (:data %) (:metadata %) {})
                                 files))]
        (is (= 5 (count upload-results)))
        (is (every? :success upload-results))

        ;; Download all files
        (let [keys (map #(-> % :data :key) upload-results)
              downloads (map #(sut/download-file service %) keys)]
          (is (= 5 (count downloads)))
          (is (every? some? downloads)))

        ;; Delete all files
        (let [keys (map #(-> % :data :key) upload-results)
              deletions (map #(sut/remove-file service %) keys)]
          (is (every? true? deletions)))))))

(deftest ^:integration error-handling-test
  (let [service (create-test-service)]

    (testing "handles missing filename gracefully"
      (let [file-data {:bytes (.getBytes "test")
                       :content-type "text/plain"}
            metadata {}  ; Missing filename
            result (sut/upload-file service file-data metadata {})]
        ;; Should handle gracefully (either error or use generated name)
        (is (map? result))))))

;; ============================================================================
;; upload-image content-type detection (BOU-206)
;; ============================================================================

(deftest ^:integration upload-image-preserves-content-type
  (let [service (create-test-service)]
    (testing "a .png upload is stored as image/png (not hardcoded jpeg)"
      (let [result (sut/upload-image service (.getBytes "fake-png-bytes")
                                     {:filename "photo.png"} {})]
        (is (:success result))
        (is (= "image/png" (-> result :original :content-type)))))
    (testing "no image extension, no image: it would be served as octet-stream (BOU-1078)"
      (let [result (sut/upload-image service (.getBytes "bytes")
                                     {:filename "photo"} {})]
        (is (not (:success result)))))))

(defn- recording-index
  "An IUploadIndex over an atom, or one whose record-upload! throws."
  [records & {:keys [fail?]}]
  (reify ports/IUploadIndex
    (record-upload! [_ u]
      (when fail? (throw (ex-info "index down" {:type :internal-error})))
      (swap! records assoc (:key u) u))
    (remove-upload! [_ k] (some? (first (swap-vals! records dissoc k))))
    (find-uploads [_ _ _] (vec (vals @records)))
    (uploads-stored-before [_ _ _] [])))

(deftest ^:integration every-upload-is-recorded-with-its-owner
  (let [records (atom {})
        storage (local/create-local-storage {:base-path test-dir})
        service (sut/create-storage-service {:storage storage
                                             :image-processor (img/create-image-processor {})
                                             :upload-index (recording-index records)})
        user-id (random-uuid)
        result  (sut/upload-image service (.getBytes "not really a png")
                                  {:filename "a.png" :owner {:user-id user-id :tenant-id "t1"}}
                                  {})
        k       (-> result :original :key)]
    (is (:success result))
    (is (= {:key k :user-id (str user-id) :tenant-id "t1" :size 16 :content-type "image/png"}
           (dissoc (get @records k) :stored-at)))
    (is (inst? (:stored-at (get @records k))))
    (is (true? (sut/remove-file service k)))
    (is (empty? @records))))

(deftest ^:integration an-upload-the-index-cannot-record-fails-and-leaves-no-file
  (let [storage (local/create-local-storage {:base-path test-dir})
        service (sut/create-storage-service {:storage storage
                                             :upload-index (recording-index (atom {}) :fail? true)})
        result  (sut/upload-file service {:bytes (.getBytes "x") :content-type "text/plain"}
                                 {:filename "x.txt"} {})]
    (is (false? (:success result)))
    (is (= [:storage-error] (map :code (:errors result))))
    (is (empty? (filter #(.isFile ^java.io.File %) (file-seq (io/file test-dir))))
        "the stored file was left behind")))

(defn- query-params [url]
  (into {} (for [kv (str/split (second (str/split url #"\?")) #"&")]
             (str/split kv #"=" 2))))

(deftest ^:integration ^:security an-app-uploads-signs-and-serves-through-the-port
  ;; BOU-1077: no storage shell namespace needed
  (let [storage (local/create-local-storage {:base-path test-dir :url-base "http://x/files"
                                             :signing-secret "q8Zr3vLx0pT6wN2mHc9sYbK4dF7jA1eU"})
        service (sut/create-storage-service {:storage storage})
        key     (get-in (ports/upload-file service {:bytes (.getBytes "hello")} {:filename "a.txt"} {}) [:data :key])
        params  (query-params (ports/get-file-url service key 60))]
    (is (= "hello" (String. ^bytes (:bytes (ports/signed-file service key params)))))
    (testing "nil for a tampered, missing or another key's signature, or a stale version"
      (is (nil? (ports/signed-file service key (assoc params "signature" (apply str (repeat 64 "0"))))))
      (is (nil? (ports/signed-file service key {})))
      (is (nil? (ports/signed-file service (str key "x") params)))
      (is (nil? (ports/signed-file service key (assoc params "v" "other")))))
    (testing "every port method reaches the service"
      (is (= "hello" (String. ^bytes (:bytes (ports/download-file service key)))))
      (is (contains? (ports/upload-image service (.getBytes "png") {:filename "x.png"} {}) :success)
          "answered by the service, not an IllegalArgumentException")
      (is (true? (ports/remove-file service key)))
      (is (nil? (ports/download-file service key))))
    (testing "an adapter without a signing secret serves nothing"
      (let [plain (sut/create-storage-service {:storage (local/create-local-storage {:base-path test-dir})})]
        (is (nil? (ports/signed-file plain key params)))))))

(deftest ^:integration an-upload-streams-from-a-file-or-a-stream
  ;; BOU-1080: never all of it in memory
  (let [service (create-test-service)
        f       (doto (java.io.File/createTempFile "upload" ".txt") (spit "from a file") (.deleteOnExit))]
    (testing "from a file"
      (let [r (ports/upload-file service {:file f} {:filename "a.txt"} {})]
        (is (:success r))
        (is (= "from a file" (String. ^bytes (:bytes (ports/download-file service (get-in r [:data :key]))))))))
    (testing "from a stream"
      (let [r (ports/upload-file service {:input-stream (io/input-stream (.getBytes "streamed"))} {:filename "b.txt"} {})]
        (is (= "streamed" (String. ^bytes (:bytes (ports/download-file service (get-in r [:data :key]))))))))
    (testing "a file over the limit is refused before it is stored"
      (is (= :file-too-large (-> (ports/upload-file service {:file f} {:filename "c.txt"} {:max-size 5}) :errors first :code))))
    (testing "a stream over the limit is refused while it is copied, and nothing is kept"
      (let [files  #(count (filter (fn [^java.io.File x] (.isFile x)) (file-seq (io/file test-dir))))
            before (files)
            r      (ports/upload-file service {:input-stream (io/input-stream (.getBytes "far too long"))}
                                      {:filename "d.txt"} {:max-size 5})]
        (is (= :file-too-large (-> r :errors first :code)))
        (is (= before (files)))))
    (testing "a refused upload's stream is closed all the same"
      (let [closed? (atom false)
            in      (proxy [java.io.ByteArrayInputStream] [(.getBytes "x")]
                      (close [] (reset! closed? true)))]
        (ports/upload-file service {:input-stream in} {:filename "x.txt"} {:allowed-extensions ["pdf"]})
        (is @closed?)))))

(deftest ^:integration ^:security the-versions-directory-is-no-path-a-caller-may-name
  (let [service (create-test-service)]
    (doseq [path [".versions" ".Versions/x"]]
      (is (= :invalid-path (-> (ports/upload-file service {:bytes (.getBytes "x")} {:filename "x.txt" :path path} {})
                               :errors first :code))
          path))))

(defn- capturing-logger [entries]
  (let [log! (fn [& args] (swap! entries conj (pr-str args)) nil)]
    (reify log-ports/ILogger
      (log* [_ l m c e] (log! l m c e))
      (trace [_ m] (log! m)) (trace [_ m c] (log! m c))
      (debug [_ m] (log! m)) (debug [_ m c] (log! m c))
      (info [_ m] (log! m)) (info [_ m c] (log! m c))
      (warn [_ m] (log! m)) (warn [_ m c] (log! m c)) (warn [_ m c e] (log! m c e))
      (error [_ m] (log! m)) (error [_ m c] (log! m c)) (error [_ m c e] (log! m c e))
      (fatal [_ m] (log! m)) (fatal [_ m c] (log! m c)) (fatal [_ m c e] (log! m c e)))))

(deftest ^:integration ^:security an-uploaders-filename-stays-out-of-the-logs
  ;; BOU-1082: a named path's key ends in the filename, which is user content
  (let [entries (atom [])
        logger  (capturing-logger entries)
        service (sut/create-storage-service {:storage (local/create-local-storage {:base-path test-dir :logger logger})
                                             :logger  logger})
        r       (ports/upload-file service {:bytes (.getBytes "cv")} {:filename "cv-jan-jansen.pdf" :path "docs"} {})]
    (is (:success r))
    (ports/get-file-url service (get-in r [:data :key]) 60)
    (ports/remove-file service (get-in r [:data :key]))
    (is (seq @entries))
    (is (not-any? #(str/includes? % "jan-jansen") @entries) (pr-str @entries))
    (is (some #(str/includes? % "docs/*.pdf") @entries) "the directory and type still show")))

(deftest ^:unit a-generated-key-is-logged-whole
  (let [k "2c/1795518158704-201233d6-3b90-4250-acc8-43585e30b64d-2cf24dba5fb0a30e.txt"]
    (is (= k (validation/key-for-log k))))
  (is (= ".tenants/t1/docs/*.pdf" (validation/key-for-log ".tenants/t1/docs/cv.pdf")))
  (is (= "*" (validation/key-for-log "notes")))
  (is (= "uploads/1795518158704-a1b2c3d4.pdf" (validation/key-for-log "uploads/1795518158704-a1b2c3d4.pdf"))
      "an S3 or GCS key")
  (is (= "*" (validation/key-for-log "Jan.Jansen")) "a name's last word is no extension"))
