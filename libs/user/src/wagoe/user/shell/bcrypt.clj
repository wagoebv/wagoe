(ns wagoe.user.shell.bcrypt
  "The one place passwords and MFA backup codes are bcrypt-hashed."
  (:require [buddy.hashers :as hashers]))

(def work-factor
  "Pinned so a buddy-hashers upgrade cannot lower it (BOU-943)."
  12)

(defn derive-hash
  "`s` hashed with bcrypt+sha512 at `work-factor`."
  [s]
  (hashers/derive s {:alg :bcrypt+sha512 :iterations work-factor}))
