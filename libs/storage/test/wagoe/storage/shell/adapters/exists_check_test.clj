(ns wagoe.storage.shell.adapters.exists-check-test
  "`file-exists?` tells a missing file from a check that failed, on S3 and GCS
   clients that fail on cue."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.storage.ports :as ports]
            [wagoe.storage.shell.adapters.gcs :as gcs]
            [wagoe.storage.shell.adapters.s3 :as s3])
  (:import [com.google.cloud.storage Storage StorageException]
           [software.amazon.awssdk.services.s3 S3Client]
           [software.amazon.awssdk.services.s3.model HeadObjectRequest S3Exception]))

(defn- s3-answering [status]
  (s3/map->S3FileStorage
   {:bucket    "b"
    :s3-client (reify S3Client
                 (^software.amazon.awssdk.services.s3.model.HeadObjectResponse
                  headObject [_ ^HeadObjectRequest _]
                   (throw (-> (S3Exception/builder) (.statusCode (int status)) (.message "head") .build))))}))

(deftest ^:unit s3-reads-a-404-as-absent-and-anything-else-as-unknown
  (testing "404"
    (is (false? (ports/file-exists? (s3-answering 404) "k"))))
  (testing "403 and 503 throw rather than claim the file is gone"
    (doseq [status [403 503]]
      (is (= :storage-error
             (:type (ex-data (is (thrown? clojure.lang.ExceptionInfo
                                          (ports/file-exists? (s3-answering status) "k")))))))))
  (testing "nor does a network failure"
    (let [storage (s3/map->S3FileStorage
                   {:bucket    "b"
                    :s3-client (reify S3Client
                                 (^software.amazon.awssdk.services.s3.model.HeadObjectResponse
                                  headObject [_ ^HeadObjectRequest _]
                                   (throw (java.io.UncheckedIOException. (java.io.IOException. "reset")))))})]
      (is (thrown? clojure.lang.ExceptionInfo (ports/file-exists? storage "k"))))))

(deftest ^:unit gcs-throws-when-it-cannot-tell
  (let [storage (gcs/map->GCSFileStorage
                 {:bucket  "b"
                  :storage (proxy [Storage] []
                             (get [& _] (throw (StorageException. 503 "unavailable"))))})]
    (is (thrown? clojure.lang.ExceptionInfo (ports/file-exists? storage "k")))))
