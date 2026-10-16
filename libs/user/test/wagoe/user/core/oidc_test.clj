(ns wagoe.user.core.oidc-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.user.core.oidc :as sut]))

(def ^:private now 1800000000)

(def ^:private claims
  {:iss "https://idp.example.com" :aud "app" :sub "248289761001"
   :exp (+ now 300) :iat now :nonce "n-0S6_WzA2Mj"
   :email "Ada@Example.com" :email_verified true :name "Ada Lovelace"})

(def ^:private opts
  {:issuer "https://idp.example.com" :client-id "app" :nonce "n-0S6_WzA2Mj" :now-epoch now})

(defn- reason [result] (get-in result [:error :reason]))

(deftest ^:unit ^:security id-token-claims
  (is (= claims (:ok (sut/validate-claims claims opts))))
  (testing "issuer"
    (is (= :issuer (reason (sut/validate-claims (assoc claims :iss "https://evil.example.com") opts)))))
  (testing "audience"
    (is (= :audience (reason (sut/validate-claims (assoc claims :aud "other") opts))))
    (is (= :audience (reason (sut/validate-claims (assoc claims :aud ["other" "app"]) opts)))
        "several audiences need azp naming us")
    (is (:ok (sut/validate-claims (assoc claims :aud ["other" "app"] :azp "app") opts)))
    (is (= :audience (reason (sut/validate-claims (assoc claims :azp "other") opts)))))
  (testing "expiry, with skew"
    (is (:ok (sut/validate-claims (assoc claims :exp (- now 30)) opts)))
    (is (= :expired (reason (sut/validate-claims (assoc claims :exp (- now 61)) opts))))
    (is (= :expired (reason (sut/validate-claims (dissoc claims :exp) opts)))))
  (testing "issued in the future"
    (is (:ok (sut/validate-claims (assoc claims :iat (+ now 30)) opts)))
    (is (= :issued-at (reason (sut/validate-claims (assoc claims :iat (+ now 120)) opts)))))
  (testing "nonce"
    (is (= :nonce (reason (sut/validate-claims (assoc claims :nonce "other") opts))))
    (is (= :nonce (reason (sut/validate-claims (dissoc claims :nonce) opts)))))
  (testing "subject"
    (is (= :subject (reason (sut/validate-claims (assoc claims :sub " ") opts))))))

(deftest ^:unit ^:security callback-state
  (let [pending {:provider "google" :state "s1" :nonce "n" :code-verifier "v" :exp (+ now 60)}
        params  {"state" "s1" "code" "c"}]
    (is (= "c" (get-in (sut/check-callback pending "google" params now) [:ok :code])))
    (is (= :oidc/state-mismatch (get-in (sut/check-callback nil "google" params now) [:error :type])))
    (is (= :oidc/state-mismatch (get-in (sut/check-callback pending "google" (assoc params "state" "s2") now) [:error :type])))
    (is (= :oidc/state-mismatch (get-in (sut/check-callback pending "github" params now) [:error :type])))
    (is (= :oidc/state-expired (get-in (sut/check-callback pending "google" params (+ now 61)) [:error :type])))
    (is (= :oidc/provider-error (get-in (sut/check-callback pending "google" (assoc params "error" "access_denied") now) [:error :type])))))

(deftest ^:unit ^:security signing-key-selection
  (let [rsa  {:kty "RSA" :kid "k1" :n "x" :e "AQAB"}
        jwks {:keys [rsa {:kty "EC" :kid "k2" :crv "P-256"}]}]
    (is (= rsa (:ok (sut/select-jwk jwks {:alg :rs256 :kid "k1"}))))
    (is (= :oidc/unknown-key (get-in (sut/select-jwk jwks {:alg :rs256 :kid "k9"}) [:error :type])))
    (is (= :oidc/unknown-key (get-in (sut/select-jwk jwks {:alg :es256 :kid "k1"}) [:error :type]))
        "an RSA key does not verify an ES256 token")
    (doseq [alg [:hs256 :none nil]]
      (is (= :oidc/invalid-token (get-in (sut/select-jwk jwks {:alg alg :kid "k1"}) [:error :type]))
          (str alg " is refused")))))

(deftest ^:unit pkce
  ;; RFC 7636 appendix B
  (is (= "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
         (sut/pkce-challenge "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")))
  (let [url (sut/authorization-url {:authorization_endpoint "https://idp.example.com/auth"}
                                   {:client-id "app" :scopes ["openid" "email"]}
                                   {:redirect-uri "https://app.example.com/web/auth/oidc/x/callback"
                                    :state "s" :nonce "n" :code-verifier "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"})]
    (is (str/starts-with? url "https://idp.example.com/auth?response_type=code&client_id=app"))
    (is (str/includes? url "code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256"))
    (is (str/includes? url "scope=openid+email"))))

(deftest ^:unit settings
  (let [p {:issuer "https://idp.example.com/" :client-id "app"}]
    (is (= {:id "google" :issuer "https://idp.example.com" :label "Google"
            :scopes ["openid" "email" "profile"] :default-role :user
            :link-by-email? false :trust-email? false :auto-create? false :client-id "app"}
           (get-in (sut/normalize-settings {:base-url "https://app.example.com" :providers {:google p}})
                   [:ok :providers "google"])))
    (is (= :configuration-error (get-in (sut/normalize-settings {:providers {:google p}}) [:error :type])))
    (is (:error (sut/normalize-provider :x (assoc p :issuer "http://idp.example.com"))))
    (is (:ok (sut/normalize-provider :x (assoc p :issuer "http://localhost:9000"))))
    (is (:error (sut/normalize-provider :x (assoc p :default-role :admin))))
    (is (:error (sut/normalize-provider :x (assoc p :scopes ["email"]))))
    (is (= :configuration-error
           (get-in (sut/normalize-provider :entra (assoc p :issuer "https://login.microsoftonline.com/{tenantid}/v2.0"))
                   [:error :type]))
        "a multi-tenant issuer template is refused")))

(deftest ^:unit ^:security account-resolution
  (let [provider {:link-by-email? true :auto-create? false :default-role :user}
        ada      {:id 1 :email "ada@example.com"}]
    (testing "an (iss, sub) link wins"
      (is (= {:action :login :user ada}
             (sut/resolve-account {:linked-user ada :claims (dissoc claims :email) :provider provider}))))
    (testing "verified email links"
      (is (= {:action :link :user ada}
             (sut/resolve-account {:email-user ada :claims claims :provider provider}))))
    (testing "unverified email never links or creates"
      (doseq [c [(assoc claims :email_verified false) (dissoc claims :email_verified)]]
        (is (= :oidc/no-account
               (get-in (sut/resolve-account {:email-user ada :claims c
                                             :provider (assoc provider :auto-create? true)})
                       [:error :type])))))
    (testing "an account already linked at this issuer is not linked again by email"
      (is (= :oidc/no-account
             (get-in (sut/resolve-account {:email-user ada :email-user-linked? true :claims claims
                                           :provider (assoc provider :auto-create? true)})
                     [:error :type]))))
    (testing "an administrator is never linked by email"
      (is (= :oidc/admin-link-refused
             (get-in (sut/resolve-account {:email-user (assoc ada :role :admin) :claims claims :provider provider})
                     [:error :type]))))
    (testing ":trust-email? treats an email with no email_verified as verified"
      (is (= {:action :link :user ada}
             (sut/resolve-account {:email-user ada :claims (dissoc claims :email_verified)
                                   :provider (assoc provider :trust-email? true)}))))
    (testing "linking by email can be switched off"
      (is (:error (sut/resolve-account {:email-user ada :claims claims
                                        :provider (assoc provider :link-by-email? false)}))))
    (testing "no account and no auto-create"
      (is (= :oidc/no-account (get-in (sut/resolve-account {:claims claims :provider provider})
                                      [:error :type]))))
    (testing "auto-create"
      (is (= {:action :create
              :user-data {:email "ada@example.com" :name "Ada Lovelace" :role :user :active true}}
             (sut/resolve-account {:claims claims :provider (assoc provider :auto-create? true)}))))))
