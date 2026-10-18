(ns wagoe.webhooks.shell.http-transport
  "`IWebhookTransport` over Apache HttpClient: bounded connect and request
   timeouts, redirects and retries off, and at most `max-response-bytes` of the
   answer read.

   Apache rather than java.net.http because it takes a DNS resolver: a request
   carrying `:addresses` connects to those, while the Host header, TLS SNI and
   certificate hostname check still use the URL's host."
  (:require [wagoe.webhooks.ports :as ports])
  (:import (java.net InetAddress)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent ScheduledFuture ScheduledThreadPoolExecutor ThreadFactory TimeUnit)
           (javax.net.ssl SSLContext)
           (org.apache.http HttpResponse)
           (org.apache.http.client.config RequestConfig)
           (org.apache.http.client.methods HttpPost)
           (org.apache.http.config Lookup RegistryBuilder)
           (org.apache.http.conn DnsResolver)
           (org.apache.http.conn.socket PlainConnectionSocketFactory)
           (org.apache.http.conn.ssl SSLConnectionSocketFactory)
           (org.apache.http.entity ByteArrayEntity)
           (org.apache.http.impl.client CloseableHttpClient HttpClients)
           (org.apache.http.impl.conn BasicHttpClientConnectionManager SystemDefaultDnsResolver)))

(defn- elapsed-ms [start] (quot (- (System/nanoTime) start) 1000000))

(defn- pinned-resolver
  "Resolves every host to `addresses`; the system resolver when there are none."
  ^DnsResolver [addresses]
  (if (seq addresses)
    (reify DnsResolver
      (resolve [_ _host] (into-array InetAddress addresses)))
    SystemDefaultDnsResolver/INSTANCE))

(defn- client
  "A client for one request: its connection manager holds the pinned resolver,
   so no connection outlives the addresses it was made for."
  ^CloseableHttpClient [^Lookup sockets ^RequestConfig request-config addresses]
  (-> (HttpClients/custom)
      (.setConnectionManager (BasicHttpClientConnectionManager. sockets nil nil (pinned-resolver addresses)))
      (.setDefaultRequestConfig request-config)
      (.disableRedirectHandling)
      (.disableAutomaticRetries)
      (.disableCookieManagement)
      (.disableContentCompression)
      ;; The request sets its own; the default reads a resource on every build.
      (.disableDefaultUserAgent)
      (.build)))

(defonce ^:private deadlines
  ;; Remove on cancel: a cancelled deadline otherwise stays queued until it
  ;; expires, holding its request, body and connection buffers.
  (doto (ScheduledThreadPoolExecutor.
         1 (reify ThreadFactory
             (newThread [_ r] (doto (Thread. ^Runnable r "webhooks-deadline") (.setDaemon true)))))
    (.setRemoveOnCancelPolicy true)))

(defn- read-prefix
  "Up to `n` bytes of the response body as a string. The stream is left open:
   closing it would drain the rest, and closing the client drops it. A body
   that breaks off, or is cut by the deadline, is still an answer: the
   status already arrived."
  [^HttpResponse resp n]
  (if-let [entity (.getEntity resp)]
    (try (String. (.readNBytes (.getContent entity) (int n)) StandardCharsets/UTF_8)
         (catch Exception _ ""))
    ""))

(defrecord HttpTransport [sockets request-config timeout-ms max-response-bytes]
  ports/IWebhookTransport
  (send-request! [_ {:keys [url headers body addresses]}]
    (let [start (System/nanoTime)
          post  (doto (HttpPost. ^String url)
                  (.setEntity (ByteArrayEntity. (.getBytes ^String body StandardCharsets/UTF_8))))
          _     (doseq [[k v] headers] (.setHeader post ^String k ^String v))
          ;; One deadline for headers and body: a receiver that trickles
          ;; either would otherwise hold the worker.
          abort (.schedule ^ScheduledThreadPoolExecutor deadlines ^Runnable #(.abort post)
                           (long timeout-ms) TimeUnit/MILLISECONDS)]
      (try
        (with-open [c (client sockets request-config addresses)]
          (let [resp (.execute c post)]
            {:status     (.getStatusCode (.getStatusLine resp))
             :body       (read-prefix resp max-response-bytes)
             :latency-ms (elapsed-ms start)}))
        (catch Exception e
          {:error      {:type    :webhooks/no-response
                        :message (str (.getSimpleName (class e)) ": " (.getMessage e))}
           :latency-ms (elapsed-ms start)})
        (finally
          (.cancel ^ScheduledFuture abort false))))))

(defn create-transport
  "Settings as `service/defaults` gives them, plus an optional `:ssl-context`
   for the trust store (the JVM default otherwise)."
  [{:keys [^SSLContext ssl-context timeout-ms connect-timeout-ms max-response-bytes]}]
  ;; Built once: the default SSL factory loads the trust store each time.
  (->HttpTransport (-> (RegistryBuilder/create)
                       (.register "http" (PlainConnectionSocketFactory/getSocketFactory))
                       (.register "https" (if ssl-context
                                            (SSLConnectionSocketFactory. ssl-context)
                                            (SSLConnectionSocketFactory/getSocketFactory)))
                       (.build))
                   (-> (RequestConfig/custom)
                       (.setConnectTimeout (int connect-timeout-ms))
                       ;; Backstop should the deadline thread be held up.
                       (.setSocketTimeout (int timeout-ms))
                       (.build))
                   timeout-ms
                   max-response-bytes))
