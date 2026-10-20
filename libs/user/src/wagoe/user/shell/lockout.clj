(ns wagoe.user.shell.lockout
  "The failed-login lockout every credential check shares: sign-in, the second
   factor, MFA disable and change-password."
  (:require [wagoe.user.core.authentication :as auth-core]
            [wagoe.user.ports :as ports]))

(defn claim-attempts!
  "Count `n` attempts for `user-id` before checking them, unless the account is
   locked: the count is the lockout check, so parallel attempts cannot exceed
   the limit (BOU-785). Returns {:claimed? true :locked? b}, :locked? when this
   claim reached the limit, or {:claimed? false :retry-after inst}."
  [user-repository auth-config user-id n current-time]
  (let [lockout (auth-core/failed-login-lockout auth-config current-time)
        claim   (ports/claim-login-attempts user-repository user-id n lockout)]
    (cond
      ;; Deleted since it was read: nothing to count, and no lockout to report.
      (nil? claim)
      {:claimed? true :locked? false}

      (:claimed? claim)
      {:claimed? true :locked? (>= (:failed-login-count claim) (:max-attempts lockout))}

      :else
      {:claimed? false :retry-after (or (:lockout-until claim) (:until lockout))})))

(defn release-attempt!
  "Take back an attempt `claim-attempts!` counted whose password was right,
   and the lockout it set, if it set one."
  [user-repository user-id claim]
  (ports/release-login-attempt user-repository user-id (:locked? claim)))

(defn locked-error
  "The ADR-036 refusal for an account locked until `retry-after`."
  [retry-after]
  {:type        :account-locked
   :message     "Too many failed attempts, try again later"
   :retry-after retry-after})
