(ns wagoe.external.shell.adapters.soap-server-test
  "SoapClient against an in-process HTTP stub (com.sun.net.httpserver) that
   answers like iBABS List.svc."
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.tools.logging.test :as log-test]
            [wagoe.cache.shell.adapters.in-memory :as cache-mem]
            [wagoe.external.core.soap-test :as fixtures]
            [wagoe.external.ports :as ports]
            [wagoe.external.shell.adapters.soap :as soap])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.io StringWriter]
           [java.net InetSocketAddress ServerSocket]
           [java.util.concurrent Executors]))

(def ^:private password "s3cret-pa55")
(def ^:private ^:dynamic *port* nil)
(def ^:private requests (atom []))
(def ^:private responder (atom nil))

(defn- respond! [^HttpExchange ex {:keys [status body delay-ms] :or {status 200}}]
  (when delay-ms (Thread/sleep (long delay-ms)))
  (let [bytes (.getBytes ^String body "UTF-8")]
    (.add (.getResponseHeaders ex) "Content-Type" "text/xml; charset=utf-8")
    (.sendResponseHeaders ex status (alength bytes))
    (with-open [out (.getResponseBody ex)]
      (.write out bytes))))

(defn- handler []
  (reify HttpHandler
    (handle [_ ex]
      (let [request {:action        (.getFirst (.getRequestHeaders ex) "SOAPAction")
                     :content-type  (.getFirst (.getRequestHeaders ex) "Content-Type")
                     :authorization (.getFirst (.getRequestHeaders ex) "Authorization")
                     :body          (slurp (.getRequestBody ex) :encoding "UTF-8")}
            n       (count (swap! requests conj request))]
        (try
          (respond! ex (@responder (assoc request :n n)))
          (catch java.io.IOException _))))))

(defn- with-stub-server [f]
  (let [server (doto (HttpServer/create (InetSocketAddress. "localhost" 0) 0)
                 (.createContext "/api/v2/List.svc" (handler))
                 (.setExecutor (Executors/newCachedThreadPool))
                 .start)]
    (try
      (binding [*port* (.getPort (.getAddress server))]
        (f))
      (finally (.stop server 0)))))

(use-fixtures :once with-stub-server)
(use-fixtures :each (fn [f] (reset! requests []) (f)))

(defn- answer [body] (constantly {:body body}))

(def ^:private throttled {:status 500 :body fixtures/throttle-fault})

(defn- client [& {:as overrides}]
  (soap/create-soap-client
   (merge {:endpoint       (str "http://localhost:" *port* "/api/v2/List.svc")
           :contract       "IList"
           :data-namespace "http://schemas.datacontract.org/2004/07/iBabsWebService.Objects"
           :username       "wba"
           :password       password
           :timeout-ms     2000
           :max-attempts   3
           :backoff-ms     1
           :retry-faults   ["Unknown fault occured"]
           :operations     {:GetListEntries {:arrays #{:list-entries}}}}
          overrides)))

(defn- refused-port []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(deftest ^:integration success-test
  (reset! responder (answer fixtures/get-list-entries-response))
  (let [{:keys [success? result]}
        (ports/call! (client) :GetListEntries
                     [[:Sitename "town"]
                      [:filterRequest {:Sitename "town" :SinceDate "2026-10-01T00:00:00" :ListId "l-1"}]])
        {:keys [action content-type authorization body]} (first @requests)]
    (is success?)
    (is (= [{:entry-id "42" :master-id "m-42"
             :values {"Titel" "Raadsvergadering" "Locatie" nil} :tags ["raad"]}]
           (:list-entries result)))
    (testing "the request a WCF service expects"
      (is (= "\"http://tempuri.org/IList/GetListEntries\"" action))
      (is (= "text/xml; charset=utf-8" content-type))
      (is (str/starts-with? authorization "Basic "))
      (is (str/includes? body "<d:ListId>l-1</d:ListId><d:SinceDate>2026-10-01T00:00:00</d:SinceDate>")))))

(deftest ^:integration credentials-in-the-body-test
  (reset! responder (answer fixtures/get-lists-response))
  (is (:success? (ports/call! (client :basic-auth? false :credential-elements ["username" "password"])
                              :GetLists {:Sitename "town"})))
  (let [{:keys [authorization body]} (first @requests)]
    (is (nil? authorization))
    (is (str/includes? body (str "<t:username>wba</t:username><t:password>" password "</t:password><t:Sitename>town")))))

(deftest ^:integration fault-test
  (reset! responder (constantly {:status 500
                                 :body (str/replace fixtures/throttle-fault "Unknown fault occured" "List not found")}))
  (let [{:keys [success? error]} (ports/call! (client) :GetListEntryByMasterId {:masterId "x"})]
    (is (false? success?))
    (is (= {:type :fault :code "a:InternalServiceFault" :message "List not found"
            :operation "GetListEntryByMasterId" :attempts 1}
           error))
    (is (= 1 (count @requests)))))

(deftest ^:integration throttle-fault-is-retried-test
  (testing "until the service answers"
    (reset! responder (fn [{:keys [n]}] (if (< n 3) throttled {:body fixtures/get-lists-response})))
    (let [{:keys [success? result]} (ports/call! (client) :GetLists {:Sitename "town"})]
      (is success?)
      (is (= 2 (count (:lists result))))
      (is (= 3 (count @requests)))))

  (testing "or the attempts run out"
    (reset! requests [])
    (reset! responder (constantly throttled))
    (let [{:keys [error]} (ports/call! (client) :GetLists {:Sitename "town"})]
      (is (= :fault (:type error)))
      (is (= 3 (:attempts error)))
      (is (= 3 (count @requests))))))

(deftest ^:integration timeout-test
  (reset! responder (constantly {:body fixtures/get-lists-response :delay-ms 1500}))
  (testing "a slow answer is abandoned at the operation's timeout, and retried"
    (let [started (System/nanoTime)
          {:keys [success? error]} (ports/call! (client :max-attempts 2) :GetLists {} {:timeout-ms 200})]
      (is (false? success?))
      (is (= :external-error (:type error)))
      (is (= 2 (:attempts error)))
      (is (< (/ (- (System/nanoTime) started) 1e6) 1400))))

  (testing "not retried for an operation that may not run twice"
    (is (= 1 (get-in (ports/call! (client) :GetLists {} {:timeout-ms 200 :idempotent? false})
                     [:error :attempts])))))

(deftest ^:integration transport-failures-are-retried-test
  (testing "a 503 without a fault"
    (reset! responder (fn [{:keys [n]}] (if (= 1 n)
                                          {:status 503 :body "<html>Service Unavailable</html>"}
                                          {:body fixtures/get-lists-response})))
    (is (:success? (ports/call! (client) :GetLists {})))
    (is (= 2 (count @requests))))

  (testing "a refused connection uses every attempt"
    (let [{:keys [error]} (ports/call! (client :endpoint (str "http://localhost:" (refused-port) "/List.svc"))
                                       :GetLists {})]
      (is (= :external-error (:type error)))
      (is (= 3 (:attempts error)))
      (is (str/includes? (:message error) "3 attempts"))))

  (testing "a 404 is not retried"
    (reset! requests [])
    (reset! responder (constantly {:status 404 :body "not here"}))
    (is (= 404 (get-in (ports/call! (client) :GetLists {}) [:error :status])))
    (is (= 1 (count @requests)))))

(deftest ^:integration an-unreachable-service-opens-the-breaker-test
  (let [c (client :endpoint (str "http://localhost:" (refused-port) "/List.svc")
                  :max-attempts 1
                  :cache (cache-mem/create-in-memory-cache {})
                  :circuit-breaker {:failure-threshold 1 :open-ms 60000})]
    (is (= :external-error (get-in (ports/call! c :GetLists {}) [:error :type])))
    (testing "the next call is declined without connecting"
      (let [{:keys [success? error]} (ports/call! c :GetLists {})]
        (is (false? success?))
        (is (= :circuit-open (:type error)))
        (is (pos? (:retry-after-ms error)))))))

(deftest ^:integration credentials-stay-out-of-logs-test
  (reset! responder (fn [{:keys [n]}] (if (< n 3) throttled {:status 503 :body "down"})))
  (log-test/with-log
    (let [c      (client :credential-elements ["username" "password"])
          result (ports/call! c :GetLists {:Sitename "town"})]
      (is (false? (:success? result)))
      (is (seq (log-test/the-log)) "the failure was logged")
      (is (not-any? #(str/includes? (pr-str %) password) (log-test/the-log)))
      (is (not (str/includes? (pr-str result) password)))
      (testing "nor in the printed client"
        (is (not (str/includes? (pr-str c) password)))
        (is (not (str/includes? (with-out-str (pprint/pprint {:client c})) password)))))))

(deftest ^:unit invalid-request-is-refused-test
  (is (= :validation-error (get-in (ports/call! (client) :GetLists "Sitename=town") [:error :type])))
  (is (= :validation-error (get-in (ports/call! (client) :GetLists {} {:timeout-ms nil}) [:error :type]))))

(deftest ^:unit invalid-config-is-refused-test
  (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid SOAP configuration"
                                (soap/create-soap-client {:endpoint "ftp://x" :password password})))]
    (is (= :validation-error (:type (ex-data e))))
    (is (contains? (:errors (ex-data e)) :endpoint))
    (is (not (str/includes? (pr-str (ex-data e)) password)))))

(deftest ^:unit adapter-has-no-reflection-test
  (let [err (StringWriter.)]
    (binding [*err* err *warn-on-reflection* true]
      (load "/wagoe/external/shell/adapters/soap"))
    (is (not (str/includes? (str err) "Reflection warning")) (str err))))
