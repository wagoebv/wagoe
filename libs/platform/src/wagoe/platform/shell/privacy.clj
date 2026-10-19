(ns wagoe.platform.shell.privacy
  "Data export and retention over what modules contribute: `:data-export`
   exporters and `:retention` policies (`wagoe.platform.ports.privacy`).

   Retention is off until `:wagoe/privacy :retention` names a policy, and then
   runs as the recurring job `retention-job-type` on `:wagoe/jobs`:

     :wagoe/privacy {:retention          {:user/audit-log {:retain \"P2Y\"}}
                     :retention-schedule {:cron \"0 3 * * *\" :zone \"UTC\"}}"
  (:require [clojure.tools.logging :as log]
            [ring.core.protocols :as ring-protocols]
            [wagoe.platform.core.privacy :as privacy]
            [wagoe.platform.ports.privacy :as ports])
  (:import [java.io OutputStream]
           [java.nio.charset StandardCharsets]
           [java.time Instant]
           [java.util.zip ZipEntry ZipOutputStream]))

(def retention-job-type :wagoe/retention)

(def default-retention-schedule {:cron "0 3 * * *"})

(defn- export-files
  "The files `exporters` hold behind their sections of `doc`, each with its
   `:entry` in the zip."
  [exporters doc]
  (for [[id exporter] exporters
        :when (satisfies? ports/IFileExporter exporter)
        :let  [section (get-in doc [:sections id])]
        :when (some? section)
        file  (ports/export-files exporter (:subject doc) section)
        :let  [entry (privacy/archive-entry id (:path file))]
        :when entry]
    (assoc file :entry entry)))

(defn- write-archive!
  "Write `doc` and `files` to `out` as a zip: the files, then
   data-export.json, which lists a file that was gone under :missing-files."
  [doc files ^OutputStream out]
  (with-open [zip (ZipOutputStream. out)]
    (let [missing (reduce (fn [missing {:keys [entry content]}]
                            (if-let [^bytes bytes (content)]
                              (do (.putNextEntry zip (ZipEntry. ^String entry))
                                  (.write zip bytes)
                                  (.closeEntry zip)
                                  missing)
                              (conj missing entry)))
                          []
                          files)]
      (.putNextEntry zip (ZipEntry. "data-export.json"))
      (.write zip (.getBytes ^String (privacy/export-json (cond-> doc (seq missing) (assoc :missing-files missing)))
                             StandardCharsets/UTF_8))
      (.closeEntry zip))))

(defn- archive-response
  "A zip download, written as it streams: the files are read from storage
   one at a time rather than held in memory together."
  [doc files]
  {:status  200
   :headers {"Content-Type"        "application/zip"
             "Content-Disposition" "attachment; filename=\"data-export.zip\""
             "Cache-Control"       "no-store"}
   :body    (reify ring-protocols/StreamableResponseBody
              (write-body-to-stream [_ _ out]
                (try
                  (write-archive! doc files out)
                  (catch Exception e
                    ;; The status has gone; a cut-off zip does not open, so
                    ;; it cannot pass for a complete one.
                    (log/error e "Data export zip failed" {:subject (:subject doc)})
                    (throw e)))))})

(defn create-data-export
  "An `IDataExport` over `exporters`, {exporter-id IDataExporter}."
  [exporters]
  (reify ports/IDataExport
    (export-subject [_ subject]
      (when-let [{:keys [error]} (privacy/subject-error subject)]
        (throw (ex-info (:message error) (assoc error :subject subject))))
      (privacy/export-document
       subject
       (into {} (map (fn [[id exporter]] [id (ports/export-data exporter subject)])) exporters)
       (Instant/now)))

    (export-response [_ doc request]
      (if (privacy/zip-requested? request)
        (archive-response doc (export-files exporters doc))
        (privacy/export-response doc)))))

(defn retention-plan!
  "The policies `configured` names, each with its period. Throws
   :configuration-error when one is unknown or its period malformed."
  [configured contributed]
  (let [{:keys [plan error]} (privacy/retention-plan configured (keys contributed))]
    (when error
      (throw (ex-info (:message error) error)))
    plan))

(defn assert-runnable!
  "Throws :configuration-error when a policy in `plan` is not an
   `IRetentionPolicy`: its module could not provide it, and every run would
   fail."
  [plan policies]
  (when-let [broken (seq (sort (remove #(satisfies? ports/IRetentionPolicy (get policies %)) (keys plan))))]
    (let [broken (vec broken)]
      (throw (ex-info (str "Retention policies " broken " are configured, but their modules cannot run them;"
                           " the log says why.")
                      {:type :configuration-error :policies broken})))))

(defn run-retention!
  "Run each policy in `plan` against `policies` at `now`. One failing policy
   does not stop the rest. Returns {:deleted {id n} :failed {id message}}."
  [plan policies ^Instant now]
  (reduce-kv
   (fn [acc id retain]
     (let [cutoff (privacy/cutoff retain now)]
       (try
         (let [n (ports/purge-before! (get policies id) cutoff)]
           (log/info "Retention: purged" {:policy id :cutoff cutoff :deleted n})
           (assoc-in acc [:deleted id] n))
         (catch Exception e
           (log/error e "Retention policy failed" {:policy id :cutoff cutoff})
           (assoc-in acc [:failed id] (ex-message e))))))
   {:deleted {} :failed {}}
   plan))

(defn retention-job-handler
  "The job handler for `retention-job-type`. Fails the job when a policy
   failed, so it is retried; deleting what is already gone is harmless."
  [plan policies]
  (fn [_args]
    (let [{:keys [failed] :as result} (run-retention! plan policies (Instant/now))]
      (if (seq failed)
        {:success? false
         :result   result
         :error    {:type :retention-failed
                    :message (str "Retention failed for " (vec (sort (keys failed))))}}
        {:success? true :result result}))))
