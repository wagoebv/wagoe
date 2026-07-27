(ns wagoe.storage.shell.adapters.gcs-test
  "GCS adapter exercised against the in-memory LocalStorageHelper fake (no
   network / real project needed). Signing is not covered — LocalStorageHelper
   does not implement signUrl."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.storage.shell.adapters.gcs :as sut]
            [wagoe.storage.ports :as ports])
  (:import [com.google.cloud.storage.contrib.nio.testing LocalStorageHelper]))

(defn- fake-storage []
  (.getService (LocalStorageHelper/getOptions)))

(defn- adapter [& {:keys [public-read?] :or {public-read? true}}]
  (sut/map->GCSFileStorage {:bucket       "test-bucket"
                            :prefix       nil
                            :public-read? public-read?
                            :storage      (fake-storage)
                            :logger       nil}))

(deftest ^:integration gcs-store-retrieve-delete-roundtrip
  (let [a   (adapter)
        res (ports/store-file a {:bytes (.getBytes "hello gcs") :content-type "text/plain"}
                              {:filename "greeting.txt"})
        k   (:key res)]
    (testing "store returns the port's result shape"
      (is (string? k))
      (is (= 9 (:size res)))
      (is (= "text/plain" (:content-type res)))
      (is (inst? (:stored-at res))))
    (testing "public URL points at the bucket"
      (is (= (str "https://storage.googleapis.com/test-bucket/" k) (:url res))))
    (testing "exists + retrieve"
      (is (true? (ports/file-exists? a k)))
      (is (= "hello gcs" (String. (:bytes (ports/retrieve-file a k))))))
    (testing "delete removes it"
      (is (true? (ports/delete-file a k)))
      (is (false? (ports/file-exists? a k)))
      (is (nil? (ports/retrieve-file a k))))))

(deftest ^:integration gcs-private-bucket-has-no-public-url
  (let [a   (adapter :public-read? false)
        res (ports/store-file a {:bytes (.getBytes "x") :content-type "text/plain"}
                              {:filename "p.txt"})]
    (is (nil? (:url res)) "private objects expose no direct public URL")))
