(ns wagoe.devtools.shell.recording
  "Stateful recording management: session atom, capture middleware, file I/O."
  (:require [wagoe.devtools.core.recording :as core]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io InputStream]))

(def ^:private default-dir ".wagoe/recordings")

(defonce ^:private session-atom (atom nil))

;; Stores the handler that was live before recording started, so we can
;; restore it on stop.  Managed exclusively by user.clj's recording helper.
(defonce ^:private pre-recording-handler (atom nil))

(defn active-session [] @session-atom)
(defn reset-session! []
  (reset! session-atom nil)
  (reset! pre-recording-handler nil))

(defn store-pre-recording-handler!
  "Save the current live handler so it can be restored when recording stops."
  [handler]
  (reset! pre-recording-handler handler))

(defn restore-pre-recording-handler!
  "Return and clear the stored pre-recording handler."
  []
  (let [h @pre-recording-handler]
    (reset! pre-recording-handler nil)
    h))

(defn peek-pre-recording-handler
  "Read the stored pre-recording handler without clearing it."
  []
  @pre-recording-handler)

(defn start-recording! []
  (reset! session-atom (core/create-session))
  (println "Recording started. Requests will be captured.")
  nil)

(defn stop-recording! []
  (when @session-atom
    (swap! session-atom core/stop-session)
    (let [cnt (core/entry-count @session-atom)]
      (println (format "Recording stopped. %d request(s) captured." cnt))))
  nil)

(defn- text-content-type?
  "Return true when the content-type header indicates a text-based body
   (JSON, EDN, XML, plain text, form data). Binary payloads (images,
   PDFs, octet-stream, multipart file uploads) return false so we don't
   corrupt them by slurping to a UTF-8 string."
  [headers]
  (let [ct (or (get headers "content-type")
               (get headers "Content-Type")
               "")]
    (boolean (re-find #"(?i)text/|json|edn|xml|urlencoded" ct))))

(defn- safe-read-body
  "Read a body into a serializable form.
   Returns [serializable-body replacement-body].
   - For text InputStreams: slurps to string, builds a replacement BAIS.
   - For binary InputStreams: reads to byte array, stores as base64 string
     tagged with {:binary true :base64 ...}, returns a replacement BAIS.
   - For everything else: returns body unchanged."
  [body text?]
  (cond
    (not (instance? InputStream body))
    [body body]

    text?
    (let [s (slurp body)]
      [s (java.io.ByteArrayInputStream. (.getBytes s "UTF-8"))])

    :else
    (let [bytes (.readAllBytes ^InputStream body)
          b64   (.encodeToString (java.util.Base64/getEncoder) bytes)]
      [{:binary true :base64 b64}
       (java.io.ByteArrayInputStream. bytes)])))

(defn capture-middleware
  "Returns a Ring middleware that captures request/response pairs into the session atom.
   Normalizes :request-method to :method for consistent data model.
   Only text-based bodies (JSON, EDN, form data) are materialized for recording.
   Binary bodies (file uploads, PDF downloads) pass through untouched."
  []
  (fn [handler]
    (fn [request]
      (let [req-text?            (text-content-type? (:headers request))
            [req-body-data req-body-replacement] (safe-read-body (:body request) req-text?)
            request              (assoc request :body req-body-replacement)
            start                (System/nanoTime)
            response             (handler request)
            duration             (/ (- (System/nanoTime) start) 1e6)
            resp-text?           (text-content-type? (:headers response))
            [resp-body-data resp-body-replacement] (safe-read-body (:body response) resp-text?)
            response             (assoc response :body resp-body-replacement)]
        (when @session-atom
          (let [req-data (-> (select-keys request [:uri :headers :query-string])
                             (assoc :method (:request-method request))
                             (assoc :body req-body-data))]
            (swap! session-atom core/add-entry
                   req-data
                   (-> (select-keys response [:status :headers])
                       (assoc :body resp-body-data))
                   (long duration))))
        response))))

(defn- json-content-type?
  "Check if headers indicate a JSON content type."
  [headers]
  (let [ct (or (get headers "content-type")
               (get headers "Content-Type")
               "")]
    (boolean (re-find #"(?i)json" ct))))

(defn- decode-binary-body
  "Decode a base64-encoded binary body back to bytes."
  [body]
  (when (and (map? body) (:binary body))
    (.decode (java.util.Base64/getDecoder) ^String (:base64 body))))

(defn- prepare-replay-body
  "Prepare a recorded body for replay based on its content type.
   JSON bodies are parsed back to data (simulate will re-encode them).
   Binary bodies are decoded from base64 back to byte arrays.
   Other text bodies are returned as-is for :raw-body passthrough."
  [body headers]
  (cond
    ;; Binary body stored as {:binary true :base64 "..."}
    (and (map? body) (:binary body))
    {:binary-bytes (decode-binary-body body)}

    ;; JSON: parse back to data for simulate to re-encode
    (json-content-type? headers)
    (if (string? body)
      (try (json/parse-string body true)
           (catch Exception _ body))
      body)

    ;; Other text: pass through as-is
    :else body))

(defn replay-entry!
  "Replay a recorded entry. simulate-fn should be the repl/simulate-request function.
   For non-JSON content types, the raw body is passed through without re-encoding."
  [idx simulate-fn & [overrides]]
  (if-let [session @session-atom]
    (if-let [entry (core/get-entry session idx)]
      (let [request (if overrides
                      (core/merge-request-modifications (:request entry) overrides)
                      (:request entry))
            headers (:headers request)
            json?   (json-content-type? headers)
            body    (prepare-replay-body (:body request) headers)
            binary-bytes (when (map? body) (:binary-bytes body))]
        (cond
          ;; Binary body: pass decoded bytes directly
          binary-bytes
          (simulate-fn (:method request) (:uri request)
                       (cond-> {:raw-bytes binary-bytes}
                         headers                   (assoc :headers headers)
                         (:query-string request)   (assoc :query-string (:query-string request))))
          ;; JSON: pass as :body, simulate will JSON-encode it
          json?
          (simulate-fn (:method request) (:uri request)
                       (cond-> {}
                         body                      (assoc :body body)
                         headers                   (assoc :headers headers)
                         (:query-string request)   (assoc :query-string (:query-string request))))
          ;; Non-JSON text: pass as :raw-body to avoid re-encoding
          :else
          (simulate-fn (:method request) (:uri request)
                       (cond-> {}
                         body                      (assoc :raw-body body)
                         headers                   (assoc :headers headers)
                         (:query-string request)   (assoc :query-string (:query-string request))))))
      (println (format "Entry %d not found. Session has %d entries (0 to %d)."
                       idx (core/entry-count session) (dec (core/entry-count session)))))
    (println "No active recording session. Use (recording :start) or (recording :load \"name\").")))

(defn- validate-recording-path!
  "Assert that the resolved file path stays within the recordings directory.
   Prevents path traversal via names like '../../../etc/passwd'."
  [file dir]
  (let [base-dir (.getCanonicalPath (io/file dir))
        resolved (.getCanonicalPath file)]
    (when-not (.startsWith resolved base-dir)
      (throw (ex-info "Invalid recording name: path traversal detected"
                      {:resolved resolved :base-dir base-dir})))))

(defn save-session!
  ([name] (save-session! name default-dir))
  ([name dir]
   (if-let [session @session-atom]
     (let [file (io/file dir (str name ".edn"))]
       (validate-recording-path! file dir)
       (io/make-parents file)
       (spit file (core/serialize-session session))
       (println (format "Recording saved to %s" (.getPath file))))
     (println "No active recording session."))))

(defn load-session!
  ([name] (load-session! name default-dir))
  ([name dir]
   (let [file (io/file dir (str name ".edn"))]
     (validate-recording-path! file dir)
     (if (.exists file)
       (do
         (reset! session-atom (core/deserialize-session (slurp file)))
         (println (format "Loaded recording '%s' (%d entries)."
                          name (core/entry-count @session-atom)))
         nil)
       (let [available (when (.exists (io/file dir))
                         (->> (.listFiles (io/file dir))
                              (filter #(.endsWith (.getName %) ".edn"))
                              (map #(subs (.getName %) 0 (- (count (.getName %)) 4)))))]
         (if (seq available)
           (println (format "Recording '%s' not found. Available: %s"
                            name (str/join ", " available)))
           (println (format "Recording '%s' not found. No saved recordings in %s."
                            name dir)))
         {:error :not-found})))))

(defn list-entries []
  (if-let [session @session-atom]
    (println (core/format-entry-table session))
    (println "No active recording session.")))

(defn diff-entries [idx-a idx-b]
  (if-let [session @session-atom]
    (if-let [diff (core/diff-entries session idx-a idx-b)]
      diff
      (println "One or both entry indices are out of bounds."))
    (println "No active recording session.")))
