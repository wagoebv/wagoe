(ns wagoe.tools.secret-keys-test
  "The Babashka copy answers as wagoe.core.utils.secret-keys does (BOU-973)."
  (:require [clojure.test :refer [deftest is]]
            [wagoe.tools.secret-keys :as tools]))

(load-file "libs/core/src/wagoe/core/utils/text.clj")
(load-file "libs/core/src/wagoe/core/utils/secret_keys.clj")

(def ^:private samples
  [:password :db-password :passwd :passphrase :pwd :secret :jwt-secret :client_secret
   :token :access-token "accessToken" "DBPassword" "APIKey" :credentials :credential
   :apikey :api-key :stripe-secret-key :private-key :access-key :service-key
   :encryption-key :signing-key :master-key
   :secret-name :token-ttl :password-policy :idempotency-key :cache-key :host
   :user :email :password-hash])

(deftest ^:unit ^:security the-copy-agrees-with-core
  ;; Same pattern, so a key outside `samples` cannot tell the copies apart either.
  (is (= (str @(resolve 'wagoe.core.utils.secret-keys/secret-name))
         (str @#'tools/secret-name)))
  (let [core-secret?   (resolve 'wagoe.core.utils.secret-keys/secret-key?)
        core-normalize (resolve 'wagoe.core.utils.secret-keys/normalize)]
    (doseq [k samples]
      (is (= (core-normalize k) (tools/normalize k)) (pr-str k))
      (is (= (core-secret? k) (tools/secret-key? k)) (pr-str k)))))

(def ^:private secret-samples
  ["change-me-to-a-32-char-minimum-secret" "dev-secret-at-least-32-characters-long"
   "change-me-min-32-chars-................" "CHANGEME-and-more-letters-to-pass" "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
   "q8Zt0n4mR2vJ7kPa1sXc9LbW3eYh6GdUf5Ni" "" nil])

(deftest ^:unit ^:security the-copies-agree-on-placeholder-secrets
  (let [core? (resolve 'wagoe.core.utils.secret-keys/placeholder-secret?)]
    (doseq [s secret-samples]
      (is (= (core? s) (tools/placeholder-secret? s)) (pr-str s))))
  (is (every? tools/placeholder-secret? (butlast (drop-last 2 secret-samples))))
  (is (not (tools/placeholder-secret? "q8Zt0n4mR2vJ7kPa1sXc9LbW3eYh6GdUf5Ni"))))

(deftest ^:unit ^:security the-last-word-decides
  (is (every? tools/secret-key? [:password :jwt-secret "accessToken" "DBPassword" :stripe-secret-key :credentials]))
  (is (not-any? tools/secret-key? [:secret-name :token-ttl :idempotency-key :host :password-policy])))
