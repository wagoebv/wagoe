(ns wagoe.platform.shell.privacy
  "Data export and retention over what modules contribute: `:data-export`
   exporters and `:retention` policies (`wagoe.platform.ports.privacy`).

   Retention is off until `:wagoe/privacy :retention` names a policy, and then
   runs as the recurring job `retention-job-type` on `:wagoe/jobs`:

     :wagoe/privacy {:retention          {:user/audit-log {:retain \"P2Y\"}}
                     :retention-schedule {:cron \"0 3 * * *\" :zone \"UTC\"}}"
  (:require [clojure.tools.logging :as log]
            [wagoe.platform.core.privacy :as privacy]
            [wagoe.platform.ports.privacy :as ports])
  (:import [java.time Instant]))

(def retention-job-type :wagoe/retention)

(def default-retention-schedule {:cron "0 3 * * *"})

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
       (Instant/now)))))

(defn retention-plan!
  "The policies `configured` names, each with its period. Throws
   :configuration-error when one is unknown or its period malformed."
  [configured contributed]
  (let [{:keys [plan error]} (privacy/retention-plan configured (keys contributed))]
    (when error
      (throw (ex-info (:message error) error)))
    plan))

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
