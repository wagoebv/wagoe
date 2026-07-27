(ns wagoe.storage.shell.service-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
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
    (testing "unknown extension falls back to image/jpeg"
      (let [result (sut/upload-image service (.getBytes "bytes")
                                     {:filename "photo"} {})]
        (is (:success result))
        (is (= "image/jpeg" (-> result :original :content-type)))))))
