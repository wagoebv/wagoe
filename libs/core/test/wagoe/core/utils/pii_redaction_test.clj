(ns wagoe.core.utils.pii-redaction-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.core.utils.pii-redaction :as pii]))

(deftest ^:unit normalize-key-name-test
  (testing "Normalizes various key types to lowercase strings"
    (is (= "password" (pii/normalize-key-name :Password)))
    (is (= "email" (pii/normalize-key-name "Email")))
    (is (= "user-id" (pii/normalize-key-name :user-id)))))

(deftest ^:unit default-redaction-keys-test
  (testing "Default key set includes common sensitive keys"
    (is (contains? pii/default-redact-keys "password"))
    (is (contains? pii/default-redact-keys "token"))
    (is (contains? pii/default-redact-keys "email"))))

(deftest ^:unit email-masking-test
  (testing "Email masking preserves domain and masks local part"
    (is (= "u***@example.com" (pii/mask-email "user@example.com")))
    (is (= "a***@example.com" (pii/mask-email "a@example.com")))
    (is (= "[REDACTED]" (pii/mask-email "not-an-email")))))

(deftest ^:unit redact-pii-basic-test
  (testing "Redacts values by key with default state"
    (let [state (pii/build-redact-state {:redact {}})
          data  {:password "secret"
                 :token    "abc"
                 :email    "user@example.com"
                 :safe     "ok"}
          out   (pii/redact-pii data state)]
      (is (= "[REDACTED]" (:password out)))
      (is (= "[REDACTED]" (:token out)))
      (is (re-find #".*\*\*\*@example\.com$" (:email out)))
      (is (= "ok" (:safe out))))))

(deftest ^:unit redact-pii-additional-keys-test
  (testing "Redacts additional configured keys"
    (let [state (pii/build-redact-state {:redact {:additional-keys [:custom]}})
          data  {:custom "val"
                 :other  "keep"}
          out   (pii/redact-pii data state)]
      (is (= "[REDACTED]" (:custom out)))
      (is (= "keep" (:other out))))))

(deftest ^:unit redact-pii-disable-email-masking-test
  (testing "Email is not masked when :mask-email? is false"
    (let [state (pii/build-redact-state {:redact {:mask-email? false}})
          data  {:email "user@example.com"}
          out   (pii/redact-pii data state)]
      (is (= "user@example.com" (:email out))))))

(deftest ^:unit redact-pii-nested-structures-test
  (testing "Redacts PII in nested structures"
    (let [state (pii/build-redact-state {:redact {}})
          data  {:user {:password "secret"
                        :name     "John"}
                 :tokens ["tok1" "tok2"]}
          out   (pii/redact-pii data state)]
      (is (= "[REDACTED]" (get-in out [:user :password])))
      (is (= "John" (get-in out [:user :name])))
      (is (= ["tok1" "tok2"] (:tokens out))))))

(deftest ^:unit apply-redaction-test
  (testing "Applies redaction to :extra and :tags"
    (let [context {:extra {:password "pw"
                           :safe     "ok"}
                   :tags  {:token "tok"
                           :env   "prod"}}
          out     (pii/apply-redaction context {})]
      (is (= "[REDACTED]" (get-in out [:extra :password])))
      (is (= "ok" (get-in out [:extra :safe])))
      (is (= "[REDACTED]" (get-in out [:tags :token])))
      (is (= "prod" (get-in out [:tags :env]))))))

(deftest ^:unit apply-redaction-default-behavior-test
  (testing "Applies default redaction even without :redact key"
    (let [context {:extra {:password "secret"}}
          out     (pii/apply-redaction context {})]
      (is (= "[REDACTED]" (get-in out [:extra :password]))))))

(deftest ^:unit redact-pii-idempotence-test
  (testing "Redaction is idempotent"
    (let [state (pii/build-redact-state {:redact {}})
          data  {:password "secret"
                 :nested  {:email "user@example.com"
                           :token "abc"}
                 :safe    "ok"}
          once  (pii/redact-pii data state)
          twice (pii/redact-pii once state)]
      (is (= once twice))
      (is (= "ok" (:safe once))))))

(deftest ^:unit ^:security redact-for-log-test
  (let [out (pii/redact-for-log
             {:user-entity {:id               7
                            :email            "user@example.com"
                            :password-hash    "bcrypt+sha512$abc"
                            :mfa-secret       "JBSWY3DPEHPK3PXP"
                            :mfa-backup-codes ["11111111"]
                            :session_token    "tok"
                            :webhook-secret   "whsec"
                            :api-key          "key"}})]
    (testing "secrets are redacted by name, including -secret/-token/-hash suffixes"
      (doseq [k [:password-hash :mfa-secret :mfa-backup-codes :session_token :webhook-secret :api-key]]
        (is (= "[REDACTED]" (get-in out [:user-entity k])) (str k))))
    (testing "ids and emails stay readable"
      (is (= 7 (get-in out [:user-entity :id])))
      (is (= "user@example.com" (get-in out [:user-entity :email]))))))

(deftest ^:unit ^:security redact-for-log-key-names-test
  (testing "one-time codes, cookies and password confirmation, kebab and snake"
    (doseq [k [:mfa-code :mfa_code :verification-code :verification_code
               :backup-code :backup_code :confirmation-code :confirmation_code
               :cookie "set-cookie" :password-confirmation]]
      (is (= "[REDACTED]" (get (pii/redact-for-log {k "v"}) k)) (str k))))
  (testing "camelCase names are normalized before matching"
    (doseq [k ["passwordHash" "accessToken" "apiKey" :mfaSecret]]
      (is (= "[REDACTED]" (get (pii/redact-for-log {k "v"}) k)) (str k))))
  (testing "credential-bearing -key names are redacted"
    (doseq [k [:stripe-secret-key "x-api-key" "X-API-Key" :secret-key :aws-access-key :signing-key]]
      (is (= "[REDACTED]" (get (pii/redact-for-log {k "v"}) k)) (str k))))
  (testing "other -key, -code and -id names are left alone"
    (doseq [k [:idempotency-key :cache-key :api-key-id :public-key :status-code :country-code]]
      (is (= "v" (get (pii/redact-for-log {k "v"}) k)) (str k))))
  (testing "known non-secret hashes stay readable; csrf-token does not"
    (let [out (pii/redact-for-log {:content-hash "h1" :commit-hash "h2" :etag "e" :csrf-token "c"})]
      (is (= {:content-hash "h1" :commit-hash "h2" :etag "e" :csrf-token "[REDACTED]"} out)))))

(deftest ^:unit ^:security redact-for-log-walks-sets-and-java-maps-test
  (testing "maps inside a set are redacted"
    (is (= #{{:mfa-secret "[REDACTED]" :id 1}}
           (pii/redact-for-log #{{:mfa-secret "s" :id 1}}))))
  (testing "a java.util.Map is redacted"
    (is (= {"password" "[REDACTED]" "id" 1}
           (pii/redact-for-log (java.util.HashMap. {"password" "p" "id" 1})))))
  (testing "Sentry context gets the same non-secret allowance"
    (let [out (pii/apply-redaction {:extra {:content-hash "h" :csrf-token "c"}} {})]
      (is (= "h" (get-in out [:extra :content-hash])))
      (is (= "[REDACTED]" (get-in out [:extra :csrf-token]))))))
