(ns wagoe.observability.logging.shell.redacting-factory
  "A clojure.tools.logging factory whose loggers write each throwable redacted,
   so a direct `(log/error e …)` prints no value a database driver quoted
   (BOU-634). Wraps whatever factory tools.logging found, Logback's included."
  (:require [clojure.tools.logging :as log]
            [clojure.tools.logging.impl :as impl]
            [wagoe.observability.core.sql-redaction :as redaction]))

(defrecord RedactingFactory [inner]
  impl/LoggerFactory
  (name [_] (impl/name inner))
  (get-logger [_ logger-ns]
    (let [logger (impl/get-logger inner logger-ns)]
      (reify impl/Logger
        (enabled? [_ level] (impl/enabled? logger level))
        (write! [_ level throwable message]
          (impl/write! logger level (some-> throwable redaction/redacted-exception) message))))))

(defn redacting
  "`factory`, whose loggers write each throwable redacted. Idempotent."
  [factory]
  (if (instance? RedactingFactory factory) factory (->RedactingFactory factory)))

(defn install!
  "Make every clojure.tools.logging call write its throwable redacted."
  []
  (alter-var-root #'log/*logger-factory* redacting))
