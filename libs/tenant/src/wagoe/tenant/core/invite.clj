(ns wagoe.tenant.core.invite
  (:require [clojure.string :as str])
  (:import (java.time Instant)))

(defn normalize-email
  "Normalize invite email for consistent matching."
  [email]
  (some-> email str str/trim str/lower-case))

(defn prepare-invite*
  "Creates a new tenant invite in :pending status using explicit runtime inputs."
  [{:keys [invite-id tenant-id email role token-hash expires-at metadata]} now]
  {:id                  invite-id
   :tenant-id           tenant-id
   :email               (normalize-email email)
   :role                role
   :status              :pending
   :token-hash          token-hash
   :expires-at          expires-at
   :accepted-at         nil
   :revoked-at          nil
   :accepted-by-user-id nil
   :metadata            metadata
   :created-at          now
   :updated-at          nil})

(defn expired?
  "Returns true when the invite is no longer valid due to expiry."
  [invite now]
  (let [expires-at (:expires-at invite)]
    (and expires-at (.isBefore ^Instant expires-at now))))

(defn accept-invite
  "Transitions an invite to :accepted."
  [invite accepted-by-user-id now]
  (assoc invite
         :status :accepted
         :accepted-at now
         :accepted-by-user-id accepted-by-user-id
         :updated-at now))

(defn revoke-invite
  "Transitions an invite to :revoked."
  [invite now]
  (assoc invite
         :status :revoked
         :revoked-at now
         :updated-at now))
