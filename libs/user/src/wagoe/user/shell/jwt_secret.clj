(ns wagoe.user.shell.jwt-secret
  "Where the user module reads and checks JWT_SECRET. Auth signs with it and MFA
   derives its encryption key from it, so both refuse the same secrets (BOU-977)."
  (:require [clojure.string :as str]))

(def ^:private min-length
  "A short HMAC key materially weakens signing, so anything shorter is refused."
  32)

(defn- validate
  "`secret` when valid, else a WGE-103 :configuration-error."
  [secret]
  (if-let [problem (cond
                     (str/blank? secret)
                     "JWT_SECRET environment variable not configured. Set JWT_SECRET before starting the application."

                     (< (count secret) min-length)
                     (str "JWT_SECRET must be at least " min-length " characters (HMAC key strength)."))]
    (throw (ex-info (str "WGE-103: " problem)
                    {:type :configuration-error
                     :wagoe/error-code "WGE-103"
                     :required-env-var "JWT_SECRET"}))
    secret))

(def ^:private secret
  "Read and checked once per process: the environment cannot change within a JVM."
  (delay (validate (System/getenv "JWT_SECRET"))))

(defn value
  "The validated JWT_SECRET. Throws WGE-103 when it is missing or short."
  []
  @secret)
