(ns wagoe.push.shell.adapters.fcm
  (:require [wagoe.push.ports :as ports]
            [cheshire.core :as json]
            [clojure.tools.logging :as log])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration]
           [java.util.concurrent CompletableFuture]
           [com.google.auth.oauth2 GoogleCredentials]
           [java.io FileInputStream]))

(defn- load-credentials [credentials-path]
  (-> (FileInputStream. ^String credentials-path)
      (GoogleCredentials/fromStream)
      (.createScoped ["https://www.googleapis.com/auth/firebase.messaging"])))

(defn- get-access-token [^GoogleCredentials credentials]
  (.refreshIfExpired credentials)
  (-> credentials .getAccessToken .getTokenValue))

(defn- fcm-url [project-id]
  (str "https://fcm.googleapis.com/v1/projects/" project-id "/messages:send"))

(defn- build-request [url body access-token]
  (-> (HttpRequest/newBuilder)
      (.uri (URI/create url))
      (.header "Content-Type" "application/json")
      (.header "Authorization" (str "Bearer " access-token))
      (.POST (HttpRequest$BodyPublishers/ofString
              (json/generate-string body)
              StandardCharsets/UTF_8))
      (.timeout (Duration/ofSeconds 10))
      (.build)))

(defn- parse-response [response token]
  (let [status (.statusCode response)
        body   (json/parse-string (.body response) true)]
    (if (<= 200 status 299)
      {:success?     true
       :message-id   (:name body)
       :device-token token
       :platform     :fcm}
      (let [error-code (get-in body [:error :status])]
        (log/warnf "FCM send failed: %s %s" status error-code)
        {:success?       false
         :device-token   token
         :platform       :fcm
         ;; ports.clj has documented ":error map" all along; the adapters
         ;; returned a string (BOU-323, ADR-036 §3).
         :error          {:type :provider-rejected :message error-code}
         :token-invalid? (contains? #{"UNREGISTERED" "INVALID_ARGUMENT"} error-code)}))))

(defrecord FCMProvider [project-id credentials http-client]
  ports/IFCMProvider

  (fcm-send! [_ payload]
    (let [token        (get-in payload [:message :token])
          access-token (get-access-token credentials)
          request      (build-request (fcm-url project-id) payload access-token)
          response     (.send ^HttpClient http-client request (HttpResponse$BodyHandlers/ofString))]
      (parse-response response token)))

  (fcm-send-multicast! [_ payload tokens]
    (let [access-token (get-access-token credentials)
          url          (fcm-url project-id)
          futures      (mapv (fn [token]
                               (let [per-token (assoc-in payload [:message :token] token)
                                     request   (build-request url per-token access-token)]
                                 {:token  token
                                  :future (.sendAsync ^HttpClient http-client request
                                                      (HttpResponse$BodyHandlers/ofString))}))
                             tokens)]
      (mapv (fn [{:keys [token future]}]
              (try
                (parse-response (.get ^CompletableFuture future) token)
                (catch Exception e
                  (log/warnf "FCM async send failed for %s: %s" token (.getMessage e))
                  {:success?     false
                   :device-token token
                   :platform     :fcm
                   :error        {:type :push-send-failed :message (.getMessage e)}})))
            futures)))

  (fcm-validate-token [this token]
    (let [payload {:message {:token token :data {"validate_only" "true"}}}
          result  (ports/fcm-send! this payload)]
      {:valid? (:success? result) :token token})))

(defn make-fcm-provider [project-id credentials-path]
  (let [creds  (load-credentials credentials-path)
        client (-> (HttpClient/newBuilder)
                   (.connectTimeout (Duration/ofSeconds 10))
                   (.build))]
    (->FCMProvider project-id creds client)))
