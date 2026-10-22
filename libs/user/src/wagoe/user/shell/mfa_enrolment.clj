(ns wagoe.user.shell.mfa-enrolment
  "The ticket a user whose password was right, but who must set up a second
   factor before signing in, carries to the setup page: sealed in a cookie,
   so it proves the password without a session."
  (:require [wagoe.user.shell.auth :as auth]
            [wagoe.user.shell.sealed :as sealed]))

(def ttl-seconds
  "How long the setup page accepts a ticket."
  600)

(defn- ticket-key []
  (sealed/derive-key "wagoe.user.mfa-enrolment" (auth/get-jwt-secret)))

(defn ticket
  "A sealed ticket to enrol `user`, who returns to `return-to` afterwards."
  [user return-to]
  (sealed/seal (ticket-key) :mfa-enrolment
               (cond-> {:user-id (str (:id user)) :email (:email user)}
                 return-to (assoc :return-to return-to))
               ttl-seconds))

(defn claims
  "{:user-id :email :return-to} of a valid ticket, else nil."
  [value]
  (when-let [c (sealed/unseal (ticket-key) :mfa-enrolment value)]
    (when-let [id (parse-uuid (str (:user-id c)))]
      (-> (select-keys c [:email :return-to]) (assoc :user-id id)))))
