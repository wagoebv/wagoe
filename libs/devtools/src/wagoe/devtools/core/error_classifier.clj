(ns wagoe.devtools.core.error-classifier
  "Classify exceptions into BND-xxx error codes.
   Pure functions — catalog data loaded once at namespace init via wagoe.devtools.error-codes.

   Classification strategy (ordered, first match wins):
   1. ex-data with :boundary/error-code — direct BND code
   2. ex-data pattern matching — infer from :type, :schema, :malli/error
   3. Message pattern — regex on .getMessage()
   4. Exception type — SQLException, ConnectException, etc.
   5. Unclassified — nil code"
  (:require [wagoe.devtools.error-codes :as codes]))

(defn- root-cause
  "Walk the cause chain to find the root cause."
  [^Throwable ex]
  (if-let [cause (.getCause ex)]
    (recur cause)
    ex))

(defn- classify-explicit-code
  "Check if exception has :boundary/error-code in ex-data."
  [ex]
  (when-let [code (get (ex-data ex) :boundary/error-code)]
    (when-let [error-def (codes/lookup code)]
      {:code     code
       :category (:category error-def)
       :data     (dissoc (ex-data ex) :boundary/error-code)
       :source   :ex-data})))

(defn- classify-ex-data-pattern
  "Infer error code from ex-data keys and values."
  [ex]
  (let [data (ex-data ex)]
    (when data
      (cond
        (or (contains? data :malli/error)
            (= :malli.core/invalid (:type data)))
        {:code "BND-201" :category :validation :data data :source :ex-data-pattern}

        (= :db/error (:type data))
        {:code "BND-303" :category :persistence :data data :source :ex-data-pattern}

        (= :auth/required (:type data))
        {:code "BND-401" :category :auth :data data :source :ex-data-pattern}

        (= :auth/forbidden (:type data))
        {:code "BND-402" :category :auth :data data :source :ex-data-pattern}

        (and (= :configuration-error (:type data))
             (= "JWT_SECRET" (:required-env-var data)))
        {:code "BND-103" :category :config :data data :source :ex-data-pattern}

        (and (= :configuration-error (:type data))
             (:required-env-var data))
        {:code "BND-101" :category :config :data data :source :ex-data-pattern}

        :else nil))))

(def ^:private message-patterns
  "Ordered list of [regex code category] for message-based classification."
  [[#"(?i)relation .* does not exist"       "BND-301" :persistence]
   [#"(?i)table .* not found"               "BND-301" :persistence]
   [#"(?i)column .* does not exist"         "BND-301" :persistence]
   [#"(?i)no such table"                    "BND-301" :persistence]
   [#"(?i)pool.*exhaust"                    "BND-302" :persistence]
   [#"(?i)connection.*refused"              "BND-303" :persistence]
   [#"(?i)authentication.*required"         "BND-401" :auth]
   [#"(?i)permission.*denied"              "BND-402" :auth]])

(defn- classify-message-pattern
  "Classify by regex matching on exception message."
  [ex]
  (when-let [msg (.getMessage ^Throwable ex)]
    (some (fn [[pattern code category]]
            (when (re-find pattern msg)
              {:code code :category category :data {} :source :message-pattern}))
          message-patterns)))

(defn- classify-exception-type
  "Classify by Java exception class."
  [ex]
  (cond
    (instance? java.sql.SQLException ex)
    {:code "BND-303" :category :persistence :data {} :source :exception-type}

    (instance? java.net.ConnectException ex)
    {:code "BND-303" :category :persistence :data {} :source :exception-type}

    :else nil))

(defn classify
  "Classify an exception into a BND-xxx error code.

   Walks the cause chain: if the outermost exception has :boundary/error-code
   in ex-data (strategy 1), that takes precedence. Otherwise, classifies the
   root cause.

   Returns a map with :code, :category, :exception, :data, :source
   or a map with :code nil for unclassified errors."
  [^Throwable exception]
  (when exception
    (let [explicit (classify-explicit-code exception)
          root     (root-cause exception)
          result   (or explicit
                       (classify-ex-data-pattern root)
                       (classify-message-pattern root)
                       (classify-exception-type root)
                       {:code nil :category nil :data {} :source :unclassified})]
      (assoc result :exception exception))))
