(ns acme.office.shell.module-wiring
  "An application module contributing both sign-in hooks as components, for
   wagoe.user.shell.auth-hooks-test."
  (:require [clojure.string :as str]
            [integrant.core :as ig]
            [wagoe.user.ports :as ports]))

(defn- in-office? [office {:keys [ip-address]}]
  (str/starts-with? (str ip-address) office))

(defmethod ig/init-key :wagoe/office [_ v] v)

(defmethod ig/init-key :wagoe/office-mfa-policy [_ {:keys [office]}]
  (reify ports/IMfaPolicy
    (mfa-required? [_ _user ctx] (not (in-office? office ctx)))))

(defmethod ig/init-key :wagoe/office-landing [_ {:keys [office]}]
  (fn [_user ctx] (if (in-office? office ctx) "/web/kantoor" "/web/thuis")))

(defn ig-config [settings _ctx]
  {:components {:wagoe/office            settings
                :wagoe/office-mfa-policy {:office (:office settings)}
                :wagoe/office-landing    {:office (:office settings)}}
   :auth       {:mfa-required?  (ig/ref :wagoe/office-mfa-policy)
                :login-redirect (ig/ref :wagoe/office-landing)}})
