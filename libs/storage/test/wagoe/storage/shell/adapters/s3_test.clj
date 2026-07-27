(ns wagoe.storage.shell.adapters.s3-test
  "Integration tests for the S3 storage adapter.

   Tests run against a local S3-compatible service (MinIO or LocalStack).
   Expected configuration:
     - Endpoint: http://localhost:9000
     - Access key: minioadmin
     - Secret key: minioadmin
     - Bucket: test-bucket

   If the endpoint is not reachable or the bucket doesn't exist, all tests
   are skipped."
  {:kaocha.testable/meta {:integration true :s3 true}}
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [wagoe.storage.shell.adapters.s3 :as s3-adapter]
            [wagoe.storage.ports :as ports])
  (:import [java.net URI]
           [software.amazon.awssdk.services.s3 S3Client]
           [software.amazon.awssdk.regions Region]
           [software.amazon.awssdk.auth.credentials
            AwsBasicCredentials StaticCredentialsProvider]
           [software.amazon.awssdk.services.s3.model
            CreateBucketRequest HeadBucketRequest NoSuchBucketException]))

;; =============================================================================
;; S3/MinIO availability check
;; =============================================================================

(def ^:private test-endpoint "http://localhost:9000")
(def ^:private test-access-key "minioadmin")
(def ^:private test-secret-key "minioadmin")
(def ^:private test-bucket "test-boundary-storage")
(def ^:private test-region "us-east-1")

(defn s3-available?
  "Check if a local S3-compatible service is reachable."
  []
  (try
    (let [creds (StaticCredentialsProvider/create
                 (AwsBasicCredentials/create test-access-key test-secret-key))
          client (-> (S3Client/builder)
                     (.region (Region/of test-region))
                     (.endpointOverride (URI/create test-endpoint))
                     (.credentialsProvider creds)
                     .build)
          ;; list buckets to confirm connection works
          _ (.listBuckets client)]
      (.close client)
      true)
    (catch Exception _
      false)))

(defn ensure-test-bucket!
  "Create test bucket if it doesn't exist."
  []
  (try
    (let [creds (StaticCredentialsProvider/create
                 (AwsBasicCredentials/create test-access-key test-secret-key))
          client (-> (S3Client/builder)
                     (.region (Region/of test-region))
                     (.endpointOverride (URI/create test-endpoint))
                     (.credentialsProvider creds)
                     .build)]
      (try
        (.headBucket client (-> (HeadBucketRequest/builder)
                                (.bucket test-bucket)
                                .build))
        (catch NoSuchBucketException _
          (.createBucket client (-> (CreateBucketRequest/builder)
                                    (.bucket test-bucket)
                                    .build)))
        (finally
          (.close client))))
    (catch Exception _
      false)))

;; =============================================================================
;; Test fixtures
;; =============================================================================

(def ^:dynamic *storage* nil)
(def ^:dynamic *stored-keys* nil)

(defn with-s3-storage
  "Fixture: create an S3 storage adapter pointing at the local MinIO instance."
  [f]
  (if (s3-available?)
    (do
      (ensure-test-bucket!)
      (let [storage (s3-adapter/create-s3-storage
                     {:bucket test-bucket
                      :region test-region
                      :endpoint test-endpoint
                      :access-key test-access-key
                      :secret-key test-secret-key
                      :prefix "test-run"})]
        (binding [*storage* storage
                  *stored-keys* (atom [])]
          (try
            (f)
            (finally
              ;; Clean up stored files
              (doseq [k @*stored-keys*]
                (try (ports/delete-file storage k) (catch Exception _)))
              (s3-adapter/close-s3-storage storage))))))
    (f)))

(use-fixtures :each with-s3-storage)

(defmacro when-s3 [& body]
  `(if (s3-available?)
     (do ~@body)
     (is (not (s3-available?)) "S3/MinIO not available — test skipped")))

;; =============================================================================
;; Basic file operations
;; =============================================================================

(deftest ^:integration s3-store-retrieve-test
  (when-s3
   (testing "store and retrieve a file"
     (let [content (.getBytes "Hello, S3!")
           file-data {:bytes content :content-type "text/plain"}
           metadata {:filename "hello.txt" :visibility :private}
           result (ports/store-file *storage* file-data metadata)]

       (is (some? (:key result)))
       (is (= (count content) (:size result)))
       (swap! *stored-keys* conj (:key result))

       (let [retrieved (ports/retrieve-file *storage* (:key result))]
         (is (some? retrieved))
         (is (= (count content) (count (:bytes retrieved)))))))))

(deftest ^:integration s3-file-exists-test
  (when-s3
   (testing "file-exists? returns true after storing"
     (let [content (.getBytes "existence test")
           result (ports/store-file *storage*
                                    {:bytes content :content-type "text/plain"}
                                    {:filename "exist.txt" :visibility :private})]
       (swap! *stored-keys* conj (:key result))
       (is (true? (ports/file-exists? *storage* (:key result))))))

   (testing "file-exists? returns false for missing key"
     (is (false? (ports/file-exists? *storage* "definitely-missing-xyz/abc"))))))

(deftest ^:integration s3-delete-file-test
  (when-s3
   (testing "delete-file removes stored file"
     (let [content (.getBytes "to delete")
           result (ports/store-file *storage*
                                    {:bytes content :content-type "text/plain"}
                                    {:filename "delete-me.txt" :visibility :private})]
       (is (true? (ports/delete-file *storage* (:key result))))
       (is (false? (ports/file-exists? *storage* (:key result))))))))

(deftest ^:integration s3-retrieve-missing-test
  (when-s3
   (testing "retrieve-file returns nil for missing key"
     (is (nil? (ports/retrieve-file *storage* "no/such/file.txt"))))))

(deftest ^:integration s3-signed-url-test
  (when-s3
   (testing "generate-signed-url returns a URL string"
     (let [content (.getBytes "signed content")
           result (ports/store-file *storage*
                                    {:bytes content :content-type "text/plain"}
                                    {:filename "signed.txt" :visibility :private})]
       (swap! *stored-keys* conj (:key result))
       (let [url (ports/generate-signed-url *storage* (:key result) 60)]
         (is (string? url))
         (is (.startsWith url "http")))))))

(deftest ^:integration s3-public-visibility-test
  (when-s3
   (testing "public visibility returns direct URL"
     (let [content (.getBytes "public content")
           result (ports/store-file *storage*
                                    {:bytes content :content-type "text/plain"}
                                    {:filename "public.txt" :visibility :public})]
       (swap! *stored-keys* conj (:key result))
       (is (string? (:url result)))
       (is (.startsWith (:url result) "https://"))))))
