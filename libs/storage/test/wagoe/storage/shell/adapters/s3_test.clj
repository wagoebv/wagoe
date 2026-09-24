(ns wagoe.storage.shell.adapters.s3-test
  "Integration tests for the S3 storage adapter.

   Tests run against a local S3-compatible service. CI starts adobe/s3mock;
   MinIO or LocalStack work too if you already run one.
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
           [software.amazon.awssdk.services.s3 S3Client S3Configuration]
           [software.amazon.awssdk.regions Region]
           [software.amazon.awssdk.auth.credentials
            AwsBasicCredentials StaticCredentialsProvider]
           [software.amazon.awssdk.services.s3.model
            CreateBucketRequest HeadBucketRequest]))

;; =============================================================================
;; S3 endpoint availability check
;; =============================================================================

(def ^:private test-endpoint "http://localhost:9000")
(def ^:private test-access-key "minioadmin")
(def ^:private test-secret-key "minioadmin")
(def ^:private test-bucket "test-wagoe-storage")
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
  "Create the test bucket if it does not exist.

   Path-style access, like the adapter: without it the SDK addresses the bucket
   as a subdomain, MinIO does not serve that, and every bucket operation failed
   while `s3-available?` still said yes — it only lists buckets, which carries no
   bucket in the host. The failure was then swallowed and returned as false,
   which nothing checked, so the suite ran against a bucket that did not exist
   (BOU-444)."
  []
  (let [creds (StaticCredentialsProvider/create
               (AwsBasicCredentials/create test-access-key test-secret-key))
        ^S3Configuration service-config (-> (S3Configuration/builder)
                                            (.pathStyleAccessEnabled true)
                                            .build)
        ;; Bound rather than threaded: inside a `->` the builder's static type
        ;; is lost, reflection picks .serviceConfiguration's Consumer overload
        ;; and the call dies the same way the adapter did (BOU-444).
        builder (S3Client/builder)
        client  (do (.region builder (Region/of test-region))
                    (.endpointOverride builder (URI/create test-endpoint))
                    (.serviceConfiguration builder service-config)
                    (.credentialsProvider builder creds)
                    (.build builder))]
    (try
      ;; Any failure to describe it is treated as absent: MinIO answers a
      ;; missing bucket with a plain 404 rather than NoSuchBucketException, so
      ;; catching only that left the bucket uncreated.
      (try
        (.headBucket client (-> (HeadBucketRequest/builder)
                                (.bucket test-bucket)
                                .build))
        (catch Exception _
          (.createBucket client (-> (CreateBucketRequest/builder)
                                    (.bucket test-bucket)
                                    .build))))
      (finally
        (.close client)))))

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

(defmacro when-s3
  "Run `body` against MinIO, or record a skip.

   Where an endpoint was promised the skip is a failure: this suite reported 62
   tests passing for months while the adapter it covers could not be
   constructed, because every case skipped itself and said so only in a passing
   assertion (BOU-444).

   Keyed on WAGOE_REQUIRE_S3, which the job that starts MinIO sets, and not on
   CI: the `:unit` suite runs this namespace too, from a job with no MinIO and
   no reason to have one, and a bare CI check made every job in the workflow
   answer for an endpoint only one of them provides."
  [& body]
  `(if (s3-available?)
     (do ~@body)
     (if (System/getenv "WAGOE_REQUIRE_S3")
       (is false
           (str "no S3-compatible endpoint on " test-endpoint
                " — this job starts MinIO, so reaching it is the point; "
                "without it the suite reports green for an adapter nothing built"))
       (do
         ;; Visible with `--no-capture-output`, and not otherwise: kaocha
         ;; replaces the JVM's streams and shows captured output only for a
         ;; failing test. Measured — System/out does not escape it either. So
         ;; the guard against green-with-nothing-exercised is the CI branch
         ;; above; this line is a courtesy to whoever is already looking.
         (println (str "  note: no S3-compatible endpoint on " test-endpoint
                       " — S3 cases skipped"))
         (is true)))))

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
