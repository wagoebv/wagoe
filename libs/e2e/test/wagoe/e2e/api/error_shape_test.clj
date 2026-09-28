(ns wagoe.e2e.api.error-shape-test
  "Error bodies as a client reads them off a running server: one shape,
   `{\"error\": {\"type\": …, \"message\": …}}`, whichever layer refused
   (BOU-586)."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [wagoe.e2e.helpers.reset :as reset]))

(defn- call [method path & [body]]
  (http/request (cond-> {:method           method
                         :url              (str (reset/default-base-url) path)
                         :accept           :json
                         :throw-exceptions false}
                  body (assoc :body body :content-type :json))))

(defn- error-of [resp]
  (:error (json/parse-string (:body resp) true)))

(deftest ^:integration ^:e2e every-refusal-has-the-one-error-shape
  (doseq [[label status method path body]
          [["no credentials"     401 :get    "/api/v1/auth/mfa/status"]
           ["no such route"      404 :get    "/api/v1/no-such-thing"]
           ["no such method"     405 :delete "/api/v1/auth/login"]
           ["a malformed body"   400 :post   "/api/v1/auth/login" "{not json"]
           ["an invalid body"    400 :post   "/api/v1/auth/login" "{}"]]]
    (testing label
      (let [resp (call method path body)
            err  (error-of resp)]
        (is (= status (:status resp)))
        (is (string? (:type err)) (str label ": " (:body resp)))
        (is (string? (:message err)) (str label ": " (:body resp)))))))
