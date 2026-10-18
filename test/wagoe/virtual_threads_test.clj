(ns wagoe.virtual-threads-test
  "On virtual threads a request and a job still run in their tenant's schema,
   and their log lines still carry the tenant id in the MDC. Both reach the
   schema through the dynamic binding of the transaction connection, and the
   MDC is a ThreadLocal, so these are what a thread change could break."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [support.embedded-pg :as epg]
            [wagoe.jobs.core.job :as job]
            [wagoe.jobs.ports :as job-ports]
            [wagoe.jobs.shell.adapters.in-memory :as in-memory]
            [wagoe.jobs.shell.tenant-context :as tenant-jobs]
            [wagoe.jobs.shell.worker :as worker]
            [wagoe.observability.logging.ports :as log-ports]
            [wagoe.observability.logging.shell.adapters.slf4j :as slf4j]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.adapters.database.factory :as factory]
            [wagoe.platform.shell.system.wiring]
            [wagoe.tenant.ports :as tenant-ports]
            [wagoe.tenant.shell.provisioning :as provisioning]
            [wagoe.tenant.shell.tenant-middleware :as tenant-mw])
  (:import [ch.qos.logback.classic Level Logger]
           [ch.qos.logback.classic.spi ILoggingEvent]
           [ch.qos.logback.core AppenderBase]
           [java.net ServerSocket URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [org.eclipse.jetty.server ServerConnector]
           [org.slf4j LoggerFactory]))

(def ^:private logger-name "wagoe.virtual-threads-test")

(defn- with-captured-mdc
  "Calls (f logger seen): `seen` collects the MDC of every line `logger` writes,
   read on the logging thread."
  [f]
  (let [seen     (atom [])
        ^Logger l (LoggerFactory/getLogger ^String logger-name)
        appender (proxy [AppenderBase] []
                   (append [^ILoggingEvent e]
                     (swap! seen conj (into {} (.getMDCPropertyMap e)))))]
    (doto ^AppenderBase appender (.setContext (.getLoggerContext l)) (.start))
    (doto l (.setLevel Level/INFO) (.setAdditive false) (.addAppender appender))
    (try (f (slf4j/create-slf4j-logger {:provider :slf4j :logger-name logger-name}) seen)
         (finally (doto l (.detachAppender ^AppenderBase appender) (.setLevel nil) (.setAdditive true))))))

(defn- note
  "The note the current schema holds, read through the db-context rather than
   the transaction handed in, so only the bound connection can find it."
  [ctx]
  (:v (first (db/execute-query! ctx {:select [:v] :from [:notes]}))))

(defn- with-tenant-db [f]
  (let [pg  (epg/start!)
        ctx (epg/db-context pg)]
    (try
      (doseq [sql ["CREATE SCHEMA tenant_acme"
                   "CREATE TABLE public.notes (v TEXT)"
                   "INSERT INTO public.notes VALUES ('public')"
                   "CREATE TABLE tenant_acme.notes (v TEXT)"
                   "INSERT INTO tenant_acme.notes VALUES ('tenant')"]]
        (jdbc/execute! (:datasource ctx) [sql]))
      (f ctx)
      (finally
        (factory/close-db-context! ctx)
        (epg/stop! pg)))))

(defn- free-port []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(deftest ^:integration a-request-on-a-virtual-thread-keeps-its-tenant-context
  (with-tenant-db
    (fn [ctx]
      (with-captured-mdc
        (fn [logger seen]
          (let [tenant  {:id "acme" :schema-name "tenant_acme"}
                handler (-> (fn [req]
                              (log-ports/info logger "served" {:tenant-id (get-in req [:tenant :id])})
                              {:status 200
                               :body   (str (.isVirtual (Thread/currentThread)) " " (note ctx))})
                            (tenant-mw/wrap-tenant-schema ctx)
                            ((fn [h] (fn [req] (h (assoc req :tenant tenant))))))
                server  (ig/init-key :wagoe/http-server {:handler          handler
                                                         :port             (free-port)
                                                         :host             "127.0.0.1"
                                                         :virtual-threads? true})]
            (try
              (let [port (.getLocalPort ^ServerConnector (first (.getConnectors server)))
                    resp (.send (HttpClient/newHttpClient)
                                (.build (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" port "/"))))
                                (HttpResponse$BodyHandlers/ofString))]
                (testing "served on a virtual thread, in the tenant's schema"
                  (is (= "true tenant" (.body resp))))
                (testing "and logged with the tenant id in the MDC"
                  (is (= ["acme"] (map #(get % "tenantId") @seen)))))
              (finally (ig/halt-key! :wagoe/http-server server)))))))))

(deftest ^:integration a-job-on-a-virtual-thread-keeps-its-tenant-context
  (with-tenant-db
    (fn [ctx]
      (with-captured-mdc
        (fn [logger seen]
          (with-redefs [tenant-ports/get-tenant (fn [_ id] (when (= id "acme")
                                                             {:id id :schema-name "tenant_acme"}))]
            (let [{:keys [queue store]} (in-memory/create-in-memory-jobs-system)
                  registry (worker/create-job-registry)
                  result   (promise)
                  provider (provisioning/create-tenant-schema-provider)]
              (job-ports/register-handler!
               registry :read-note
               (fn [{:keys [tenant-id]}]
                 (deliver result
                          (tenant-jobs/process-tenant-job!
                           {:id (random-uuid) :job-type :read-note :args {}
                            :metadata {:tenant-id tenant-id}}
                           (fn [_ _]
                             (log-ports/info logger "ran" {:tenant-id tenant-id})
                             [(.isVirtual (Thread/currentThread)) (note ctx)])
                           ctx ::tenant-service provider))
                 {:success? true :result nil}))
              (job-ports/enqueue-job! queue :default (job/create-job {:job-type :read-note
                                                                      :args     {:tenant-id "acme"}
                                                                      :queue    :default}
                                                                     (random-uuid) (java.time.Instant/now)))
              (let [w (worker/create-worker {:queue-name :default :poll-interval-ms 50
                                             :virtual-threads? true}
                                            queue store registry)]
                (try
                  (testing "ran on a virtual thread, in the tenant's schema"
                    (is (= [true "tenant"] (deref result 10000 ::timeout))))
                  (testing "and logged with the tenant id in the MDC"
                    (is (= ["acme"] (map #(get % "tenantId") @seen))))
                  (finally (job-ports/stop-worker! w (:id (:state w)))))))))))))
