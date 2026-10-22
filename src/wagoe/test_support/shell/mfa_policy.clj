(ns wagoe.test-support.shell.mfa-policy
  "The test profile's :mfa-required? hook, so e2e can sign in as a user the
   app requires a second factor of."
  (:require [clojure.string :as str]))

(defn required?
  "The default rule, MFA when the user turned it on, and always for an
   address at mfa-required.test."
  [user _login-context]
  (or (boolean (:mfa-enabled user))
      (str/ends-with? (str (:email user)) "@mfa-required.test")))
