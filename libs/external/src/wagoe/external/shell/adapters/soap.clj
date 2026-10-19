(ns wagoe.external.shell.adapters.soap
  "SOAP 1.1 client implementing ISoapClient, on java.net.http and the JDK's
   XML parser, for WCF services.

   Operations are described in config rather than read from a WSDL: WCF's
   defaults (SOAPAction namespace + contract + operation, request element named
   after the operation) cover most, and :operations sets the rest.

   Usage:
     (def ibabs (create-soap-client
                 {:endpoint       \"https://ibabs.example/api/v2/List.svc\"
                  :contract       \"IList\"
                  :data-namespace \"http://schemas.datacontract.org/2004/07/iBabs\"
                  :retry-faults   [\"Unknown fault occured\"]
                  :operations     {:GetListEntries {:timeout-ms 60000}}}))
     (ports/call! ibabs :GetLists {:Sitename \"town\"})"
  (:require [clojure.pprint :as pprint]
            [clojure.tools.logging :as log]
            [malli.core :as m]
            [malli.error :as me]
            [wagoe.external.core.soap :as soap-core]
            [wagoe.external.ports :as ports]
            [wagoe.external.schema :as schema]
            [wagoe.external.shell.resilience :as resilience]
            [wagoe.platform.core.rpc :as rpc]
            [wagoe.platform.resilience :as platform-resilience])
  (:import [java.io ByteArrayInputStream IOException]
           [java.net URI]
           [java.net.http HttpClient HttpClient$Version HttpConnectTimeoutException HttpRequest
            HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers HttpTimeoutException]
           [java.nio.channels UnresolvedAddressException]
           [java.nio.charset StandardCharsets]
           [java.time Duration]
           [java.util Base64]
           [java.util.concurrent ExecutionException TimeUnit TimeoutException]
           [javax.xml.parsers DocumentBuilderFactory]
           [org.w3c.dom Element]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; XML
;; =============================================================================

(defn- document-builder-factory ^DocumentBuilderFactory []
  ;; A response is untrusted input: no DTDs, so no external entities.
  (doto (DocumentBuilderFactory/newInstance)
    (.setNamespaceAware true)
    (.setFeature "http://apache.org/xml/features/disallow-doctype-decl" true)
    (.setXIncludeAware false)
    (.setExpandEntityReferences false)))

(defn- dom->element [^Element el]
  (let [nodes    (.getChildNodes el)
        kids     (map #(.item nodes (int %)) (range (.getLength nodes)))
        children (into [] (comp (filter #(instance? Element %)) (map dom->element)) kids)]
    {:name     (.getLocalName el)
     :ns       (.getNamespaceURI el)
     :nil?     (= "true" (.getAttributeNS el soap-core/xsi-ns "nil"))
     ;; Between child elements there is only whitespace, which nothing reads.
     :text     (if (seq children) "" (.getTextContent el))
     :children children}))

(defn- parse-xml [^bytes body]
  (-> (.newDocumentBuilder (document-builder-factory))
      (.parse (ByteArrayInputStream. body))
      .getDocumentElement
      dom->element))

;; =============================================================================
;; HTTP
;; =============================================================================

(defn- cause-kind
  "java.net.http's own exceptions first (a connect timeout is an
   HttpTimeoutException too), then the RPC client's classification."
  [^Throwable t]
  (cond
    (instance? HttpConnectTimeoutException t)                     :unconnected
    (instance? UnresolvedAddressException t)                      :unconnected
    (or (instance? HttpTimeoutException t) (instance? TimeoutException t)) :timeout
    (instance? Exception t) ({:rpc/unavailable :unconnected :rpc/timeout :timeout}
                             (rpc/classify-exception t))))

(defn- transport-failure
  "A failure without an answer, classified for the retry rule."
  [^Throwable e]
  {:kind  (or (some cause-kind (take-while some? (iterate ex-cause e))) :io)
   :cause (or (ex-message e) (str e))})

(defn- send-request
  "POST the envelope. The wait is bounded as a whole, body included, because
   a request timeout alone only covers the response headers."
  [{:keys [^HttpClient client ^URI uri ^String authorization]} {:keys [action timeout-ms]} ^String envelope]
  (let [builder (-> (HttpRequest/newBuilder uri)
                    (.timeout (Duration/ofMillis timeout-ms))
                    (.header "Content-Type" "text/xml; charset=utf-8")
                    (.header "SOAPAction" (str "\"" action "\""))
                    (.POST (HttpRequest$BodyPublishers/ofString envelope StandardCharsets/UTF_8)))
        request (.build (if authorization (.header builder "Authorization" authorization) builder))
        future  (.sendAsync client request (HttpResponse$BodyHandlers/ofByteArray))]
    (try
      (.get future (long timeout-ms) TimeUnit/MILLISECONDS)
      (catch TimeoutException e
        (.cancel future true)
        (throw e))
      (catch InterruptedException e
        (.cancel future true)
        (throw e))
      (catch ExecutionException e
        (throw (or (.getCause e) e))))))

(defn- read-response [^HttpResponse response spec]
  (soap-core/outcome (.statusCode response)
                     (try (soap-core/read-envelope (parse-xml (.body response)) spec)
                          (catch Exception e {:malformed (str "unreadable XML: " (ex-message e))}))))

(defn- attempt [client spec envelope]
  (try
    (read-response (send-request client spec envelope) spec)
    (catch InterruptedException _
      ;; Our caller gave up, not the service: neither retried nor counted.
      (.interrupt (Thread/currentThread))
      {:failure {:kind :interrupted :cause "interrupted"}})
    (catch IOException e {:failure (transport-failure e)})
    (catch TimeoutException e {:failure (transport-failure e)})))

;; =============================================================================
;; Calls
;; =============================================================================

(defn- call-operation
  [{:keys [endpoint-name resilience] :as client} spec request]
  (let [operation (:operation spec)
        envelope  (soap-core/envelope spec (soap-core/with-credentials request (:config client)))
        outcome
        (platform-resilience/call
         resilience
         {:retryable? #(boolean (some-> (:failure %) (soap-core/retryable? spec)))
          :failure?   #(boolean (some-> (:failure %) (soap-core/unavailable? spec)))
          :on-open    (fn [retry-after-ms]
                        (log/warn "SOAP circuit open; not attempting" {:endpoint endpoint-name :operation operation})
                        {:error (resilience/open-error (:circuit resilience) retry-after-ms)})
          :on-retry   (fn [{{:keys [kind cause fault]} :failure} n delay]
                        (log/warn "SOAP attempt failed, retrying"
                                  {:endpoint endpoint-name :operation operation :attempt n :delay-ms delay
                                   :kind kind :cause (or cause (:message fault))}))}
         (fn [n] (assoc (attempt client spec envelope) :attempts n)))]
    (cond
      (contains? outcome :value)
      (assoc (:value outcome) :success? true)

      (:failure outcome)
      (let [error (soap-core/soap-error (:failure outcome) spec endpoint-name (:attempts outcome))]
        (if (= :fault (:type error))
          (log/warn "SOAP fault" (select-keys error [:operation :code :message :attempts]))
          (log/error "SOAP call failed" (dissoc error :type)))
        {:success? false :error error})

      :else
      (assoc outcome :success? false))))

;; =============================================================================
;; Adapter Record
;; =============================================================================

(def ^:private explain-opts (m/explainer schema/SoapOperation))

(defrecord SoapClient [endpoint-name config]
  ports/ISoapClient
  (call! [this operation request]
    (ports/call! this operation request {}))
  (call! [this operation request opts]
    (let [opts-errors (some-> (explain-opts opts) me/humanize)]
      (cond
        (not (soap-core/valid-request? request))
        {:success? false
         :error    {:type    :validation-error
                    :message "A SOAP request is a map or [name value] pairs"}}

        opts-errors
        {:success? false
         :error    {:type :validation-error :message "Invalid SOAP call options" :errors opts-errors}}

        :else
        (call-operation this (soap-core/operation-spec config operation opts) request)))))

;; Keep the password out of logs and REPL output.
(defmethod print-method SoapClient [^SoapClient c ^java.io.Writer w]
  (.write w (str "#SoapClient" (pr-str {:endpoint-name (:endpoint-name c) :username (get-in c [:config :username])}))))

;; pprint dispatches records as maps and would bypass print-method.
(defmethod pprint/simple-dispatch SoapClient [c] (pr c))

;; =============================================================================
;; Constructor
;; =============================================================================

(defn- endpoint-name
  "The endpoint without user info or query, safe to log."
  [^URI uri]
  (str (.getScheme uri) "://" (.getHost uri) (when (pos? (.getPort uri)) (str ":" (.getPort uri))) (.getPath uri)))

(defn create-soap-client
  "Create a SOAP 1.1 client.

  Config keys:
    :endpoint             - service URL (required)
    :namespace            - operations' namespace (default http://tempuri.org/)
    :contract             - service contract, e.g. IList: SOAPAction is
                            namespace + contract + \"/\" + operation
    :data-namespace       - namespace of nested request members
    :username, :password  - sent as HTTP Basic auth (:basic-auth?, default true
                            when :username is set) and/or as the leading
                            parameters named by :credential-elements
    :timeout-ms           - per attempt, response included (default 30000)
    :connect-timeout-ms   - (default 10000)
    :max-attempts         - tries per call (default 3)
    :backoff-ms           - first retry delay, doubled per retry (default 1000)
    :retry-faults         - fault messages to retry, strings or regexes
    :operations           - {:GetListEntries {:timeout-ms .. :arrays #{..} ..}}
    :cache                - cache component; enables the circuit breaker
    :circuit-breaker      - {:failure-threshold 5 :open-ms 30000}

  Returns:
    SoapClient implementing ISoapClient"
  [{:keys [endpoint username password basic-auth? connect-timeout-ms max-attempts backoff-ms operations]
    :or   {connect-timeout-ms 10000 max-attempts 3 backoff-ms 1000}
    :as   config}]
  (when-let [errors (some-> (m/explain schema/SoapConfig config) me/humanize)]
    ;; humanize names the keys without echoing the password
    (throw (ex-info "Invalid SOAP configuration"
                    {:type :validation-error :errors errors})))
  (let [uri          (URI. endpoint)
        ep-name      (endpoint-name uri)
        basic-auth?  (if (some? basic-auth?) basic-auth? (some? username))
        longest-wait (apply max (:timeout-ms config 30000) (keep :timeout-ms (vals operations)))]
    (log/info "Creating SOAP client" {:endpoint ep-name})
    (assoc (->SoapClient ep-name (dissoc config :cache :circuit-breaker))
           :uri uri
           :authorization (when basic-auth?
                            (str "Basic " (.encodeToString (Base64/getEncoder)
                                                           (.getBytes (str username ":" password)
                                                                      StandardCharsets/UTF_8))))
           :client (-> (HttpClient/newBuilder)
                       ;; WCF speaks HTTP/1.1; no h2c upgrade attempt on http://.
                       (.version HttpClient$Version/HTTP_1_1)
                       (.connectTimeout (Duration/ofMillis connect-timeout-ms))
                       .build)
           :resilience (platform-resilience/settings
                        config
                        {:max-attempts max-attempts :delay-ms backoff-ms
                         :backoff :exponential :max-delay-ms 30000}
                        (str "soap:" ep-name)
                        (+ connect-timeout-ms longest-wait)))))
