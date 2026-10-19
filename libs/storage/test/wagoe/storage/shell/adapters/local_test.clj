(ns wagoe.storage.shell.adapters.local-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [wagoe.storage.shell.adapters.local :as sut]
            [wagoe.storage.ports :as ports]
            [clojure.string :as str]))

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

    (testing "stores file in custom path, one directory per segment (BOU-639)"
      (let [result (ports/store-file storage file-data metadata)]
        (is (= "custom/path/custom.txt" (sut/canonical-key (:key result))))
        (is (.isFile (io/file test-dir "custom" "path" "custom.txt")))))

    (testing "drops `..`, `.` and empty segments instead of joining them"
      (doseq [[path expected] [["../a/./b//c" "a/b/c/custom.txt"]
                               ["a\\..\\b"    "a/b/custom.txt"]
                               ["/.."          "custom.txt"]]]
        (is (= expected
               (sut/canonical-key
                (:key (ports/store-file storage file-data
                                        (assoc metadata :path path)))))
            path)))))

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

(deftest ^:unit a-key-cannot-escape-the-storage-root
  ;; The mounted HTTP routes pass a caller-supplied key straight through, and
  ;; `{*file-key}` carries slashes — so `../../deps.edn` reached the adapter and
  ;; read a file outside the root. Containment, checked on the resolved path
  ;; (BOU-421).
  (let [root    (str (java.nio.file.Files/createTempDirectory
                      "wagoe-trav" (make-array java.nio.file.attribute.FileAttribute 0)))
        outside (java.io.File. (str root "/../wagoe-trav-secret.txt"))
        storage (sut/create-local-storage {:base-path root})]
    (try
      (spit outside "SECRET")
      (spit (str root "/inside.txt") "fine")
      (.mkdirs (java.io.File. (str root "/2a")))
      (spit (str root "/2a/photo.jpg") "sharded")

      (testing "an escaping key reads, deletes and exists as nothing"
        (doseq [key ["../wagoe-trav-secret.txt"
                     "../../etc/passwd"
                     "2a/../../wagoe-trav-secret.txt"]]
          (is (nil? (ports/retrieve-file storage key)) key)
          (is (false? (ports/delete-file storage key)) key)
          (is (false? (ports/file-exists? storage key)) key)))

      (testing "and the file outside the root is untouched"
        (is (.exists outside))
        (is (= "SECRET" (slurp outside))))

      (testing "keys inside the root still work, sharded ones included"
        (is (some? (:bytes (ports/retrieve-file storage "inside.txt"))))
        (is (some? (:bytes (ports/retrieve-file storage "2a/photo.jpg")))))

      (finally
        (.delete outside)
        (doseq [f (reverse (file-seq (java.io.File. root)))] (.delete f))))))

(deftest ^:unit every-filesystem-operation-is-root-contained
  ;; The containment check was applied to retrieve/delete/exists and not to
  ;; the write, so an upload naming a symlinked directory inside the root
  ;; created files outside it (BOU-421). All four, one table.
  (let [root    (str (java.nio.file.Files/createTempDirectory
                      "wagoe-write" (make-array java.nio.file.attribute.FileAttribute 0)))
        outside (str root "-outside")
        storage (sut/create-local-storage {:base-path root})]
    (try
      (.mkdirs (java.io.File. outside))
      (java.nio.file.Files/createSymbolicLink
       (java.nio.file.Paths/get (str root "/link") (into-array String []))
       (java.nio.file.Paths/get outside (into-array String []))
       (make-array java.nio.file.attribute.FileAttribute 0))

      (testing "a write through a symlinked directory is refused"
        (is (thrown? Exception
                     (ports/store-file storage
                                       {:bytes (.getBytes "x") :content-type "text/plain"}
                                       {:filename "evil.txt" :path "link"})))
        (is (not (.exists (java.io.File. (str outside "/evil.txt"))))))

      (testing "reads, deletes and existence through the same link are refused"
        (spit (str outside "/secret.txt") "SECRET")
        (is (nil?   (ports/retrieve-file storage "link/secret.txt")))
        (is (false? (ports/delete-file   storage "link/secret.txt")))
        (is (false? (ports/file-exists?  storage "link/secret.txt")))
        (is (.exists (java.io.File. (str outside "/secret.txt")))))

      (testing "ordinary and nested keys still store and read"
        (let [{:keys [key]} (ports/store-file storage
                                              {:bytes (.getBytes "ok") :content-type "text/plain"}
                                              {:filename "ok.txt" :path "sub"})]
          (is (some? key))
          (is (some? (:bytes (ports/retrieve-file storage key))))))

      (finally
        (doseq [f (reverse (file-seq (java.io.File. root)))] (.delete f))
        (doseq [f (reverse (file-seq (java.io.File. outside)))] (.delete f))))))

(deftest ^:unit a-dangling-symlink-cannot-carry-a-write-out-of-the-root
  ;; The containment probe followed links, so a *dangling* link reported "does
  ;; not exist", the walk anchored on its in-root parent, and Files/write then
  ;; followed the link and created the file outside the root (BOU-421 review).
  ;; A live link is the control: it must stay allowed when it resolves inside.
  (let [root    (str (java.nio.file.Files/createTempDirectory
                      "wagoe-dangling" (make-array java.nio.file.attribute.FileAttribute 0)))
        outside (str root "-outside")
        link    (fn [from to]
                  (java.nio.file.Files/createSymbolicLink
                   (java.nio.file.Paths/get from (into-array String []))
                   (java.nio.file.Paths/get to (into-array String []))
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        gone?   (fn [p] (java.nio.file.Files/exists
                         (java.nio.file.Paths/get p (into-array String []))
                         (into-array java.nio.file.LinkOption
                                     [java.nio.file.LinkOption/NOFOLLOW_LINKS])))
        storage (sut/create-local-storage {:base-path root})]
    (try
      (.mkdirs (java.io.File. (str root "/up")))
      (.mkdirs (java.io.File. outside))
      (link (str root "/up/pwned.txt") (str outside "/pwned.txt"))   ; dangling file
      (link (str root "/gone")         (str outside "/gone"))        ; dangling directory

      (testing "a write onto a dangling link is refused, and creates nothing outside"
        (is (thrown? Exception
                     (ports/store-file storage
                                       {:bytes (.getBytes "OWNED") :content-type "text/plain"}
                                       {:filename "pwned.txt" :path "up"})))
        (is (not (gone? (str outside "/pwned.txt")))))

      (testing "and neither does a write under a dangling directory link"
        (is (thrown? Exception
                     (ports/store-file storage
                                       {:bytes (.getBytes "OWNED") :content-type "text/plain"}
                                       {:filename "x.txt" :path "gone"})))
        (is (not (gone? (str outside "/gone")))))

      (testing "a link that resolves inside the root is still served"
        (spit (str root "/up/real.txt") "ok")
        (link (str root "/up/alias.txt") (str root "/up/real.txt"))
        (is (= "ok" (String. ^bytes (:bytes (ports/retrieve-file storage "up/alias.txt"))))))

      (finally
        (doseq [f (reverse (file-seq (java.io.File. root)))] (.delete f))
        (doseq [f (reverse (file-seq (java.io.File. outside)))] (.delete f))))))

(deftest ^:unit a-blank-signing-secret-is-no-signing-secret
  ;; A blank secret is truthy, so it selected the signing branch and
  ;; SecretKeySpec threw "Empty key" — after the bytes were on disk, leaving
  ;; the file orphaned (BOU-421).
  (let [root (str (java.nio.file.Files/createTempDirectory
                   "wagoe-blank" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (doseq [secret ["" "   " nil]]
        (let [storage (sut/create-local-storage {:base-path root
                                                 :url-base "http://x"
                                                 :signing-secret secret})
              result  (ports/store-file storage
                                        {:bytes (.getBytes "x") :content-type "text/plain"}
                                        {:filename "a.txt"})]
          (is (some? (:key result)) (pr-str secret))
          (is (not (str/includes? (str (:url result)) "signature="))
              (str "no signature is promised for secret " (pr-str secret)))))
      (finally (doseq [f (reverse (file-seq (java.io.File. root)))] (.delete f))))))

(deftest ^:unit no-key-spelling-addresses-the-storage-root-itself
  ;; "" resolves to the root itself, so delete-file "" removed the storage
  ;; root — and the catch-all route matches DELETE …/delete/ with an empty
  ;; key. An earlier sweep ran this case and passed it: it asked whether the
  ;; call threw, not whether it did something destructive (BOU-421).
  (let [root    (str (java.nio.file.Files/createTempDirectory
                      "wagoe-blank-key" (make-array java.nio.file.attribute.FileAttribute 0)))
        storage (sut/create-local-storage {:base-path root})]
    (try
      (spit (str root "/keep.txt") "keep")
      ;; Every spelling that resolves to the root itself, not just the blank
      ;; ones: "." and "./" deleted an empty root after the blank-key fix.
      (doseq [k ["" "   " nil "." "./" "a/.." "/"]]
        (is (false? (ports/delete-file storage k)) (pr-str k))
        (is (nil?   (ports/retrieve-file storage k)) (pr-str k))
        (is (false? (ports/file-exists? storage k)) (pr-str k)))

      (testing "the root and its contents survive"
        (is (.exists (java.io.File. root)))
        (is (.exists (java.io.File. (str root "/keep.txt")))))

      (testing "and a real key still deletes"
        (is (true? (ports/delete-file storage "keep.txt")))
        (is (not (.exists (java.io.File. (str root "/keep.txt"))))))

      (finally (doseq [f (reverse (file-seq (java.io.File. root)))] (.delete f))))))

(deftest ^:unit an-empty-storage-root-is-not-deletable-through-a-key
  ;; The earlier case kept a file in the root, so Files/delete failed on a
  ;; non-empty directory and hid the defect: with an empty root, "." and "./"
  ;; removed it outright (BOU-421).
  (doseq [k ["" "." "./" "a/.." "   "]]
    (let [root (str (java.nio.file.Files/createTempDirectory
                     "wagoe-empty-root" (make-array java.nio.file.attribute.FileAttribute 0)))
          storage (sut/create-local-storage {:base-path root})]
      (try
        (is (false? (ports/delete-file storage k)) (pr-str k))
        (is (.exists (java.io.File. root)) (str "root survives " (pr-str k)))
        (finally (.delete (java.io.File. root)))))))

(deftest ^:integration a-streamed-file-is-stored-and-a-failed-one-leaves-nothing
  (let [storage (sut/create-local-storage {:base-path test-dir})
        write!  (fn [^java.io.OutputStream out] (.write out (.getBytes "streamed")))]
    (testing "with a generated key and a named one"
      (doseq [meta [{:filename "s.txt"} {:filename "s.txt" :path "exports"}]]
        (let [r (ports/store-stream storage {:write! write! :content-type "text/plain"} meta)]
          (is (= 8 (:size r)))
          (is (= "streamed" (String. ^bytes (:bytes (ports/retrieve-file storage (:key r)))))))))
    (testing "a writer that throws stores nothing"
      (let [files  #(set (filter (fn [^java.io.File f] (.isFile f)) (file-seq (io/file test-dir))))
            before (files)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"half"
                              (ports/store-stream storage
                                                  {:write! (fn [^java.io.OutputStream out]
                                                             (.write out (.getBytes "partial"))
                                                             (throw (ex-info "half" {:type :internal-error})))
                                                   :content-type "text/plain"}
                                                  {:filename "f.txt" :path "exports"})))
        (is (= before (files)))))))
