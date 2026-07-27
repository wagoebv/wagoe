(ns wagoe.storage.shell.module-wiring-test
  "The :wagoe/storage Integrant key must build a working storage service from
   the catalogue-advertised config shape ({:provider :local :root ...})."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.storage.shell.module-wiring]
            [wagoe.storage.shell.service :as service]
            [wagoe.storage.ports :as ports]
            [integrant.core :as ig]
            [clojure.java.io :as io]))

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

(deftest ^:unit storage-routes-key-emits-normalized-api-routes
  (cleanup)
  (try
    (let [component (ig/init-key :wagoe/storage {:provider :local :root test-root})
          routes    (ig/init-key :wagoe/storage-routes {:storage component})]
      (testing "routes component exposes normalized :api vector"
        (is (vector? (:api routes)))
        (is (every? (every-pred :path :methods) (:api routes)))
        (is (every? #(re-find #"^/storage" (:path %)) (:api routes))
            "paths carry no /api prefix (versioning adds it)"))
      (ig/halt-key! :wagoe/storage component))
    (finally (cleanup))))
