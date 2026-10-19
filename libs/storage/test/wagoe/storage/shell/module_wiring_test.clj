(ns wagoe.storage.shell.module-wiring-test
  "The :wagoe/storage Integrant key must build a working storage service from
   the catalogue-advertised config shape ({:provider :local :root ...})."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.storage.shell.module-wiring]
            [wagoe.storage.shell.http-handlers :as http-handlers]
            [wagoe.storage.shell.service :as service]
            [wagoe.storage.ports :as ports]
            [integrant.core :as ig]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [wagoe.storage.shell.adapters.local :as local]))

(def ^:private test-root "target/test-wiring-storage")

(defn- cleanup []
  (let [dir (io/file test-root)]
    (when (.exists dir)
      (doseq [f (reverse (file-seq dir))] (io/delete-file f true)))))

(deftest ^:integration storage-init-key-builds-service-from-catalogue-shape
  (cleanup)
  (try
    (let [component (ig/init-key :wagoe/storage {:provider :local :root test-root})]
      (testing "returns the provider + a usable IStorageService"
        (is (= :local (:provider component)))
        (is (satisfies? service/IStorageService (:service component))))
      (testing "the service round-trips a file to :root"
        (let [{:keys [success data]} (service/upload-file
                                      (:service component)
                                      {:bytes (.getBytes "hi") :content-type "text/plain"}
                                      {:filename "a.txt"} {})]
          (is success)
          (is (true? (ports/file-exists? (:storage component) (:key data))))))
      (ig/halt-key! :wagoe/storage component))
    (finally (cleanup))))

(deftest ^:unit storage-init-key-rejects-unknown-provider
  (is (thrown? clojure.lang.ExceptionInfo
               (ig/init-key :wagoe/storage {:provider :dropbox}))))

(deftest ^:unit storage-routes-key-emits-api-routes
  (cleanup)
  (try
    (let [component (ig/init-key :wagoe/storage {:provider :local :root test-root})
          routes    (ig/init-key :wagoe/storage-routes {:storage component})]
      (testing "routes component exposes Reitit :api data"
        ;; [path {method {...}}] — ADR-037.
        (is (vector? (:api routes)))
        (is (every? #(and (vector? %) (string? (first %)) (map? (second %)))
                    (:api routes)))
        (is (every? #(re-find #"^/storage" (first %)) (:api routes))
            "paths carry no /api prefix (versioning adds it)"))
      (ig/halt-key! :wagoe/storage component))
    (finally (cleanup))))

(deftest ^:unit ig-config-assembles-the-service-and-mounts-routes-on-request
  ;; BOU-421: the routes component existed and no application ever mounted it.
  ;; The module owns its graph now — but mounting stays opt-in, because the
  ;; endpoints carry no authorization: anonymous upload and delete by key.
  (let [base {:provider :local :root "/tmp/x"}]
    (testing "assembled by default, mounted by nobody"
      (let [{:keys [components routes]} (wagoe.storage.shell.module-wiring/ig-config base {})]
        (is (contains? components :wagoe/storage))
        (is (contains? components :wagoe/storage-routes))
        (is (empty? routes)
            "publishing anonymous upload/delete must be a decision, not a default")))
    (testing "only an explicit true mounts them — #env yields strings"
      (doseq [v [false "false" "no" nil 0]]
        (is (empty? (:routes (wagoe.storage.shell.module-wiring/ig-config
                              (assoc base :expose-http? v) {})))
            (str "expose-http? " (pr-str v) " must not publish the routes"))))
    (testing ":expose-http? true mounts them"
      (doseq [v [true "true"]]
        (is (= 1 (count (:routes (wagoe.storage.shell.module-wiring/ig-config
                                  (assoc base :expose-http? v) {}))))
            (pr-str v))))))

(deftest ^:unit ig-config-indexes-uploads-and-contributes-them-to-privacy
  (let [{:keys [components data-export retention]}
        (wagoe.storage.shell.module-wiring/ig-config {:provider :local :root "/tmp/x"} {})]
    (is (= (ig/ref :wagoe.storage/upload-index) (get-in components [:wagoe/storage :upload-index])))
    (is (= (ig/ref :wagoe/db-context) (get-in components [:wagoe.storage/upload-index :ctx])))
    (is (= {:storage (ig/ref :wagoe.storage/data-exporter)} data-export))
    (is (= {:storage/uploads (ig/ref :wagoe.storage/upload-retention)} retention))))

(deftest ^:unit export-jobs-come-with-the-jobs-module
  (let [ig-config #(wagoe.storage.shell.module-wiring/ig-config {:provider :local} {:enabled %})
        without   (ig-config #{})
        with      (ig-config #{:wagoe/jobs})]
    (is (not (contains? (:components without) :wagoe.storage/export-jobs)))
    (is (not (contains? (:components without) :wagoe/export-jobs)))
    (is (= {:export-jobs (ig/ref :wagoe.storage/export-jobs)} (get-in with [:components :wagoe/export-jobs])))
    (is (nil? (:routes without)))
    (is (= [(ig/ref :wagoe.storage/export-job-routes)] (:routes with)))
    (is (= [(ig/ref :wagoe.storage/export-job-handlers)] (:job-handlers with)))
    (is (= (ig/ref :wagoe.storage/export-archive-retention) (get-in with [:retention :storage/export-archives])))))

(deftest ^:unit routes-address-the-sharded-keys-the-adapter-returns
  ;; The local adapter shards every key it hands back — `2a/photo.jpg` — so a
  ;; single-segment :file-key could never match the key it had just returned.
  (let [paths (map first (http-handlers/storage-routes nil {}))]
    (doseq [op ["download" "delete" "url"]]
      (is (some #(= % (str "/storage/" op "/{*file-key}")) paths)
          (str op " must catch the whole key, slashes included")))))

(deftest ^:unit a-signed-url-reaches-the-route-that-verifies-it
  ;; My earlier round-trip test handed the key to the handler directly, so it
  ;; proved the signature and not the address. The URL is built as
  ;; `<url-base>/<key>`, and only this module's download route verifies — so
  ;; the two have to be configurable into agreement (BOU-421).
  (let [root    (str (java.nio.file.Files/createTempDirectory
                      "wagoe-url" (make-array java.nio.file.attribute.FileAttribute 0)))
        base    "/files"
        public  (str "http://app.test/api/v1" base "/download")
        routes  (:api (ig/init-key :wagoe/storage-routes
                                   {:storage (ig/init-key :wagoe/storage
                                                          {:provider :local
                                                           :root root
                                                           :url-base public
                                                           :signing-secret "s3cr3t"
                                                           :http-base-path base})}))
        paths   (set (map first routes))]
    (try
      (testing "the mounted path follows :http-base-path"
        (is (contains? paths (str base "/download/{*file-key}"))
            (pr-str paths)))

      (testing "and a URL the adapter emits lands on exactly that path"
        (let [storage (local/create-local-storage {:base-path root
                                                   :url-base public
                                                   :signing-secret "s3cr3t"})
              {:keys [key url]} (ports/store-file
                                 storage
                                 {:bytes (.getBytes "x") :content-type "text/plain"}
                                 {:filename "a.txt"})
              path-of (-> url (str/replace #"\?.*$" "") (str/replace "http://app.test" ""))]
          (is (= (str "/api/v1" base "/download/" key) path-of)
              "the emitted URL addresses the mounted download route")))
      (finally (doseq [f (reverse (file-seq (java.io.File. root)))] (.delete f))))))

(deftest ^:unit export-requests-per-hour-is-a-whole-number
  (doseq [bad [-1 "5" 1.5]]
    (is (= :configuration-error
           (:type (ex-data (is (thrown? clojure.lang.ExceptionInfo
                                        (ig/init-key :wagoe.storage/export-jobs {:requests-per-hour bad}))))))
        (pr-str bad))))
