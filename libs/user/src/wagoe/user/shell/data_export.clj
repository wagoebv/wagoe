(ns wagoe.user.shell.data-export
  "The user module's contributions to data export and retention: a user's
   account, sessions, sign-in links, groups and audit trail, and the audit-log
   retention policy."
  (:require [wagoe.core.utils.type-conversion :as type-conversion]
            [wagoe.platform.ports.privacy :as privacy]
            [wagoe.user.core.data-export :as core]
            [wagoe.user.ports :as ports]))

(def ^:private page-size 500)

(defn- audit-entries
  "Every audit entry matching `filters`, read a page at a time. Ordered by id,
   which is unique, so no entry falls between two pages."
  [audit-repository filters]
  (loop [offset 0 acc []]
    (let [page (:audit-logs (ports/find-audit-logs audit-repository
                                                   (assoc filters :limit page-size :offset offset
                                                          :sort-by :id :sort-direction :asc)))
          acc  (into acc page)]
      (if (< (count page) page-size)
        acc
        (recur (+ offset page-size) acc)))))

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
