(ns wagoe.webhooks.shell.http-transport-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [wagoe.webhooks.ports :as ports]
            [wagoe.webhooks.shell.http-transport :as http-transport]
            [wagoe.webhooks.shell.service :as service]
            [wagoe.webhooks.support :as support])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpsConfigurator HttpsParameters HttpsServer)
           (java.io File)
           (java.net InetAddress InetSocketAddress)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.security KeyStore)
           (java.util List)
           (javax.net.ssl KeyManagerFactory SNIHostName SNIMatcher SSLContext TrustManagerFactory)))

(def ^:private loopback (InetAddress/getByName "127.0.0.1"))

(defn- transport [& [settings]]
  (http-transport/create-transport (merge service/defaults {:timeout-ms 2000} settings)))

(defn- post! [t url]
  (ports/send-request! t {:url url :headers {"Content-Type" "application/json"} :body "{}"
                          :addresses [loopback]}))

(deftest ^:integration a-pinned-request-connects-to-the-checked-address
  (support/with-receiver (constantly {:status 200 :body "ok"})
    (fn [{:keys [server seen]}]
      ;; rebind.test does not resolve: only the pinned address can reach it.
      (let [port (.getPort (.getAddress ^com.sun.net.httpserver.HttpServer server))
            resp (post! (transport) (str "http://rebind.test:" port "/hook"))]
        (is (= 200 (:status resp)))
        (is (= (str "rebind.test:" port) (get-in (first @seen) [:headers "host"]))
            "the Host header names the URL's host, not the address")))))

(deftest ^:integration a-slow-receiver-is-cut-off-at-the-timeout
  (support/with-receiver (fn [_] (Thread/sleep 1500) {:status 200})
    (fn [{:keys [url]}]
      (let [resp (ports/send-request! (transport {:timeout-ms 200}) {:url url :headers {} :body "{}"})]
        (is (= :webhooks/no-response (get-in resp [:error :type])))
        (is (< (:latency-ms resp) 1000))))))

(defn- key-store
  "A PKCS12 key store holding a self-signed certificate for rebind.test."
  ^KeyStore []
  (let [dir  (.toFile (Files/createTempDirectory "webhooks-tls" (make-array FileAttribute 0)))
        file (File. dir "ks.p12")
        proc (.start (doto (ProcessBuilder.
                            ^List ["keytool" "-genkeypair" "-alias" "k" "-keyalg" "EC"
                                   "-dname" "CN=rebind.test" "-ext" "san=dns:rebind.test"
                                   "-validity" "1" "-storetype" "PKCS12"
                                   "-keystore" (str file) "-storepass" "changeit"])
                       (.redirectErrorStream true)))]
    (try
      (assert (zero? (.waitFor proc)) (slurp (.getInputStream proc)))
      (with-open [in (io/input-stream file)]
        (doto (KeyStore/getInstance "PKCS12") (.load in (.toCharArray "changeit"))))
      (finally (.delete file) (.delete dir)))))

(defn- server-context ^SSLContext [ks]
  (let [kmf (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
              (.init ks (.toCharArray "changeit")))]
    (doto (SSLContext/getInstance "TLS") (.init (.getKeyManagers kmf) nil nil))))

(defn- client-context
  "Trusts only the certificate in `ks`."
  ^SSLContext [^KeyStore ks]
  (let [trust (doto (KeyStore/getInstance "PKCS12")
                (.load nil nil)
                (.setCertificateEntry "c" (.getCertificate ks "k")))
        tmf   (doto (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
                (.init trust))]
    (doto (SSLContext/getInstance "TLS") (.init nil (.getTrustManagers tmf) nil))))

(defn- start-https!
  "An HTTPS server on 127.0.0.1 that records the SNI names it is sent."
  [ks sni]
  (let [server (HttpsServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        ctx    (server-context ks)]
    (.setHttpsConfigurator
     server
     (proxy [HttpsConfigurator] [ctx]
       (configure [^HttpsParameters params]
         (let [p (.getDefaultSSLParameters ctx)]
           (.setSNIMatchers p [(proxy [SNIMatcher] [0]
                                 (matches [name]
                                   (swap! sni conj (.getAsciiName ^SNIHostName name))
                                   true))])
           (.setSSLParameters params p)))))
    (.createContext server "/" (reify HttpHandler
                                 (handle [_ ex]
                                   (let [^HttpExchange ex ex]
                                     (.readAllBytes (.getRequestBody ex))
                                     (.sendResponseHeaders ex 204 -1)
                                     (.close ex)))))
    (.start server)
    server))

(deftest ^:integration tls-checks-the-urls-host-on-a-pinned-request
  (let [ks     (key-store)
        sni    (atom [])
        server (start-https! ks sni)
        port   (.getPort (.getAddress server))
        t      (transport {:ssl-context (client-context ks)})]
    (try
      (testing "SNI and the certificate check use the name, not 127.0.0.1"
        (is (= 204 (:status (post! t (str "https://rebind.test:" port "/hook")))))
        (is (= ["rebind.test"] @sni)))
      (testing "a certificate for another name is refused"
        (is (re-find #"SSLPeerUnverifiedException|SSLHandshakeException"
                     (str (get-in (post! t (str "https://other.test:" port "/hook")) [:error :message])))))
      (finally (.stop server 0)))))
