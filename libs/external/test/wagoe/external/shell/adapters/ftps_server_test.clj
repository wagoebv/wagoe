(ns wagoe.external.shell.adapters.ftps-server-test
  "FtpsFileSource against a real FTPS server (Apache MINA FtpServer, in-process,
   explicit TLS with a keystore generated per run)."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [wagoe.cache.shell.adapters.in-memory :as cache-mem]
            [wagoe.external.ports :as ports]
            [wagoe.external.schema :as schema]
            [wagoe.external.shell.adapters.ftps :as ftps])
  (:import [java.io File StringWriter]
           [java.net ServerSocket]
           [java.nio.file Files]
           [java.security KeyStore]
           [java.nio.file.attribute FileAttribute]
           [org.apache.ftpserver FtpServer FtpServerFactory]
           [org.apache.ftpserver.ftplet DefaultFtpReply DefaultFtplet FtpRequest FtpSession FtpletResult]
           [org.apache.ftpserver.listener ListenerFactory]
           [org.apache.ftpserver.ssl SslConfigurationFactory]
           [org.apache.ftpserver.usermanager ClearTextPasswordEncryptor PropertiesUserManagerFactory]
           [org.apache.ftpserver.usermanager.impl BaseUser WritePermission]))

(def ^:private user "import")
(def ^:private password "secret")
(def ^:private store-password "changeit")
(def ^:private binary (byte-array (map unchecked-byte (range -128 128))))

(def ^:private ^:dynamic *port* nil)
(def ^:private ^:dynamic *trust-store* nil)
(def ^:private commands (atom []))
(def ^:private refuse-prot? (atom false))

(defn- temp-dir ^File [prefix]
  (.toFile (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- keytool! [& args]
  (let [bin (str (System/getProperty "java.home") "/bin/keytool")
        {:keys [exit err]} (apply sh/sh bin args)]
    (when-not (zero? exit)
      (throw (ex-info (str "keytool failed: " err) {:type :internal-error})))))

(defn- generate-stores!
  "A server keystore with a self-signed certificate for localhost, and a trust
   store holding only that certificate."
  [^File dir]
  (let [ks   (io/file dir "server.p12")
        ts   (io/file dir "trust.p12")]
    (keytool! "-genkeypair" "-alias" "ftps" "-keyalg" "RSA" "-keysize" "2048"
              "-dname" "CN=localhost" "-ext" "SAN=dns:localhost" "-validity" "2"
              "-storetype" "PKCS12" "-keystore" (str ks) "-storepass" store-password)
    (let [pass   (.toCharArray ^String store-password)
          server (with-open [in (io/input-stream ks)]
                   (doto (KeyStore/getInstance "PKCS12") (.load in pass)))
          trust  (doto (KeyStore/getInstance "PKCS12")
                   (.load nil nil)
                   (.setCertificateEntry "ftps" (.getCertificate server "ftps")))]
      (with-open [out (io/output-stream ts)]
        (.store trust out pass)))
    {:keystore ks :trust-store ts}))

(defn- recording-ftplet []
  (proxy [DefaultFtplet] []
    (beforeCommand [^FtpSession session ^FtpRequest request]
      (let [line (str/trim (.getRequestLine request))]
        (swap! commands conj line)
        (if (and @refuse-prot? (= "PROT P" line))
          (do (.write session (DefaultFtpReply. 534 "PROT P refused"))
              FtpletResult/SKIP)
          FtpletResult/DEFAULT)))))

(defn- start-server! ^FtpServer [^File home ^File keystore]
  (let [ssl      (doto (SslConfigurationFactory.)
                   (.setKeystoreFile keystore)
                   (.setKeystorePassword store-password)
                   (.setKeystoreType "PKCS12"))
        listener (.createListener (doto (ListenerFactory.)
                                    (.setPort 0)
                                    (.setImplicitSsl false)
                                    (.setSslConfiguration (.createSslConfiguration ssl))))
        props    (doto (io/file (.getParentFile home) "users.properties") .createNewFile)
        users    (.createUserManager (doto (PropertiesUserManagerFactory.)
                                       (.setFile props)
                                       (.setPasswordEncryptor (ClearTextPasswordEncryptor.))))
        factory  (FtpServerFactory.)]
    (.save users (doto (BaseUser.)
                   (.setName user)
                   (.setPassword password)
                   (.setHomeDirectory (str home))
                   (.setAuthorities (java.util.ArrayList. [(WritePermission.)]))))
    (doto factory
      (.addListener "default" listener)
      (.setUserManager users)
      (.setFtplets (java.util.HashMap. {"recorder" (recording-ftplet)})))
    (let [server (.createServer factory)]
      (.start server)
      {:server server :port (.getPort listener)})))

(defn- with-ftps-server [f]
  (let [dir  (temp-dir "ftps-test")
        home (doto (io/file dir "home") .mkdirs)
        {:keys [keystore trust-store]} (generate-stores! dir)]
    (io/make-parents (io/file home "outbox" "x"))
    (spit (io/file home "outbox" "contacts-2.csv") "id,name\n2,Bea\n")
    (spit (io/file home "outbox" "contacts-1.csv") "id,name\n1,Ann\n")
    (spit (io/file home "outbox" "notes.txt") "not an import")
    (io/copy binary (io/file home "outbox" "events.bin"))
    (.mkdirs (io/file home "outbox" "archive"))
    (let [{:keys [^FtpServer server port]} (start-server! home keystore)]
      (try
        (binding [*port* port *trust-store* (str trust-store)]
          (f))
        (finally (.stop server))))))

(use-fixtures :once with-ftps-server)

(defn- source [& {:as overrides}]
  (ftps/create-ftps-source (merge {:host "localhost" :port *port*
                                   :username user :password password
                                   :timeout-ms 5000 :max-attempts 3 :backoff-ms 1
                                   :trust-store *trust-store* :trust-store-password store-password}
                                  overrides)))

(deftest ^:integration list-files-test
  (testing "regular files only, sorted by name, with path and size"
    (let [{:keys [success? files]} (ports/list-files! (source) "/outbox")]
      (is success?)
      (is (= ["contacts-1.csv" "contacts-2.csv" "events.bin" "notes.txt"] (map :name files)))
      (is (= "/outbox/contacts-1.csv" (:path (first files))))
      (is (= 256 (:size (first (filter #(= "events.bin" (:name %)) files)))))
      (is (every? #(m/validate schema/RemoteFile %) files))))

  (testing "by regex, by list, and skipping processed files"
    (is (= ["contacts-1.csv" "contacts-2.csv"]
           (map :name (:files (ports/list-files! (source) "/outbox" {:match #"contacts-.*\.csv"})))))
    (is (= ["notes.txt"]
           (map :name (:files (ports/list-files! (source) "/outbox" {:match ["notes.txt" "gone.txt"]})))))
    (is (= ["contacts-2.csv"]
           (map :name (:files (ports/list-files! (source) "/outbox"
                                                 {:match #"contacts-.*\.csv" :skip #{"contacts-1.csv"}})))))))

(deftest ^:integration fetch-file-test
  (testing "bytes arrive unchanged: binary mode"
    (let [{:keys [success? content]} (ports/fetch-file! (source) "/outbox/events.bin")]
      (is success?)
      (is (= (seq binary) (seq content)))))

  (testing "text"
    (is (= "id,name\n1,Ann\n" (String. ^bytes (:content (ports/fetch-file! (source) "/outbox/contacts-1.csv")) "UTF-8")))))

(deftest ^:integration control-and-data-channels-are-encrypted-test
  (reset! commands [])
  (ports/list-files! (source) "/outbox")
  ;; AUTH TLS happens before the Ftplet sees commands; PROT P is what moves the
  ;; listing onto an encrypted data channel.
  (is (some #{"PBSZ 0"} @commands))
  (is (some #{"PROT P"} @commands))
  (is (some #(str/starts-with? % "PASS") @commands)))

(deftest ^:integration permanent-failures-are-not-retried-test
  (testing "rejected credentials: a 5xx, one attempt"
    (let [{:keys [success? error]} (ports/list-files! (source :password "wrong") "/outbox")]
      (is (false? success?))
      (is (= :external-error (:type error)))
      (is (= 1 (:attempts error)))
      (is (= 530 (:reply-code error)))
      (is (str/includes? (:message error) "localhost"))))

  (testing "a missing file"
    (let [{:keys [success? error]} (ports/fetch-file! (source) "/outbox/missing.csv")]
      (is (false? success?))
      (is (= 1 (:attempts error)))
      (is (= 550 (:reply-code error)))))

  (testing "a refused PROT P keeps its reply code"
    (reset! refuse-prot? true)
    (try
      (let [{:keys [success? error]} (ports/list-files! (source) "/outbox")]
        (is (false? success?))
        (is (= 1 (:attempts error)))
        (is (= 534 (:reply-code error))))
      (finally (reset! refuse-prot? false))))

  (testing "a certificate the JVM does not trust"
    (let [{:keys [success? error]} (ports/list-files! (source :trust-store nil) "/outbox")]
      (is (false? success?))
      (is (= 1 (:attempts error)))))

  (testing "a certificate that does not name the host"
    (let [{:keys [success? error]} (ports/list-files! (source :host "127.0.0.1") "/outbox")]
      (is (false? success?))
      (is (= 1 (:attempts error))))))

(deftest ^:integration transient-failures-are-retried-test
  (testing "refused connection: every attempt is used"
    (let [port (with-open [s (ServerSocket. 0)] (.getLocalPort s))
          {:keys [success? error]} (ports/list-files! (source :port port) "/outbox")]
      (is (false? success?))
      (is (= 3 (:attempts error)))
      (is (str/includes? (:message error) "3 attempts"))))

  (testing "a server that accepts and never answers does not hang the caller"
    (with-open [silent (ServerSocket. 0)]
      (let [started (System/nanoTime)
            {:keys [success? error]} (ports/list-files! (source :port (.getLocalPort silent)
                                                                :timeout-ms 200 :max-attempts 2)
                                                        "/outbox")]
        (is (false? success?))
        (is (= 2 (:attempts error)))
        (is (< (/ (- (System/nanoTime) started) 1e6) 5000))))))

(deftest ^:integration an-unreachable-server-trips-the-breaker-test
  (let [port (with-open [s (ServerSocket. 0)] (.getLocalPort s))
        src  (source :port port :max-attempts 1
                     :cache (cache-mem/create-in-memory-cache {})
                     :circuit-breaker {:failure-threshold 1 :open-ms 60000})]
    (is (= :external-error (get-in (ports/list-files! src "/outbox") [:error :type])))
    (testing "the next call is declined without connecting"
      (let [{:keys [success? error]} (ports/list-files! src "/outbox")]
        (is (false? success?))
        (is (= :circuit-open (:type error)))
        (is (pos? (:retry-after-ms error)))))))

(deftest ^:unit adapter-has-no-reflection-test
  (let [err (StringWriter.)]
    (binding [*err* err *warn-on-reflection* true]
      (load "/wagoe/external/shell/adapters/ftps"))
    (is (not (str/includes? (str err) "Reflection warning")) (str err))))

(deftest ^:unit invalid-config-is-refused-test
  (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid FTPS configuration"
                                (ftps/create-ftps-source {:host "localhost" :username "x"})))]
    (is (contains? (:errors (ex-data e)) :password))))

(deftest ^:unit unreadable-trust-store-is-typed-test
  (let [e (is (thrown? clojure.lang.ExceptionInfo
                       (source :trust-store "/nonexistent/trust.p12")))]
    (is (= :validation-error (:type (ex-data e))))))

(deftest ^:unit printing-hides-the-password-test
  (is (not (str/includes? (pr-str (source)) password)))
  (is (not (str/includes? (with-out-str (pprint/pprint {:source (source)})) password))))
