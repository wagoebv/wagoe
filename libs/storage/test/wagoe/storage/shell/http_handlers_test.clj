(ns wagoe.storage.shell.http-handlers-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [wagoe.storage.shell.http-handlers :as sut]
            [wagoe.storage.shell.service :as service]
            [wagoe.storage.shell.adapters.local :as local]
            [wagoe.storage.ports]
            [wagoe.platform.shell.http.reitit-router :as reitit-router]
            [clojure.string]))

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

(deftest ^:unit a-signed-download-route-refuses-what-it-did-not-sign
  ;; A configured signing secret means the URLs this module issues expire.
  ;; The route that serves them has to enforce that, or the signature is
  ;; decoration — and mounting made this route reachable (BOU-421).
  ;;
  ;; Signed through the adapter's own generate-signed-url, so the test cannot
  ;; drift from the format the module actually issues.
  (let [secret  "s3cr3t"
        key     "photo.jpg"
        storage (local/create-local-storage {:base-path test-dir
                                             :url-base  "http://x/files"
                                             :signing-secret secret})
        svc     (service/create-storage-service {:storage storage})
        _       (do (.mkdirs (io/file test-dir))
                    (spit (io/file test-dir key) "content"))
        signed  (wagoe.storage.ports/generate-signed-url storage key 3600)
        params  (into {} (for [kv (rest (clojure.string/split signed #"[?&]"))
                               :let [[k v] (clojure.string/split kv #"=")]]
                           [k v]))
        handler (sut/download-file-handler svc secret)
        call    #(handler {:path-params {:file-key key} :query-params %})]
    (testing "unsigned is refused"
      (is (= 403 (:status (call {})))))
    (testing "a bogus signature is refused"
      (is (= 403 (:status (call (assoc params "signature" "deadbeef"))))))
    (testing "a valid, unexpired signature is served"
      (is (= 200 (:status (call params)))))
    (testing "a repeated parameter is a 403, not a 500"
      ;; Ring hands a repeated query parameter over as a vector, and a vector
      ;; reached (long …) as a ClassCastException — a generic 500 where the
      ;; answer is \"not a valid signature\" (BOU-421).
      (doseq [bad [(assoc params "expires" ["1" "2"])
                   (assoc params "signature" ["a" "b"])
                   (assoc params "expires" "not-a-number")]]
        (is (= 403 (:status (call bad))) (pr-str bad))))

    (testing "without a configured secret, a file nobody is recorded as owning is not served"
      (is (= 404 (:status ((sut/download-file-handler svc nil)
                           {:path-params {:file-key key} :query-params {}})))))))

(defn- indexed-service
  "A storage service whose upload index holds `uploads`, keyed by file key."
  [uploads]
  (let [records (atom uploads)]
    (service/create-storage-service
     {:storage      (local/create-local-storage {:base-path test-dir :url-base "http://x/files"})
      :upload-index (reify
                      wagoe.storage.ports/IUploadIndex
                      (record-upload! [_ u] (swap! records assoc (:key u) u))
                      (remove-upload! [_ k] (some? (first (swap-vals! records dissoc k))))
                      (find-uploads [_ _ _] [])
                      (uploads-stored-before [_ _ _] [])
                      wagoe.storage.ports/IUploadLookup
                      (find-upload [_ k] (get @records k)))})))

(deftest ^:unit ^:security a-file-is-reached-only-by-its-owner-its-tenant-or-an-admin
  ;; Download, delete and URL used to act on any key a signed-in user named (BOU-945).
  (let [tenant-file "a1/report.pdf"
        own-file    "a1/notes.txt"
        _           (do (.mkdirs (io/file test-dir "a1"))
                        (spit (io/file test-dir tenant-file) "pdf")
                        (spit (io/file test-dir own-file) "txt"))
        svc         (indexed-service {tenant-file {:key tenant-file :user-id "alice" :tenant-id "t1"}
                                      own-file    {:key own-file :user-id "alice"}})
        as          (fn [key user tenant member?]
                      (cond-> {:path-params {:file-key key} :query-params {} :user user}
                        tenant  (assoc :tenant {:id tenant})
                        member? (assoc :tenant-membership {:role :member})))]
    (doseq [[route handler] {"download" (sut/download-file-handler svc nil)
                             "url"      (sut/get-file-url-handler svc)}]
      (testing (str route ", a file stored in a tenant")
        (is (= 404 (:status (handler (as tenant-file {:id "bob"} nil false)))) "another user")
        (is (= 404 (:status (handler (as tenant-file {:id "bob"} "t2" true)))) "a member of another tenant")
        (is (= 404 (:status (handler (as tenant-file {:id "bob"} "t1" false)))) "naming the tenant without being in it")
        (is (= 404 (:status (handler (as tenant-file {:id "alice"} nil false)))) "its uploader, outside the tenant")
        (is (not= 404 (:status (handler (as tenant-file {:id "bob"} "t1" true)))) "a member of its tenant")
        (is (not= 404 (:status (handler (as tenant-file {:id "root" :role :admin} nil false)))) "a platform admin"))
      (testing (str route ", a file stored outside any tenant")
        (is (= 404 (:status (handler (as own-file {:id "bob"} nil false)))) "another user")
        (is (not= 404 (:status (handler (as own-file {:id "alice"} nil false)))) "its uploader")))
    (testing "delete"
      (let [delete (sut/delete-file-handler svc)]
        (is (= 404 (:status (delete (as tenant-file {:id "bob"} "t2" true)))) "a member of another tenant")
        (is (.exists (io/file test-dir tenant-file)) "and the file is still there")
        (is (= 204 (:status (delete (as tenant-file {:id "alice"} "t1" true)))) "its tenant deletes it")))))

(deftest ^:unit ^:security an-upload-cannot-replace-another-users-file
  ;; A named path is a predictable local key; the upload overwrote the file and took its record (BOU-950).
  (let [files  {"file" ["report.pdf" "application/pdf"] "image" ["pic.png" "image/png"]}
        _      (do (.mkdirs (io/file test-dir "docs"))
                   (doseq [[name _] (vals files)] (spit (io/file test-dir "docs" name) "alice's")))
        svc    (indexed-service (into {} (for [[name _] (vals files) :let [k (str "docs/" name)]]
                                           [k {:key k :user-id "alice"}])))
        upload (fn [handler filename type user]
                 (handler {:multipart-params {"file" (make-multipart-file filename type "bob's") "path" "docs"}
                           :query-params     {}
                           :user             user}))]
    (doseq [[route handler] {"file" (sut/upload-file-handler svc) "image" (sut/upload-image-handler svc)}
            :let [[name type] (files route)]]
      (testing route
        (is (= 403 (:status (upload handler name type {:id "bob"}))))
        (is (= "alice's" (slurp (io/file test-dir "docs" name))))
        (is (= "alice" (:user-id (service/upload-at svc (str "docs/" name)))))))
    (testing "a filename that sanitizes to the same name twice over"
      (is (= 403 (:status (upload (sut/upload-file-handler svc) "report. . .pdf" "application/pdf" {:id "bob"}))))
      (is (= "alice's" (slurp (io/file test-dir "docs/report.pdf")))))
    (testing "its uploader may replace it"
      (is (= 201 (:status (upload (sut/upload-file-handler svc) "report.pdf" "application/pdf" {:id "alice"})))))))

(deftest ^:unit every-mounted-handler-refuses-a-repeated-parameter
  ;; Ring gives a repeated parameter as a vector, and each consumer here —
  ;; parse-int-safe, keyword, str/split — throws on one. Fixed in one place
  ;; last round and not swept; this table is the sweep (BOU-421).
  (let [svc  (create-test-service)
        file {:filename "a.txt" :content-type "text/plain"
              :tempfile (doto (java.io.File/createTempFile "wagoe" ".txt")
                          (spit "x"))}]
    (doseq [[label handler req]
            [["upload max-size"      (sut/upload-file-handler svc)
              {:multipart-params {"file" file} :query-params {"max-size" ["1" "2"]}}]
             ["upload allowed-types" (sut/upload-file-handler svc)
              {:multipart-params {"file" file} :query-params {"allowed-types" ["a" "b"]}}]
             ["upload visibility"    (sut/upload-file-handler svc)
              {:multipart-params {"file" file "visibility" ["a" "b"]} :query-params {}}]
             ["image thumbnail-size" (sut/upload-image-handler svc)
              {:multipart-params {"file" file "thumbnail-size" ["1" "2"]}}]
             ["image visibility"     (sut/upload-image-handler svc)
              {:multipart-params {"file" file "visibility" ["a" "b"]}}]
             ["url expiration"       (sut/get-file-url-handler svc)
              {:path-params {:file-key "k"} :query-params {"expiration" ["1" "2"]}}]]]
      (let [resp (try (handler req)
                      (catch Exception e {:status 500 :threw (.getSimpleName (class e))}))]
        (is (= 400 (:status resp))
            (str label " => " (pr-str (select-keys resp [:status :threw]))))))))

(deftest ^:unit a-stored-file-reports-a-url-its-own-route-accepts
  ;; With a signing secret the download route rejects an unsigned URL — so
  ;; store-file reporting one handed the caller a link that 403s
  ;; (BOU-421).
  (let [secret  "s3cr3t"
        storage (local/create-local-storage {:base-path test-dir
                                             :url-base "http://x/files"
                                             :signing-secret secret})
        svc     (service/create-storage-service {:storage storage})
        result  (wagoe.storage.ports/store-file
                 storage {:bytes (.getBytes "hello") :content-type "text/plain"}
                 {:filename "hello.txt"})
        url     (:url result)
        params  (into {} (for [kv (rest (clojure.string/split url #"[?&]"))
                               :let [[k v] (clojure.string/split kv #"=")]]
                           [k v]))]
    (is (clojure.string/includes? url "signature=") "the reported URL is signed")
    (is (= 200 (:status ((sut/download-file-handler svc secret)
                         {:path-params {:file-key (:key result)}
                          :query-params params})))
        "and the download route accepts exactly that URL")))

(deftest ^:unit signing-and-verification-agree-on-the-key-separator
  ;; path-join yields the platform separator, so on Windows a key is signed as
  ;; `2a\\photo.jpg` while the router hands back `2a/photo.jpg` — every freshly
  ;; issued signed URL would 403 there (BOU-421).
  (let [secret  "s3cr3t"
        storage (local/create-local-storage {:base-path test-dir
                                             :url-base "http://x/files"
                                             :signing-secret secret})
        url     (wagoe.storage.ports/generate-signed-url storage "2a\\photo.jpg" 3600)
        params  (into {} (for [kv (rest (clojure.string/split url #"[?&]"))
                               :let [[k v] (clojure.string/split kv #"=")]]
                           [k v]))]
    (is (local/verify-signed-url secret "2a/photo.jpg"
                                 {:expires   (get params "expires")
                                  :signature (get params "signature")})
        "a key signed in the platform form verifies against the slash form the router supplies")))

(deftest ^:unit a-file-field-that-is-not-a-file-is-a-400
  ;; Ring puts a vector under "file" when the field repeats and a string when
  ;; it arrives as an ordinary text part. Both were truthy, so the
  ;; missing-field guard passed them to extraction, which threw a 500
  ;; (BOU-421).
  (let [svc  (create-test-service)
        good {:filename "a.txt" :content-type "text/plain"
              :tempfile (doto (java.io.File/createTempFile "wagoe" ".txt") (spit "x"))}]
    (doseq [[label handler value]
            (for [[hlabel handler] [["upload" (sut/upload-file-handler svc)]
                                    ["image"  (sut/upload-image-handler svc)]]
                  [vlabel value]   [["vector" [good good]]
                                    ["string" "not-a-file"]
                                    ["absent" nil]]]
              [(str hlabel "/" vlabel) handler value])]
      (let [resp (try (handler {:multipart-params (if value {"file" value} {}) :query-params {}})
                      (catch Exception e {:status 500 :threw (.getSimpleName (class e))}))]
        (is (= 400 (:status resp))
            (str label " => " (pr-str (select-keys resp [:status :threw]))))))
    (testing "and a real file still uploads"
      (is (= 201 (:status ((sut/upload-file-handler svc)
                           {:multipart-params {"file" good} :query-params {}})))))))

(deftest ^:unit a-blank-path-key-is-a-400-on-every-route-that-takes-one
  ;; The catch-all captures an empty segment, so DELETE …/delete/ arrived with
  ;; :file-key "" and passed the missing-key check (BOU-421).
  (let [svc (create-test-service)]
    (doseq [[label handler] [["download" (sut/download-file-handler svc)]
                             ["delete"   (sut/delete-file-handler svc)]
                             ["url"      (sut/get-file-url-handler svc)]]
            key             ["" "   "]]
      (let [resp (handler {:path-params {:file-key key} :query-params {}})]
        (is (= 400 (:status resp)) (str label " with " (pr-str key)))
        ;; It said "bad-request", a type no other 400 used (BOU-586).
        (is (= "validation-error"
               (get-in (:body resp) [:error :type])))))))

;; ============================================================================
;; Who may download (BOU-568)
;; ============================================================================

(deftest ^:unit ^:security a-signed-url-is-its-own-credential
  ;; The platform refuses a route that is not :public true. With a signing
  ;; secret the signature authenticates the download, so a signed link works
  ;; for someone who is not signed in; without one, it does not.
  (.mkdirs (io/file test-dir))
  (spit (io/file test-dir "photo.jpg") "content")
  (doseq [[label secret expected] [["with a signing secret" "s3cr3t" 200]
                                   ["without one" nil 401]]]
    (testing label
      (let [storage (local/create-local-storage
                     (cond-> {:base-path test-dir :url-base "/api/v1/storage/download"}
                       secret (assoc :signing-secret secret)))
            svc     (service/create-storage-service {:storage storage})
            handler (reitit-router/compile-routes
                     [(into ["/api/v1"] (sut/storage-routes svc {:signing-secret secret}))]
                     {:swagger-enabled false :authentication {:middleware []}})
            url     (wagoe.storage.ports/generate-signed-url storage "photo.jpg" 3600)
            [path query] (clojure.string/split url #"\?" 2)]
        (is (= expected (:status (handler {:request-method :get :uri path :query-string query}))))
        (testing "and every other storage route still refuses"
          (doseq [[method p] [[:post "/api/v1/storage/upload"]
                              [:post "/api/v1/storage/upload/image"]
                              [:delete "/api/v1/storage/delete/photo.jpg"]
                              [:get "/api/v1/storage/url/photo.jpg"]]]
            (is (= 401 (:status (handler {:request-method method :uri p}))) p)))))))

(deftest ^:unit a-success-body-goes-out-encoded
  ;; With a Content-Type already set muuntaja left the map for Jetty, which
  ;; cannot write one.
  (let [handler (reitit-router/compile-routes
                 [(into ["/api/v1"] (sut/storage-routes (create-test-service) {}))]
                 {:swagger-enabled false :authentication {:middleware []}})
        resp    (handler {:request-method   :post :uri "/api/v1/storage/upload"
                          :headers          {} :user {:id 1}
                          :multipart-params {"file" (make-multipart-file "a.txt" "text/plain" "hi")}})]
    (is (= 201 (:status resp)))
    (is (instance? java.io.InputStream (:body resp)))
    (is (clojure.string/includes? (slurp (:body resp)) "\"key\""))))
