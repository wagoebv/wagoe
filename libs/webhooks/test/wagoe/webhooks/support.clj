(ns wagoe.webhooks.support
  "A local receiver, an in-memory job system, and a service wired to both."
  (:require [wagoe.jobs.ports :as jobs]
            [wagoe.jobs.shell.adapters.in-memory :as mem]
            [wagoe.jobs.shell.worker :as worker]
            [wagoe.webhooks.ports :as ports]
            [wagoe.webhooks.shell.http-transport :as http-transport]
            [wagoe.webhooks.shell.persistence :as persistence]
            [wagoe.webhooks.shell.service :as service])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetAddress InetSocketAddress)
           (java.nio.charset StandardCharsets)))

(def encryption-key "test-encryption-key-at-least-32-characters")

(defn start-receiver!
  "An HTTP server on 127.0.0.1 answering with `(respond request)` —
   `{:status n :body s :headers {}}` — and recording each request. `server`,
   unstarted, replaces the plain HTTP one (an HttpsServer)."
  [respond & [server]]
  (let [seen   (atom [])
        ^HttpServer server (or server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0))]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [^HttpExchange ex ex
                              req {:method  (.getRequestMethod ex)
                                   :path    (.getPath (.getRequestURI ex))
                                   :headers (into {} (map (fn [[k v]] [(.toLowerCase ^String k) (first v)]))
                                                  (.getRequestHeaders ex))
                                   :body    (String. (.readAllBytes (.getRequestBody ex)) StandardCharsets/UTF_8)}
                              _   (swap! seen conj req)
                              {:keys [status body headers]} (respond req)
                              out (.getBytes (str body) StandardCharsets/UTF_8)]
                          (doseq [[k v] headers] (.add (.getResponseHeaders ex) k v))
                          (.sendResponseHeaders ex status (if (zero? (alength out)) -1 (alength out)))
                          (with-open [o (.getResponseBody ex)] (.write o out))))))
    (.start server)
    {:server server
     :seen   seen
     :url    (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/hook")}))

(defn stop-receiver! [{:keys [^HttpServer server]}] (.stop server 0))

(defn with-receiver
  ([respond f] (with-receiver respond nil f))
  ([respond server f]
   (let [r (start-receiver! respond server)]
     (try (f r) (finally (stop-receiver! r))))))

(defn transport
  "The HTTP transport with a 2 s timeout; `settings` override."
  [& [settings]]
  (http-transport/create-transport (merge service/defaults {:timeout-ms 2000} settings)))

(defn rebinding-resolver
  "`[resolve calls]`: a resolver giving a new public address on every lookup,
   and the atom counting them."
  []
  (let [calls (atom 0)]
    [(fn [_] [(InetAddress/getByName (str "203.0.113." (swap! calls inc)))]) calls]))

(defn job-system
  "In-memory queue, store and idempotency store."
  []
  (assoc (mem/create-in-memory-jobs-system)
         :idempotency (mem/create-in-memory-idempotency-store)))

(defn service
  "A service on db-context `ctx` and job system `js`. `settings` override the
   test defaults, which allow 127.0.0.1 over http; `deps` override its deps."
  [ctx js settings & [deps]]
  (service/create-service (merge {:store       (persistence/create-store ctx)
                                  :transport   (transport)
                                  :job-queue   (:queue js)
                                  :idempotency (:idempotency js)}
                                 deps)
                          (merge {:allowed-hosts  #{"127.0.0.1"}
                                  :require-https? false
                                  :encryption-key encryption-key}
                                 settings)))

(defn run-jobs!
  "Run every due delivery job, retries included when their backoff is zero."
  [svc js retry-config]
  (let [registry (worker/create-job-registry)]
    (jobs/register-handler! registry service/job-type #(ports/deliver! svc %))
    (worker/run-pending! {:retry-config retry-config} (:queue js) (:store js) registry)))

(def no-backoff {:initial-delay-ms 0 :jitter false})
