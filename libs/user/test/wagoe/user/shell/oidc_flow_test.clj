(ns wagoe.user.shell.oidc-flow-test
  "OIDC sign-in end to end against a fake provider on a real socket: discovery,
   key set and token endpoint, with PKCE and client authentication checked the
   way a provider checks them."
  (:require [buddy.core.codecs :as codecs]
            [buddy.core.hash :as hash]
            [buddy.core.keys :as keys]
            [buddy.sign.jwt :as jwt]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [one-time.core :as otp]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.codec :as codec]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.auth :as auth]
            [wagoe.user.shell.in-memory-repository-test :as mem]
            [wagoe.user.shell.middleware :as middleware]
            [wagoe.user.shell.mfa :as mfa]
            [wagoe.user.shell.oidc :as oidc]
            [wagoe.user.shell.service :as service]
            [wagoe.user.shell.web-handlers :as handlers])
  (:import (java.security KeyPairGenerator)
           (java.time Instant)
           (java.util UUID)
           (org.eclipse.jetty.server ServerConnector)))

;; =============================================================================
;; Fake provider
;; =============================================================================

(defn- rsa-key-pair []
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048))))

(def ^:private provider-keys (rsa-key-pair))
(def ^:private forged-keys (rsa-key-pair))

(def ^:private idp
  "Provider state: issued codes, request counts, and per-test overrides."
  (atom {}))

(def ^:dynamic *issuer* nil)

(defn- stated-issuer
  "The issuer as the provider writes it: some, like Auth0, end it with a slash."
  []
  (str *issuer* (when (:issuer-slash? @idp) "/")))

(defn- json-response [body]
  {:status 200 :headers {"Content-Type" "application/json"} :body (json/generate-string body)})

(defn- basic-credentials
  "client_secret_basic as a provider reads it: Base64, then form-decode each half."
  [header]
  (when (and header (str/starts-with? header "Basic "))
    (let [[id secret] (str/split (codecs/bytes->str (codecs/b64->bytes (subs header 6))) #":" 2)]
      [(codec/form-decode-str id) (codec/form-decode-str (str secret))])))

(defn- token-response [{:keys [form-params headers]}]
  (let [grant (get (:codes @idp) (get form-params "code"))]
    (swap! idp update :codes dissoc (get form-params "code"))
    (cond
      (not= ["app" (:client-secret @idp)] (basic-credentials (get headers "authorization")))
      {:status 401 :body "{\"error\":\"invalid_client\"}"}

      (or (nil? grant)
          (not= (:redirect-uri grant) (get form-params "redirect_uri"))
          (not= (:challenge grant)
                (codecs/bytes->b64-str (hash/sha256 (str (get form-params "code_verifier"))) true)))
      {:status 400 :body "{\"error\":\"invalid_grant\"}"}

      :else
      (let [now    (.getEpochSecond (Instant/now))
            claims ((:claims-fn @idp identity)
                    {:iss (stated-issuer) :aud "app" :sub (:sub @idp "sub-1")
                     :email (:email @idp "ada@example.com") :email_verified true
                     :name "Ada Lovelace" :nonce (:nonce grant) :iat now :exp (+ now 300)})]
        (json-response {:token_type "Bearer" :access_token "at"
                        :id_token (jwt/sign claims (.getPrivate ^java.security.KeyPair (:sign-with @idp provider-keys))
                                            {:alg :rs256 :header {:kid "k1"}})})))))

(defn- provider-app [request]
  (swap! idp update-in [:hits (:uri request)] (fnil inc 0))
  (case (:uri request)
    "/.well-known/openid-configuration"
    (json-response {:issuer (stated-issuer)
                    :authorization_endpoint (str *issuer* "/authorize")
                    :token_endpoint (str *issuer* "/token")
                    :jwks_uri (str *issuer* "/jwks")})
    "/jwks"
    (json-response {:keys [(assoc (keys/public-key->jwk (.getPublic ^java.security.KeyPair provider-keys))
                                  :kid "k1" :use "sig" :alg "RS256")]})
    "/token" (token-response request)
    {:status 404 :body ""}))

(defn- authorize!
  "The person at the provider says yes: issue a code bound to the request."
  [location]
  (let [params (codec/form-decode (second (str/split location #"\?" 2)))
        code   (str (UUID/randomUUID))]
    (swap! idp assoc-in [:codes code] {:nonce        (get params "nonce")
                                       :challenge    (get params "code_challenge")
                                       :redirect-uri (get params "redirect_uri")})
    {"state" (get params "state") "code" code}))

(use-fixtures :once
  (fn [f]
    (let [issuer (promise)
          server (jetty/run-jetty (wrap-params (fn [req] (binding [*issuer* @issuer] (provider-app req))))
                                  {:port 0 :join? false})]
      (deliver issuer (str "http://localhost:" (.getLocalPort ^ServerConnector (first (.getConnectors server)))))
      (try
        (binding [*issuer* @issuer]
          (with-redefs [auth/get-jwt-secret (constantly "test-secret-minimum-32-characters-long")]
            (f)))
        (finally (.stop server))))))

;; =============================================================================
;; The application
;; =============================================================================

(defrecord MemoryIdentityRepository [state]
  ports/IUserIdentityRepository
  (find-identity [_ issuer subject] (get @state [issuer subject]))
  (find-identities-by-user [_ user-id] (filterv #(= user-id (:user-id %)) (vals @state)))
  (link-identity [_ identity]
    (when (get @state [(:issuer identity) (:subject identity)])
      (throw (ex-info "Duplicate identity" {:type :conflict})))
    (let [stored (assoc identity :id (UUID/randomUUID) :created-at (Instant/now))]
      (swap! state assoc [(:issuer identity) (:subject identity)] stored)
      stored))
  (touch-identity [_ issuer subject at]
    (boolean (when (get @state [issuer subject])
               (swap! state assoc-in [[issuer subject] :last-login-at] at))))
  (unlink-identity [_ issuer subject]
    (boolean (when (get @state [issuer subject]) (swap! state dissoc [issuer subject])))))

(defn- app
  "`:user-settings` merge into `:wagoe/user`; the other options configure the
   provider."
  [& {:keys [user-settings] :as opts}]
  (reset! idp {})
  (let [users      (mem/->MemoryUserRepository (atom {}))
        sessions   (mem/->MemorySessionRepository (atom {}))
        audit      (mem/->MemoryAuditRepository (atom []))
        identities (->MemoryIdentityRepository (atom {}))
        auth-svc   (auth/create-authentication-service users sessions (mfa/create-mfa-service users {}) {})
        provider   (merge {:issuer *issuer* :client-id "app" :client-secret "app-secret"
                           :link-by-email? true}
                          (dissoc opts :user-settings))
        _          (swap! idp assoc :client-secret (:client-secret provider))
        settings   {:base-url  "http://localhost:3000"
                    :providers {:fake provider}}
        client     (oidc/create-oidc-client {:settings settings :secret (apply str (repeat 40 "s"))
                                             :auth-service auth-svc :audit-repository audit
                                             :identity-repository identities})
        config     {:active {:wagoe/user (merge {:oidc settings} user-settings)}}]
    {:users users :sessions sessions :audit audit :identities identities :client client
     :user-service (service/create-user-service users sessions audit {} auth-svc)
     :start    (handlers/oidc-start-handler client config)
     :callback (handlers/oidc-callback-handler client config)
     :mfa      (handlers/oidc-mfa-handler client config)}))

(defn- add-user! [{:keys [users]} & {:as attrs}]
  (ports/create-user users (merge {:email "ada@example.com" :name "Ada" :role :user
                                   :active true :password-hash "x"}
                                  attrs)))

(defn- request [m]
  (merge {:remote-addr "127.0.0.1" :headers {"user-agent" "test"} :query-params {} :cookies {}} m))

(defn- start! [{:keys [start]}]
  (let [resp (start (request {:path-params {:provider "fake"}}))]
    {:location (get-in resp [:headers "Location"])
     :cookie   (get-in resp [:cookies "oidc-pending" :value])}))

(defn- callback! [{:keys [callback]} cookie params]
  (callback (request {:path-params {:provider "fake"} :query-params params
                      :cookies {"oidc-pending" {:value cookie}}})))

(defn- sign-in!
  "The whole browser round trip. Returns the callback response."
  [a]
  (let [{:keys [location cookie]} (start! a)]
    (callback! a cookie (authorize! location))))

(defn- session-token [resp] (get-in resp [:cookies "session-token" :value]))

(defn- authenticated-as
  "Who a request carrying the session token is, through the real middleware."
  [{:keys [user-service]} token]
  (let [resp ((middleware/session-authentication-middleware
               user-service (fn [req] {:status 200 :body (get-in req [:user :email])}))
              {:uri "/x" :headers {"x-session-token" token}})]
    (when (= 200 (:status resp)) (:body resp))))

;; =============================================================================
;; Tests
;; =============================================================================

(deftest ^:integration ^:security signs-in-with-a-db-session
  (let [a    (app)
        ada  (add-user! a)
        resp (sign-in! a)]
    (is (= 200 (:status resp)))
    (is (= "0;url=/web/dashboard" (get-in resp [:headers "Refresh"])))
    (is (= :strict (get-in resp [:cookies "session-token" :same-site])))
    (is (= "ada@example.com" (authenticated-as a (session-token resp))))
    (testing "linked by verified email to (iss, sub)"
      (is (= [(:id ada)] (map :user-id (ports/find-identities-by-user (:identities a) (:id ada))))))
    (testing "the link, not the email, finds the account next time"
      (swap! idp assoc :email "ada@new-employer.example")
      (is (= "ada@example.com" (authenticated-as a (session-token (sign-in! a))))))
    (testing "audited as an OIDC login"
      (is (= {:method "oidc" :provider "fake"}
             (:metadata (first (filter #(= :login (:action %)) @(:state (:audit a))))))))
    (testing "discovery and keys are fetched once"
      (is (= 1 (get-in @idp [:hits "/.well-known/openid-configuration"])))
      (is (= 1 (get-in @idp [:hits "/jwks"]))))))

(deftest ^:integration issuer-with-a-trailing-slash
  (let [a (app)]
    (add-user! a)
    (swap! idp assoc :issuer-slash? true)
    (is (= "ada@example.com" (authenticated-as a (session-token (sign-in! a)))))))

(deftest ^:integration an-email-registered-with-capitals-still-links
  (let [a (app)]
    (add-user! a :email "Ada@Example.com")
    (swap! idp assoc :email "Ada@Example.com")
    (is (= "Ada@Example.com" (authenticated-as a (session-token (sign-in! a)))))))

(deftest ^:integration ^:security a-recycled-email-does-not-take-over-a-linked-account
  (let [a (app :auto-create? true)]
    (add-user! a)
    (is (some? (session-token (sign-in! a))))
    (swap! idp assoc :sub "sub-2")
    (let [resp (sign-in! a)]
      (is (= 403 (:status resp)))
      (is (nil? (session-token resp))))))

(deftest ^:integration ^:security return-to-is-kept-and-must-be-local
  (let [a (app)]
    (add-user! a)
    (doseq [[asked landed] [["/web/profile" "/web/profile"] ["https://evil.example" "/web/dashboard"]]]
      (let [resp ((:start a) (request {:path-params {:provider "fake"} :query-params {"return-to" asked}}))
            cb   (callback! a (get-in resp [:cookies "oidc-pending" :value])
                            (authorize! (get-in resp [:headers "Location"])))]
        (is (= (str "0;url=" landed) (get-in cb [:headers "Refresh"])))))))

(deftest ^:integration lands-where-the-app-says
  (let [a (app :user-settings {:login-redirect {:user "/web/overzicht"}})]
    (add-user! a)
    (is (= "0;url=/web/overzicht" (get-in (sign-in! a) [:headers "Refresh"])))))

(deftest ^:integration ^:security rejected-id-tokens-open-no-session
  (doseq [[reason override] [[:signature {:sign-with forged-keys}]
                             [:audience {:claims-fn #(assoc % :aud "someone-else")}]
                             [:issuer {:claims-fn #(assoc % :iss "https://evil.example.com")}]
                             [:expired {:claims-fn #(assoc % :exp (- (:iat %) 600))}]
                             [:nonce {:claims-fn #(assoc % :nonce "replayed")}]]]
    (testing reason
      (let [a (app)]
        (add-user! a)
        (swap! idp merge override)
        (let [{:keys [location cookie]} (start! a)]
          (is (= {:type :oidc/invalid-token :reason reason}
                 (select-keys (:error (oidc/callback (:client a) "fake" (authorize! location) cookie {}))
                              [:type :reason]))))
        (let [resp (sign-in! a)]
          (is (= 400 (:status resp)))
          (is (nil? (session-token resp)))
          (is (empty? @(:state (:sessions a)))))))))

(deftest ^:integration ^:security state-must-match
  (let [a (app)]
    (add-user! a)
    (let [{:keys [location cookie]} (start! a)
          params (authorize! location)]
      (is (= 400 (:status (callback! a cookie (assoc params "state" "forged")))))
      (is (= 400 (:status (callback! a nil params))) "no pending sign-in")
      (is (= 400 (:status (callback! a "garbage" params))) "a cookie we did not seal")
      (is (empty? @(:state (:sessions a)))))))

(deftest ^:integration ^:security pkce-binds-the-code-to-its-flow
  (let [a (app)]
    (add-user! a)
    ;; A code issued to one flow, presented with another flow's state and
    ;; verifier: the provider sees a verifier that does not hash to the
    ;; challenge it bound the code to.
    (let [flow-a (start! a)
          flow-b (start! a)
          code-a (get (authorize! (:location flow-a)) "code")
          state-b (get (codec/form-decode (second (str/split (:location flow-b) #"\?" 2))) "state")
          resp   (callback! a (:cookie flow-b) {"state" state-b "code" code-a})]
      (is (= 502 (:status resp)))
      (is (nil? (session-token resp))))))

(deftest ^:integration ^:security accounts-are-created-only-when-allowed
  (testing "no account, no auto-create"
    (let [a (app)
          resp (sign-in! a)]
      (is (= 403 (:status resp)))
      (is (empty? @(:state (:users a))))))
  (testing "unverified email does not link"
    (let [a (app)]
      (add-user! a)
      (swap! idp assoc :claims-fn #(assoc % :email_verified false))
      (is (= 403 (:status (sign-in! a))))))
  (testing "auto-create makes a :user, never an admin"
    (let [a    (app :auto-create? true)
          resp (sign-in! a)
          [u]  (vals @(:state (:users a)))]
      (is (= 200 (:status resp)))
      (is (= {:email "ada@example.com" :name "Ada Lovelace" :role :user}
             (select-keys u [:email :name :role])))
      (is (= "ada@example.com" (authenticated-as a (session-token resp)))))))

(deftest ^:integration ^:security deactivated-accounts-cannot-sign-in
  (let [a (app)]
    (add-user! a :active false)
    (let [resp (sign-in! a)]
      (is (= 403 (:status resp)))
      (is (nil? (session-token resp))))))

(deftest ^:integration ^:security email-linking-is-opt-in-and-never-reaches-an-admin
  (testing "off: an existing account is not linked by email"
    (let [a (app :link-by-email? false)]
      (add-user! a)
      (is (= 403 (:status (sign-in! a))))))
  (testing "on, but the account is an administrator"
    (let [a     (app)
          admin (add-user! a :role :admin)
          resp  (sign-in! a)]
      (is (= 403 (:status resp)))
      (is (str/includes? (:body resp) "oidc-admin-link-refused"))
      (is (empty? (ports/find-identities-by-user (:identities a) (:id admin)))))))

(deftest ^:integration ^:security trust-email-stands-in-for-email-verified
  (testing "no email_verified claim: refused unless the provider is trusted"
    (let [a (app)]
      (add-user! a)
      (swap! idp assoc :claims-fn #(dissoc % :email_verified))
      (is (= 403 (:status (sign-in! a))))))
  (let [a (app :trust-email? true)]
    (add-user! a)
    (swap! idp assoc :claims-fn #(dissoc % :email_verified))
    (is (= "ada@example.com" (authenticated-as a (session-token (sign-in! a)))))))

(deftest ^:integration client-secret-basic-is-form-urlencoded
  (let [a (app :client-secret "s~e+c%r:et /x")]
    (add-user! a)
    (is (= "ada@example.com" (authenticated-as a (session-token (sign-in! a)))))))

(deftest ^:integration ^:security cookies-are-sealed-per-step
  (let [a (app)]
    (add-user! a :mfa-enabled true :mfa-secret (mfa/generate-totp-secret))
    (let [{:keys [location cookie]} (start! a)]
      (testing "a pending sign-in is not an MFA ticket"
        (is (= 400 (:status ((:mfa a) (request {:cookies {"oidc-mfa" {:value cookie}}
                                                :form-params {"mfa-code" "000000"}}))))))
      (let [mfa-cookie (get-in (callback! a cookie (authorize! location)) [:cookies "oidc-mfa" :value])
            {:keys [location]} (start! a)]
        (testing "and an MFA ticket is not a pending sign-in"
          (is (= 400 (:status (callback! a mfa-cookie (authorize! location))))))))))

(deftest ^:integration ^:security a-dangling-link-is-dropped
  (let [a   (app :auto-create? true)
        ada (add-user! a)]
    (is (some? (session-token (sign-in! a))))
    ;; The user row goes; the link stays behind.
    (swap! (:state (:users a)) dissoc (:id ada))
    (let [resp (sign-in! a)
          [u]  (vals @(:state (:users a)))]
      (is (= 200 (:status resp)) "resolved afresh: here, a new account")
      (is (= [(:id u)] (map :user-id (vals @(:state (:identities a)))))))))

(deftest ^:integration ^:security mfa-is-still-asked
  (let [a      (app :user-settings {:login-redirect {:user "/web/overzicht"}})
        secret (mfa/generate-totp-secret)
        ada    (add-user! a :mfa-enabled true :mfa-secret secret)
        resp   (sign-in! a)
        cookie (get-in resp [:cookies "oidc-mfa" :value])
        submit #((:mfa a) (request {:cookies {"oidc-mfa" {:value cookie}} :form-params {"mfa-code" %}}))]
    (is (= 200 (:status resp)))
    (is (= :strict (get-in resp [:cookies "oidc-mfa" :same-site])))
    (is (nil? (session-token resp)))
    (is (empty? (ports/find-identities-by-user (:identities a) (:id ada)))
        "not linked until the second factor is in")
    (let [wrong (submit "000000")]
      (is (= 401 (:status wrong)))
      (is (= 1 (:failed-login-count (ports/find-user-by-id (:users a) (:id ada))))))
    (let [ok (submit (str (otp/get-totp-token secret)))]
      (is (= "ada@example.com" (authenticated-as a (session-token ok))))
      (is (= "0;url=/web/overzicht" (get-in ok [:headers "Refresh"])) "lands per :login-redirect")
      (is (= 1 (count (ports/find-identities-by-user (:identities a) (:id ada))))))))
