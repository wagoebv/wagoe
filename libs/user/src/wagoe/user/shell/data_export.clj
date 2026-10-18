(ns wagoe.user.shell.data-export
  "The user module's contributions to data export and retention: a user's
   account, sessions, sign-in links, groups and audit trail, and the audit-log
   retention policy."
  (:require [wagoe.core.utils.type-conversion :as type-conversion]
            [wagoe.platform.ports.privacy :as privacy]
            [wagoe.user.core.audit :as audit]
            [wagoe.user.core.data-export :as core]
            [wagoe.user.ports :as ports]))

(def ^:private page-size 500)

(defn- audit-entries
  "Every audit entry matching `filters`, a page at a time, each page starting
   after the last id of the one before."
  [audit-repository filters]
  (loop [after-id nil acc []]
    (let [page (vec (:audit-logs (ports/find-audit-logs audit-repository
                                                        (assoc filters :limit page-size :after-id after-id
                                                               :sort-by :id :sort-direction :asc
                                                               :count? false))))
          acc  (into acc page)]
      (if (< (count page) page-size)
        acc
        (recur (:id (peek page)) acc)))))

(defrecord UserDataExporter [user-repository session-repository audit-repository
                             identity-repository group-repository]
  privacy/IDataExporter
  (export-data [_ {:keys [type id]}]
    (when (= :user type)
      (when-let [user (some->> (if (uuid? id) id (type-conversion/parse-uuid-string (str id)))
                                (ports/find-user-by-id user-repository))]
        (let [user-id (:id user)]
          (core/user-export
           {:user          user
            :sessions      (ports/find-sessions-by-user session-repository user-id)
            :identities    (ports/find-identities-by-user identity-repository user-id)
            :groups        (ports/find-user-groups group-repository user-id)
            :audit-entries (concat (audit-entries audit-repository {:filter-target-user-id user-id})
                                   (audit-entries audit-repository {:filter-actor-id user-id}))}))))))

(defrecord AuditLogRetention [audit-repository]
  privacy/IRetentionPolicy
  (purge-before! [_ cutoff]
    (ports/delete-audit-logs-before audit-repository cutoff)))

(defn record-admin-export!
  "Audit an admin's download of `target`'s data. Before the response, and not
   caught: an export nobody can trace afterwards is not served."
  [audit-repository audit-context target]
  (ports/create-audit-log audit-repository (audit/data-export-audit-entry audit-context target)))
