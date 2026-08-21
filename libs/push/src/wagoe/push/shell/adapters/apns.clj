(ns wagoe.push.shell.adapters.apns
  (:require [wagoe.push.ports :as ports]
            [cheshire.core :as json]
            [clojure.tools.logging :as log])
  (:import [java.net URI]
           [java.net.http HttpClient HttpClient$Version HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Paths]
           [java.security KeyFactory]
           [java.security.spec PKCS8EncodedKeySpec]
           [java.time Duration Instant]
           [java.util Base64]
           [java.util.concurrent CompletableFuture]))

(defn- load-p8-key [key-path]
  (let [raw    (String. (Files/readAllBytes (Paths/get key-path (into-array String []))))
        pem    (-> raw
                   (.replace "-----BEGIN PRIVATE KEY-----" "")
                   (.replace "-----END PRIVATE KEY-----" "")
                   (.replaceAll "\\s" ""))
        decoded (.decode (Base64/getDecoder) pem)
        spec   (PKCS8EncodedKeySpec. decoded)]
    (.generatePrivate (KeyFactory/getInstance "EC") spec)))

(defn- base64url [^bytes bs]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))

(defn- make-jwt [team-id key-id private-key]
  (let [header  (json/generate-string {:alg "ES256" :kid key-id})
        now     (.getEpochSecond (Instant/now))
        payload (json/generate-string {:iss team-id :iat now})
        signing-input (str (base64url (.getBytes header StandardCharsets/UTF_8))
                           "." (base64url (.getBytes payload StandardCharsets/UTF_8)))
        sig     (let [signer (java.security.Signature/getInstance "SHA256withECDSA")]
                  (.initSign signer private-key)
                  (.update signer (.getBytes signing-input StandardCharsets/UTF_8))
                  (.sign signer))]
    (str signing-input "." (base64url sig))))

(defn- apns-host [sandbox?]
  (if sandbox?
    "https://api.sandbox.push.apple.com"
    "https://api.push.apple.com"))

(defn- build-apns-request [host bundle-id jwt payload device-token]
  (let [url  (str host "/3/device/" device-token)
        body (json/generate-string payload)]
    (-> (HttpRequest/newBuilder)
        (.uri (URI/create url))
        (.header "Content-Type" "application/json")
        (.header "Authorization" (str "bearer " jwt))
        (.header "apns-topic" bundle-id)
        (.header "apns-push-type" (if (= 1 (get-in payload [:aps :content-available]))
                                    "background" "alert"))
        (.POST (HttpRequest$BodyPublishers/ofString body StandardCharsets/UTF_8))
        (.timeout (Duration/ofSeconds 10))
        (.build))))

(defn- parse-apns-response [response device-token]
  (if (<= 200 (.statusCode response) 299)
    (let [apns-id-opt (.firstValue (.headers response) "apns-id")]
      {:success?     true
       :apns-id      (when (.isPresent apns-id-opt) (.get apns-id-opt))
       :message-id   (when (.isPresent apns-id-opt) (.get apns-id-opt))
       :device-token device-token
       :platform     :apns})
    (let [resp-body (try (json/parse-string (.body response) true) (catch Exception _ {}))
          reason    (:reason resp-body)]
      (log/warnf "APNs send failed: %d %s" (.statusCode response) reason)
      {:success?       false
       :device-token   device-token
       :platform       :apns
       :error          {:type :provider-rejected :message reason}
       :token-invalid? (contains? #{"BadDeviceToken" "Unregistered"} reason)})))

(defrecord APNsProvider [team-id key-id private-key bundle-id sandbox? http-client]
  ports/IAPNsProvider

  (apns-send! [_ payload device-token]
    (let [jwt     (make-jwt team-id key-id private-key)
          host    (apns-host sandbox?)
          request (build-apns-request host bundle-id jwt payload device-token)
          response (.send ^HttpClient http-client request (HttpResponse$BodyHandlers/ofString))]
      (parse-apns-response response device-token)))

  (apns-send-batch! [_ payload device-tokens]
    (let [jwt     (make-jwt team-id key-id private-key)
          host    (apns-host sandbox?)
          futures (mapv (fn [token]
                          {:token  token
                           :future (.sendAsync ^HttpClient http-client
                                               (build-apns-request host bundle-id jwt payload token)
                                               (HttpResponse$BodyHandlers/ofString))})
                        device-tokens)]
      (mapv (fn [{:keys [token future]}]
              (try
                (parse-apns-response (.get ^CompletableFuture future) token)
                (catch Exception e
                  (log/warnf "APNs async send failed for %s: %s" token (.getMessage e))
                  {:success?     false
                   :device-token token
                   :platform     :apns
                   :error        {:type :push-send-failed :message (.getMessage e)}})))
            futures))))

(defn make-apns-provider [team-id key-id key-path bundle-id sandbox?]
  (let [pk     (load-p8-key key-path)
        client (-> (HttpClient/newBuilder)
                   (.version HttpClient$Version/HTTP_2)
                   (.connectTimeout (Duration/ofSeconds 10))
                   (.build))]
    (->APNsProvider team-id key-id pk bundle-id sandbox? client)))
