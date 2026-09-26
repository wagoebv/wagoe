(ns wagoe.storage.shell.http-error-body-test
  "An untyped exception in a JSON handler must not reach the client (BOU-557)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.platform.shell.http.reitit-router :as reitit]
            [wagoe.storage.shell.http-handlers :as sut]
            [wagoe.storage.ports :as ports]
            [wagoe.storage.shell.service :as service]))

(def ^:private secret "s3://internal-bucket AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI")

(defn- upload [svc]
  (let [handler (reitit/compile-routes (sut/storage-routes svc)
                                       ;; Production: dev answers a missing-:type diagnostic instead.
                                       {:swagger-enabled false
                                        :system          {:environment "production"}})
        resp    (handler {:request-method   :post
                          :uri              "/storage/upload"
                          :headers          {}
                          :multipart-params {"file" {:filename     "a.txt"
                                                     :content-type "text/plain"
                                                     :bytes        (.getBytes "hi")}}})
        body    (:body resp)]
    {:status (:status resp)
     :raw    (if (string? body) body (slurp body))}))

(defn- serve
  "POST /storage/upload through the platform router, with upload-file doing `f`."
  [f]
  (upload (reify service/IStorageService
            (upload-file [_ _ _ _] (f))
            (upload-image [_ _ _ _] nil)
            (download-file [_ _] nil)
            (remove-file [_ _] nil)
            (get-file-url [_ _ _] nil))))

(deftest ^:unit untyped-exception-is-a-generic-500
  (let [{:keys [status raw]} (serve #(throw (RuntimeException. secret)))
        body                 (json/parse-string raw true)]
    (is (= 500 status))
    (is (not (str/includes? raw "wJalrXUtnFEMI")) raw)
    (testing "the platform's error shape"
      (is (= "internal-error" (:error body)))
      (is (= "Internal Server Error" (:message body)))
      (is (contains? body :correlation-id)))))

(deftest ^:unit typed-validation-error-keeps-its-message
  (let [{:keys [status raw]} (serve #(throw (ex-info "File name is not allowed"
                                                     {:type :validation-error})))
        body                 (json/parse-string raw true)]
    (is (= 400 status))
    (is (= "validation-error" (:error body)))
    (is (= "File name is not allowed" (:message body)))))

(deftest ^:unit adapter-failure-does-not-reach-the-body
  (let [storage (reify ports/IFileStorage
                  (store-file [_ _ _] (throw (RuntimeException. secret)))
                  (retrieve-file [_ _] nil)
                  (delete-file [_ _] nil)
                  (file-exists? [_ _] false)
                  (generate-signed-url [_ _ _] nil))
        {:keys [raw]} (upload (service/create-storage-service {:storage storage}))]
    (is (not (str/includes? raw "wJalrXUtnFEMI")) raw)))
