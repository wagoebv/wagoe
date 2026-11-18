(ns wagoe.user.shell.audit-redaction
  "The migration that blanks credential values already in user_audit_log
   (BOU-1013): a password change kept the old and the new hash."
  (:require [cheshire.core :as json]
            [wagoe.platform.database :as db]
            [wagoe.user.core.audit :as audit-core]
            [wagoe.user.shell.persistence :as persistence]))

(defn redact!
  "Blank the credential values in every update entry that holds one."
  [ctx]
  ;; ponytail: reads every update entry once; page it if a log runs to millions
  (doseq [{:keys [id changes]} (db/execute-query! ctx {:select [:id :changes] :from [:user_audit_log]
                                                        :where [:= :action "update"]})
          :let [before (persistence/read-json changes)
                after  (audit-core/redact-secret-changes before)]
          :when (not= before after)]
    (db/execute-update! ctx {:update :user_audit_log
                             :set    {:changes (json/generate-string after)}
                             :where  [:= :id id]})))

(defn up
  "Migratus entry point."
  [config]
  (redact! (db/context-of (db/migration-connectable config))))

(defn down
  "Nothing: the values are gone."
  [_config])
