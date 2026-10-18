(ns wagoe.external.shell.adapters.ftps
  "FTPS file source implementing IFileSource, on Apache Commons Net.

   Explicit TLS (AUTH TLS, then PBSZ 0 and PROT P so the data channel is
   encrypted too), passive mode, binary transfers. One connection per call. A
   transient failure is retried with backoff; a 5xx reply is not.

   Usage:
     (def source (create-ftps-source {:host \"ftp.example.org\"
                                      :username \"import\"
                                      :password \"secret\"}))
     (ports/list-files! source \"/outbox\" {:match #\".*\\.csv\"})"
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.tools.logging :as log]
            [malli.core :as m]
            [malli.error :as me]
            [wagoe.external.core.ftps :as ftps-core]
            [wagoe.external.ports :as ports]
            [wagoe.external.schema :as schema]
            [wagoe.platform.core.circuit-breaker :as cb]
            [wagoe.platform.core.retry :as retry]
            [wagoe.platform.resilience :as resilience])
  (:import [java.io ByteArrayOutputStream]
           [java.security KeyStore]
           [java.security.cert CertificateException]
           [java.time Duration]
           [java.util Calendar]
           [javax.net.ssl SSLException TrustManager]
           [org.apache.commons.net.ftp FTP FTPFile FTPReply FTPSClient]
           [org.apache.commons.net.util TrustManagerUtils]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; Connection
;; =============================================================================

(defn- reply-failure!
  "Throw the server's last reply as a typed failure."
  [^FTPSClient client step]
  (throw (ex-info (str step " refused: " (.trim (str (.getReplyString client))))
                  {:type :external-error :reply-code (.getReplyCode client)})))

(defn- check-reply! [^FTPSClient client step]
  (when-not (FTPReply/isPositiveCompletion (.getReplyCode client))
    (reply-failure! client step)))

(defn- tls-step!
  "Run (f). Commons Net reports a refused AUTH, PBSZ or PROT as a bare
   SSLException; rethrow that with the reply code so a 5xx is not retried. A
   failed handshake follows a positive reply and is rethrown as is."
  [^FTPSClient client step f]
  (try
    (f)
    (catch SSLException e
      (if (FTPReply/isPositiveCompletion (.getReplyCode client))
        (throw e)
        (reply-failure! client step)))))

(defn- connect!
  ^FTPSClient [{:keys [host port username password timeout-ms ^TrustManager trust-manager]}]
  (let [client (FTPSClient. "TLS" false)]
    (doto client
      (.setTrustManager trust-manager)
      (.setEndpointCheckingEnabled true)
      (.setConnectTimeout (int timeout-ms))
      (.setDefaultTimeout (int timeout-ms))
      (.setDataTimeout (Duration/ofMillis timeout-ms)))
    (try
      (tls-step! client "AUTH TLS" #(.connect client ^String host (int port)))
      (check-reply! client "Connection")
      (when-not (.login client ^String username ^String password)
        (reply-failure! client "Login"))
      (tls-step! client "PBSZ 0" #(.execPBSZ client 0))
      (tls-step! client "PROT P" #(.execPROT client "P"))
      (.enterLocalPassiveMode client)
      (when-not (.setFileType client FTP/BINARY_FILE_TYPE)
        (reply-failure! client "Binary mode"))
      client
      (catch Exception e
        (try (.disconnect client) (catch Exception _))
        (throw e)))))

(defn- close-client! [^FTPSClient client]
  (try (.logout client) (catch Exception _))
  (try (.disconnect client) (catch Exception _)))

;; =============================================================================
;; Retries
;; =============================================================================

(defn- certificate-failure? [^Throwable e]
  (boolean (some #(instance? CertificateException %)
                 (take-while some? (iterate #(.getCause ^Throwable %) e)))))

(defn- failure [^Throwable e]
  {:reply-code   (:reply-code (ex-data e))
   :certificate? (certificate-failure? e)
   :cause        (if (ex-data e) (ex-message e) (str e))})

(defn- attempt [adapter f]
  (try
    {:value (let [client (connect! adapter)]
              (try (f client) (finally (close-client! client))))}
    (catch Exception e
      {:failure (failure e)})))

(defn- transient? [outcome]
  (boolean (some-> (:failure outcome) ftps-core/retryable?)))

(defn- with-ftps
  "Run (f client) on a fresh connection, retrying transient failures, behind
   the circuit breaker when the source has a :cache. Returns
   (assoc (f client) :success? true), or {:success? false :error ...}."
  [{:keys [host policy cache breaker circuit] :as adapter} operation f]
  (let [outcome
        (resilience/with-breaker
          cache breaker circuit
          transient?
          (fn [retry-after-ms] {:open retry-after-ms})
          (fn []
            (resilience/with-retry
              policy
              transient?
              (fn [n] (assoc (attempt adapter f) :attempts n))
              {:on-retry (fn [{{:keys [cause]} :failure} n delay]
                           (log/warn "FTPS attempt failed, retrying"
                                     {:host host :operation operation :attempt n
                                      :delay-ms delay :cause cause}))})))]
    (cond
      (contains? outcome :value)
      (assoc (:value outcome) :success? true)

      (:failure outcome)
      (let [error (ftps-core/ftps-error (assoc (:failure outcome)
                                               :host host :operation operation
                                               :attempts (:attempts outcome)))]
        (log/error "FTPS operation failed" (dissoc error :type))
        {:success? false :error error})

      :else
      (do (log/warn "FTPS circuit open; not attempting" {:host host :operation operation})
          {:success? false
           :error    (cond-> {:type    :circuit-open
                              :message (str "Circuit open for " circuit "; not attempting the call")
                              :host    host}
                       (:open outcome) (assoc :retry-after-ms (:open outcome)))}))))

;; =============================================================================
;; Operations
;; =============================================================================

(defn- ftp-file->remote-file [dir ^FTPFile f]
  (let [^Calendar ts (.getTimestamp f)]
    (cond-> {:name (.getName f)
             :path (ftps-core/remote-path dir (.getName f))
             :size (max 0 (.getSize f))}
      ts (assoc :modified-at (.toInstant ts)))))

(defn- list-dir [^FTPSClient client dir]
  (let [entries (.listFiles client ^String dir)]
    (check-reply! client (str "LIST " dir))
    (into [] (comp (filter #(.isFile ^FTPFile %))
                   (map #(ftp-file->remote-file dir %)))
          entries)))

(defn- retrieve [^FTPSClient client ^String path]
  (let [out (ByteArrayOutputStream.)]
    (when-not (.retrieveFile client path out)
      (reply-failure! client (str "RETR " path)))
    (.toByteArray out)))

;; =============================================================================
;; Adapter Record
;; =============================================================================

(defrecord FtpsFileSource [host port username password timeout-ms policy trust-manager cache breaker circuit]
  ports/IFileSource
  (list-files! [this dir]
    (ports/list-files! this dir {}))
  (list-files! [this dir opts]
    (with-ftps this :list
      (fn [client] {:files (ftps-core/select-files (list-dir client dir) opts)})))

  (fetch-file! [this path]
    (with-ftps this :fetch
      (fn [client]
        {:content (retrieve client path)}))))

;; Keep the password out of logs and REPL output.
(defmethod print-method FtpsFileSource [^FtpsFileSource s ^java.io.Writer w]
  (.write w (str "#FtpsFileSource" (pr-str (select-keys s [:host :port :username])))))

;; pprint dispatches records as maps and would bypass print-method.
(defmethod pprint/simple-dispatch FtpsFileSource [s] (pr s))

;; =============================================================================
;; Constructor
;; =============================================================================

(defn- trust-manager-for
  "Chain-validating trust manager over the given keystore, or the JVM's cacerts
   when there is none. FTPSClient's own default only checks expiry dates."
  ^TrustManager [{:keys [trust-store trust-store-password trust-store-type]}]
  (TrustManagerUtils/getDefaultTrustManager
   (when trust-store
     (try
       (with-open [in (io/input-stream trust-store)]
         (doto (KeyStore/getInstance ^String (or trust-store-type "PKCS12"))
           (.load in (some-> ^String trust-store-password .toCharArray))))
       (catch Exception e
         (throw (ex-info (str "Cannot load FTPS trust store " trust-store ": " (ex-message e))
                         {:type :validation-error :trust-store trust-store}
                         e)))))))

(defn create-ftps-source
  "Create an FTPS file source.

  Config keys:
    :host                 - server hostname (required); the certificate must name it
    :port                 - control port (default 21)
    :username, :password  - login (required)
    :timeout-ms           - connect, control and data read timeout (default 30000)
    :max-attempts         - tries per operation (default 3)
    :backoff-ms           - first retry delay, doubled per retry (default 1000)
    :trust-store          - keystore file to trust instead of the JVM default
    :trust-store-password, :trust-store-type (default PKCS12)
    :cache                - cache component; enables the circuit breaker
    :circuit-breaker      - {:failure-threshold 5 :open-ms 30000}

  Returns:
    FtpsFileSource implementing IFileSource"
  [{:keys [host port username password timeout-ms max-attempts backoff-ms]
    :or   {port 21 timeout-ms 30000 max-attempts 3 backoff-ms 1000}
    :as   config}]
  (when-not (schema/valid-ftps-config? config)
    (throw (ex-info "Invalid FTPS configuration"
                    {:type     :validation-error
                     :host     host
                     :username username
                     ;; humanize names the keys without echoing the password
                     :errors   (me/humanize (m/explain schema/FtpsConfig config))})))
  (log/info "Creating FTPS file source" {:host host :port port})
  (let [policy {:max-attempts max-attempts :delay-ms backoff-ms
                :backoff :exponential :max-delay-ms 30000}
        ;; Each attempt can wait on connect, control and data reads in turn.
        cb-cfg (merge cb/default-config
                      {:probe-lease-ms (retry/budget-ms policy (* 3 timeout-ms))}
                      (:circuit-breaker config))]
    (when-let [problem (and (:circuit-breaker config) (cb/config-problem cb-cfg))]
      (throw (ex-info problem {:type :validation-error :host host})))
    (->FtpsFileSource host port username password timeout-ms policy
                      (trust-manager-for config)
                      (:cache config) cb-cfg (str "ftps:" host ":" port))))
