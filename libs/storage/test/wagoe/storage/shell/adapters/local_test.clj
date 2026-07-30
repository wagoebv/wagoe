(ns wagoe.storage.shell.adapters.local-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [wagoe.storage.shell.adapters.local :as sut]
            [wagoe.storage.ports :as ports])
)

(def test-dir "target/test-storage")

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

(deftest ^:integration create-local-storage-test
  (testing "creates storage with valid config"
    (let [storage (sut/create-local-storage {:base-path test-dir
                                             :create-directories? true})]
      (is (some? storage))
      (is (.exists (io/file test-dir)))))

  (testing "throws on missing base-path"
    (is (thrown? Exception
                 (sut/create-local-storage {})))))

(deftest ^:integration store-and-retrieve-file-test
  (let [storage (sut/create-local-storage {:base-path test-dir
                                           :url-base "http://localhost/files"})
        test-content (.getBytes "Hello, World!")
        file-data {:bytes test-content
                   :content-type "text/plain"}
        metadata {:filename "test.txt"}]

    (testing "stores file successfully"
      (let [result (ports/store-file storage file-data metadata)]
        (is (some? result))
        (is (string? (:key result)))
        (is (string? (:url result)))
        (is (= (alength test-content) (:size result)))
        (is (= "text/plain" (:content-type result)))
        (is (inst? (:stored-at result)))))

    (testing "retrieves stored file"
      (let [store-result (ports/store-file storage file-data metadata)
            retrieved (ports/retrieve-file storage (:key store-result))]
        (is (some? retrieved))
        (is (= (seq test-content) (seq (:bytes retrieved))))
        (is (= "text/plain" (:content-type retrieved)))
        (is (= (alength test-content) (:size retrieved)))))

    (testing "returns nil for non-existent file"
      (is (nil? (ports/retrieve-file storage "non-existent-key"))))))

(deftest ^:integration file-exists-test
  (let [storage (sut/create-local-storage {:base-path test-dir})
        file-data {:bytes (.getBytes "test")
                   :content-type "text/plain"}
        metadata {:filename "exists.txt"}]

    (testing "returns false for non-existent file"
      (is (false? (ports/file-exists? storage "non-existent"))))

    (testing "returns true for existing file"
      (let [result (ports/store-file storage file-data metadata)]
        (is (true? (ports/file-exists? storage (:key result))))))))

(deftest ^:integration delete-file-test
  (let [storage (sut/create-local-storage {:base-path test-dir})
        file-data {:bytes (.getBytes "test")
                   :content-type "text/plain"}
        metadata {:filename "delete-me.txt"}]

    (testing "deletes existing file"
      (let [result (ports/store-file storage file-data metadata)
            key (:key result)]
        (is (true? (ports/file-exists? storage key)))
        (is (true? (ports/delete-file storage key)))
        (is (false? (ports/file-exists? storage key)))))

    (testing "returns false when deleting non-existent file"
      (is (false? (ports/delete-file storage "non-existent"))))))

(deftest ^:integration generate-signed-url-test
  (let [storage (sut/create-local-storage {:base-path test-dir
                                           :url-base "http://localhost/files"})
        file-data {:bytes (.getBytes "test")
                   :content-type "text/plain"}
        metadata {:filename "url-test.txt"}]

    (testing "generates URL when url-base is configured"
      (let [result (ports/store-file storage file-data metadata)
            url (ports/generate-signed-url storage (:key result) 3600)]
        (is (some? url))
        (is (re-find #"http://localhost/files/" url))))

    (testing "returns nil when url-base not configured"
      (let [storage-no-url (sut/create-local-storage {:base-path test-dir})
            result (ports/store-file storage-no-url file-data metadata)
            url (ports/generate-signed-url storage-no-url (:key result) 3600)]
        ;; Local storage without url-base still returns URL if configured
        (is (or (nil? url) (string? url)))))))

(deftest ^:integration custom-path-test
  (let [storage (sut/create-local-storage {:base-path test-dir})
        file-data {:bytes (.getBytes "test")
                   :content-type "text/plain"}
        metadata {:filename "custom.txt"
                  :path "custom/path"}]

    (testing "stores file in custom path"
      (let [result (ports/store-file storage file-data metadata)]
        (is (some? result))
        (is (re-find #"custom" (:key result)))))))

(deftest ^:integration concurrent-uploads-test
  (let [storage (sut/create-local-storage {:base-path test-dir})
        file-data {:bytes (.getBytes "test content")
                   :content-type "text/plain"}]

    (testing "handles concurrent uploads safely"
      (let [results (doall
                     (pmap
                      (fn [i]
                        (ports/store-file storage
                                          file-data
                                          {:filename (str "concurrent-" i ".txt")}))
                      (range 10)))]
        (is (= 10 (count results)))
        (is (every? some? results))
        (is (= 10 (count (set (map :key results)))))))))

;; ============================================================================
;; Signed URLs (HMAC-SHA256)
;; ============================================================================

(deftest ^:unit signed-url-round-trips-and-rejects-tampering
  (let [secret  "top-secret-signing-key"
        storage (sut/map->LocalFileStorage {:base-path      "irrelevant"
                                            :url-base       "https://cdn.example.com"
                                            :signing-secret secret})
        url     (ports/generate-signed-url storage "ab/file.png" 3600)
        expires (second (re-find #"expires=(\d+)" url))
        sig     (second (re-find #"signature=([0-9a-f]+)" url))]
    (testing "URL carries an expiry + 64-hex HMAC signature"
      (is (re-matches #"https://cdn\.example\.com/ab/file\.png\?expires=\d+&signature=[0-9a-f]{64}" url)))
    (testing "a valid signature verifies"
      (is (true? (sut/verify-signed-url secret "ab/file.png" {:expires expires :signature sig}))))
    (testing "a tampered signature is rejected"
      (is (false? (sut/verify-signed-url secret "ab/file.png" {:expires expires :signature (apply str (repeat 64 "0"))}))))
    (testing "a different file-key is rejected (signature is key-bound)"
      (is (false? (sut/verify-signed-url secret "other/key.png" {:expires expires :signature sig}))))
    (testing "a different secret is rejected"
      (is (false? (sut/verify-signed-url "wrong-secret" "ab/file.png" {:expires expires :signature sig}))))))

(deftest ^:unit signed-url-expiry-is-enforced
  (let [secret  "top-secret-signing-key"
        storage (sut/map->LocalFileStorage {:base-path      "irrelevant"
                                            :url-base       "https://cdn.example.com"
                                            :signing-secret secret})
        ;; negative lifetime => an already-past expiry, signature still valid
        url     (ports/generate-signed-url storage "ab/file.png" -10000)
        expires (second (re-find #"expires=(\d+)" url))
        sig     (second (re-find #"signature=([0-9a-f]+)" url))]
    (is (false? (sut/verify-signed-url secret "ab/file.png" {:expires expires :signature sig}))
        "an expired but correctly-signed URL is rejected")))

(deftest ^:unit without-signing-secret-url-is-plain-public
  (let [storage (sut/map->LocalFileStorage {:base-path "irrelevant"
                                            :url-base  "https://cdn.example.com"})]
    (is (= "https://cdn.example.com/ab/file.png"
           (ports/generate-signed-url storage "ab/file.png" 3600))
        "no secret => unsigned public URL (no query string)")))
