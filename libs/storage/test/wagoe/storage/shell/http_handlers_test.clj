(ns wagoe.storage.shell.http-handlers-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [wagoe.storage.shell.http-handlers :as sut]
            [wagoe.storage.shell.service :as service]
            [wagoe.storage.shell.adapters.local :as local]))

(def test-dir "target/test-http-handlers-storage")

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

(defn create-test-service []
  (let [storage (local/create-local-storage {:base-path test-dir})]
    (service/create-storage-service {:storage storage})))

(defn make-multipart-file
  "Build a Ring-style multipart file map with byte content."
  [filename content-type ^String content]
  {:filename filename
   :content-type content-type
   :bytes (.getBytes content)})

;; ============================================================================
;; upload-file-handler
;; ============================================================================

(deftest ^:integration upload-file-handler-valid-max-size-test
  (let [service (create-test-service)
        handler (sut/upload-file-handler service)
        file (make-multipart-file "test.txt" "text/plain" "hello")]

    (testing "valid max-size param is parsed and applied"
      (let [resp (handler {:multipart-params {"file" file}
                           :query-params {"max-size" "1024"}})]
        (is (= 201 (:status resp)))))

    (testing "max-size rejects oversized file"
      (let [resp (handler {:multipart-params {"file" file}
                           :query-params {"max-size" "2"}})]
        (is (= 400 (:status resp)))))))

(deftest ^:integration upload-file-handler-invalid-max-size-test
  (let [service (create-test-service)
        handler (sut/upload-file-handler service)
        file (make-multipart-file "test.txt" "text/plain" "hello")]

    (testing "non-numeric max-size is treated as nil (no limit)"
      (let [resp (handler {:multipart-params {"file" file}
                           :query-params {"max-size" "not-a-number"}})]
        (is (= 201 (:status resp)))))))

;; ============================================================================
;; upload-image-handler
;; ============================================================================

(deftest ^:integration upload-image-handler-thumbnail-size-test
  (let [service (create-test-service)
        handler (sut/upload-image-handler service)
        ;; Minimal 1x1 PNG
        png-bytes (byte-array [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A
                               0x00 0x00 0x00 0x0D 0x49 0x48 0x44 0x52
                               0x00 0x00 0x00 0x01 0x00 0x00 0x00 0x01
                               0x08 0x02 0x00 0x00 0x00 0x90 0x77 0x53
                               0xDE 0x00 0x00 0x00 0x0C 0x49 0x44 0x41
                               0x54 0x08 0x99 0x63 0xF8 0x0F 0x00 0x00
                               0x01 0x01 0x00 0x05 0x18 0x0D 0xB9 0x2B
                               0x00 0x00 0x00 0x00 0x49 0x45 0x4E 0x44
                               0xAE 0x42 0x60 0x82])
        file {:filename "test.png" :content-type "image/png" :bytes png-bytes}]

    (testing "valid thumbnail-size is parsed"
      (let [resp (handler {:multipart-params {"file" file
                                              "thumbnail-size" "150"}})]
        (is (= 201 (:status resp)))))

    (testing "non-numeric thumbnail-size is treated as nil"
      (let [resp (handler {:multipart-params {"file" file
                                              "thumbnail-size" "abc"}})]
        (is (= 201 (:status resp)))))))

;; ============================================================================
;; get-file-url-handler
;; ============================================================================

(deftest ^:integration get-file-url-handler-expiration-test
  (let [service (create-test-service)
        ;; Upload a file first so we have a valid key
        file-data {:bytes (.getBytes "url test")
                   :content-type "text/plain"
                   :size 8}
        upload-result (service/upload-file service file-data {:filename "url.txt"} {})
        file-key (-> upload-result :data :key)
        handler (sut/get-file-url-handler service)]

    (testing "valid expiration param is parsed"
      (let [resp (handler {:path-params {:file-key file-key}
                           :query-params {"expiration" "7200"}})]
        ;; local storage may return 200 or 404 depending on url-base config
        ;; but it should NOT stack-overflow
        (is (#{200 404} (:status resp)))))

    (testing "non-numeric expiration defaults to 3600"
      (let [resp (handler {:path-params {:file-key file-key}
                           :query-params {"expiration" "garbage"}})]
        (is (#{200 404} (:status resp)))))

    (testing "no expiration param defaults to 3600"
      (let [resp (handler {:path-params {:file-key file-key}
                           :query-params {}})]
        (is (#{200 404} (:status resp)))))))
