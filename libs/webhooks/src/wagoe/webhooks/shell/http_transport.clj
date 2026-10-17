(ns wagoe.webhooks.shell.http-transport
  "`IWebhookTransport` over java.net.http: bounded connect and request
   timeouts, redirects never followed, and at most `max-response-bytes` of the
   answer read."
  (:require [wagoe.webhooks.ports :as ports])
  (:import (java.io InputStream)
           (java.net URI)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest
                          HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration)))

(defn- elapsed-ms [start] (quot (- (System/nanoTime) start) 1000000))

(defn- read-prefix
  "Up to `n` bytes of `in` as a string, waiting at most `wait-ms`; the rest is
   not read. The request timeout ends when the headers arrive, so a receiver
   that trickles its body would otherwise hold the worker."
  [^InputStream in n wait-ms]
  ;; A body that breaks off is still an answer: the status already arrived.
  (let [f    (future (try (String. (.readNBytes in (int n)) StandardCharsets/UTF_8)
                          (catch Exception _ "")))
        body (try (deref f (max 1 wait-ms) ::timeout)
                  (finally (.close in)))]
    (if (= ::timeout body)
      (do (future-cancel f) "")
      body)))

(defrecord HttpTransport [^HttpClient client timeout-ms max-response-bytes]
  ports/IWebhookTransport
  (send-request! [_ {:keys [url headers body]}]
    (let [start   (System/nanoTime)
          builder (-> (HttpRequest/newBuilder (URI. url))
                      (.timeout (Duration/ofMillis timeout-ms))
                      (.POST (HttpRequest$BodyPublishers/ofString body StandardCharsets/UTF_8)))]
      (doseq [[k v] headers] (.header builder k v))
      (try
        (let [^HttpResponse resp (.send client (.build builder) (HttpResponse$BodyHandlers/ofInputStream))]
          {:status     (.statusCode resp)
           :body       (read-prefix (.body resp) max-response-bytes
                                    (- timeout-ms (elapsed-ms start)))
           :latency-ms (elapsed-ms start)})
        (catch InterruptedException e
          (.interrupt (Thread/currentThread))
          (throw e))
        (catch Exception e
          {:error      {:type    :webhooks/no-response
                        :message (str (.getSimpleName (class e)) ": " (.getMessage e))}
           :latency-ms (elapsed-ms start)})))))

(defn create-transport
  "Settings as `service/defaults` gives them."
  [{:keys [timeout-ms connect-timeout-ms max-response-bytes]}]
  (->HttpTransport (-> (HttpClient/newBuilder)
                       (.connectTimeout (Duration/ofMillis connect-timeout-ms))
                       (.followRedirects HttpClient$Redirect/NEVER)
                       (.build))
                   timeout-ms
                   max-response-bytes))
