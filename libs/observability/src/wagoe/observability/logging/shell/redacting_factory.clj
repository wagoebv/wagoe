(ns wagoe.observability.logging.shell.redacting-factory
  "A clojure.tools.logging factory whose loggers write each throwable redacted,
   so a direct `(log/error e …)` prints no value a database driver quoted
   (BOU-634). Wraps whatever factory tools.logging found, Logback's included.
   Installed when this namespace loads, so it holds whatever starts first."
  (:require [clojure.tools.logging :as log]
            [clojure.tools.logging.impl :as impl]
            [wagoe.observability.core.sql-redaction :as redaction])
  (:import [java.util.concurrent ConcurrentHashMap]
           [java.util.function Function]))

(defn- shown
  "`t` redacted. A log call must not throw: if redaction does, a stand-in
   naming the class, not the original, which may quote a value."
  [^Throwable t]
  (try
    (redaction/redacted-exception t)
    (catch Throwable _
      (RuntimeException. (str (.getName (class t)) " (not shown: it could not be redacted)")))))

(defn- redacting-logger [logger]
  (reify impl/Logger
    (enabled? [_ level] (impl/enabled? logger level))
    (write! [_ level throwable message]
      (impl/write! logger level (some-> throwable shown) message))))

;; `wrapped` holds one wrapper per logger namespace, so it is bounded however
;; the inner factory hands out loggers: tools.logging asks for one on every log
;; statement, a disabled debug included.
(defrecord RedactingFactory [inner ^ConcurrentHashMap wrapped ^Function wrap]
  impl/LoggerFactory
  (name [_] (impl/name inner))
  (get-logger [_ logger-ns]
    (.computeIfAbsent wrapped logger-ns wrap)))

(defn- ->redacting-factory [inner]
  (->RedactingFactory inner
                      (ConcurrentHashMap.)
                      (reify Function
                        (apply [_ logger-ns] (redacting-logger (impl/get-logger inner logger-ns))))))

(defn redacting
  "`factory`, whose loggers write each throwable redacted. Idempotent, a reload
   of this namespace included."
  [factory]
  (cond
    (instance? RedactingFactory factory) factory
    ;; A reload redefines the record class: unwrap the old one, do not nest it.
    (= (.getName (class factory)) (.getName RedactingFactory)) (redacting (:inner factory))
    :else (->redacting-factory factory)))

(defn install!
  "Make every clojure.tools.logging call write its throwable redacted."
  []
  (alter-var-root #'log/*logger-factory* redacting))

(install!)
