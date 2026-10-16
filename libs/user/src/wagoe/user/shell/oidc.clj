(ns wagoe.user.shell.oidc
  "OpenID Connect sign-in: authorization code flow with PKCE.

   The pending sign-in (state, nonce, code verifier) travels in an encrypted,
   short-lived cookie rather than server state, so any replica can finish what
   another started. Discovery documents and key sets are cached per process.

   Every step returns {:ok …} or {:error {:type :oidc/… :message …}}; only a
   bad configuration throws, at boot."
  (:require [buddy.core.hash :as hash]
            [buddy.core.keys :as keys]
            [buddy.sign.jws :as jws]
            [buddy.sign.jwt :as jwt]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [wagoe.user.core.audit :as audit-core]
            [wagoe.user.core.authentication :as auth-core]
            [wagoe.user.core.oidc :as oidc]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.auth :as auth]
            [wagoe.user.shell.mfa :as mfa])
  (:import (java.nio.charset StandardCharsets)
           (java.time Instant)))

(def pending-ttl-seconds
  "How long a browser has at the provider, or at the MFA prompt."
  600)

(def ^:private cache-ttl-ms (* 60 60 1000))

(def ^:private forced-jwks-refresh-ms
  "Least time between refetches of a key set for an unknown kid, so a stream
   of forged tokens cannot turn into a stream of requests to the provider."
  30000)

(defrecord OidcClient [settings auth-service audit-repository identity-repository key cache])

;; =============================================================================
;; Sealed cookies
;; =============================================================================

(defn- now-epoch [] (.getEpochSecond (Instant/now)))

(defn seal
  "Encrypt `claims` for a cookie that expires in `ttl` seconds."
  [client claims ttl]
  (jwt/encrypt (assoc claims :exp (+ (now-epoch) ttl)) (:key client) {:alg :dir :enc :a256gcm}))

(defn unseal
  "The claims of a sealed cookie, or nil when it is absent, forged or expired."
  [client value]
  (when-not (str/blank? value)
    (try (jwt/decrypt value (:key client) {:alg :dir :enc :a256gcm})
         (catch Exception _ nil))))

(defn- random-token []
  (auth/generate-session-token))

;; =============================================================================
;; HTTP
;; =============================================================================

(defn- request-json [req]
  (try
    (let [{:keys [status body]} (http/request (merge {:throw-exceptions   false
                                                      :socket-timeout     5000
                                                      :connection-timeout 5000
                                                      :accept             :json
                                                      :as                 :string}
                                                     req))]
      (if (= 200 status)
        {:ok (json/parse-string body true)}
        {:error {:type :oidc/http-error :message "Provider answered with an error"
                 :status status :url (:url req)}}))
    (catch Exception e
      {:error {:type :oidc/http-error :message "Provider unreachable"
               :url (:url req) :cause (ex-message e)}})))

(defn- cached [client k url fetch]
  (let [hit (get-in @(:cache client) [k url])]
    (if (and hit (< (- (System/currentTimeMillis) (:at hit)) cache-ttl-ms))
      {:ok (:value hit)}
      (let [r (fetch)]
        (when-let [v (:ok r)]
          (swap! (:cache client) assoc-in [k url] {:value v :at (System/currentTimeMillis)}))
        r))))

(defn discovery
  "The provider's discovery document, cached for an hour."
  [client provider]
  (let [issuer (:issuer provider)]
    (cached client :discovery issuer
            #(let [r (request-json {:method :get
                                    :url    (str issuer "/.well-known/openid-configuration")})]
               (if (:ok r) (oidc/check-discovery (:ok r) issuer) r)))))

(defn- jwks [client uri force?]
  (let [hit (get-in @(:cache client) [:jwks uri])]
    (when (and force? hit (> (- (System/currentTimeMillis) (:at hit)) forced-jwks-refresh-ms))
      (swap! (:cache client) update :jwks dissoc uri))
    (cached client :jwks uri #(request-json {:method :get :url uri}))))

;; =============================================================================
;; Id token
;; =============================================================================

(defn- verify-signature
  "The id token's claims, once its signature checks out against the provider's
   key set. Refetches the set once for a kid it does not know: key rotation."
  [client discovery-doc id-token]
  (let [header (try (jws/decode-header id-token) (catch Exception _ nil))
        uri    (:jwks_uri discovery-doc)
        pick   (fn [force?]
                 (let [ks (jwks client uri force?)]
                   (if (:error ks) ks (oidc/select-jwk (:ok ks) header))))
        jwk    (when header
                 (let [r (pick false)]
                   (if (= :oidc/unknown-key (get-in r [:error :type])) (pick true) r)))]
    (cond
      (nil? header) {:error {:type :oidc/invalid-token :message "Id token rejected" :reason :malformed}}
      (:error jwk)  jwk
      :else
      (try
        (let [alg     (keyword (str/lower-case (name (:alg header))))
              payload (jws/unsign id-token (keys/jwk->public-key (:ok jwk)) {:alg alg})]
          {:ok (json/parse-string (String. ^bytes payload StandardCharsets/UTF_8) true)})
        (catch Exception _
          {:error {:type :oidc/invalid-token :message "Id token rejected" :reason :signature}})))))

(defn- exchange-code [client provider discovery-doc code verifier]
  (let [{:keys [client-id client-secret token-endpoint-auth]} provider
        basic? (and client-secret (not= :post token-endpoint-auth))
        r      (request-json
                (cond-> {:method      :post
                         :url         (:token_endpoint discovery-doc)
                         :form-params (cond-> {:grant_type    "authorization_code"
                                               :code          code
                                               :redirect_uri  (oidc/redirect-uri (get-in client [:settings :base-url])
                                                                                 (:id provider))
                                               :code_verifier verifier}
                                        (not basic?) (assoc :client_id client-id)
                                        (and client-secret (not basic?)) (assoc :client_secret client-secret))}
                  basic? (assoc :basic-auth [client-id client-secret])))]
    (cond
      (:error r)                    (assoc-in r [:error :type] :oidc/token-exchange-failed)
      (str/blank? (:id_token (:ok r))) {:error {:type :oidc/token-exchange-failed
                                                :message "Token response has no id_token"}}
      :else                         {:ok (:id_token (:ok r))})))

;; =============================================================================
;; Signing in
;; =============================================================================

(defn provider [client provider-id]
  (get-in client [:settings :providers provider-id]))

(defn start
  "Begin a sign-in. Returns {:ok {:location url :cookie sealed-pending}}."
  [client provider-id return-to]
  (if-let [p (provider client provider-id)]
    (let [d (discovery client p)]
      (if (:error d)
        d
        (let [pending {:provider      provider-id
                       :state         (random-token)
                       :nonce         (random-token)
                       :code-verifier (random-token)
                       :return-to     return-to}]
          {:ok {:location (oidc/authorization-url
                           (:ok d) p
                           (assoc pending :redirect-uri
                                  (oidc/redirect-uri (get-in client [:settings :base-url]) provider-id)))
                :cookie   (seal client pending pending-ttl-seconds)}})))
    {:error {:type :not-found :message "Unknown sign-in provider"}}))

(defn- audit! [client user login-context provider-id success? message]
  (when (and user (:audit-repository client))
    (ports/create-audit-log (:audit-repository client)
                            (assoc (audit-core/login-audit-entry (:id user) (:email user)
                                                                 (:ip-address login-context)
                                                                 (:user-agent login-context)
                                                                 success? message)
                                   :metadata {:method "oidc" :provider provider-id}))))

(defn- finish!
  "Record the login, link the identity if this sign-in is what links it, and
   open a session — the same session a password login gets."
  [client user {:keys [provider-id identity link?]} login-context]
  (let [{:keys [user-repository session-repository]} (:auth-service client)
        repo    (:identity-repository client)
        now     (Instant/now)
        risk    (auth-core/analyze-login-risk user login-context
                                              (ports/find-sessions-by-user session-repository (:id user))
                                              now)]
    (if link?
      (ports/link-identity repo (assoc identity :user-id (:id user)))
      (ports/touch-identity repo (:issuer identity) (:subject identity) now))
    (ports/update-user user-repository (merge user (auth-core/prepare-successful-login-updates user now)))
    (let [{:keys [session]} (auth/open-session! (:auth-service client) user risk login-context now)]
      (audit! client user login-context provider-id true nil)
      (log/info "OIDC sign-in" {:user-id (:id user) :provider provider-id :linked? (boolean link?)})
      {:ok {:outcome :signed-in
            :user    (dissoc user :password-hash :mfa-secret :mfa-backup-codes :mfa-backup-codes-used)
            :session session}})))

(defn- unusable-password-hash
  "A created account signs in through its provider; nobody knows this password."
  []
  (auth/hash-password (random-token)))

(defn- account
  "The account for verified `claims`, created when the provider allows it.
   Returns {:ok {:user u :link? bool}} or an error."
  [client p claims login-context]
  (let [{:keys [user-repository]} (:auth-service client)
        repo     (:identity-repository client)
        identity (ports/find-identity repo (:iss claims) (:sub claims))
        linked   (when identity (ports/find-user-by-id user-repository (:user-id identity)))
        email    (oidc/verified-email claims)]
    (if (and identity (nil? linked))
      ;; Linked to an account that has since been deleted.
      {:error {:type :oidc/account-unavailable :message "Account is not available"}}
      (let [decision (oidc/resolve-account
                      {:linked-user linked
                       :email-user  (when (and email (nil? linked))
                                      (ports/find-user-by-email user-repository email))
                       :claims      claims
                       :provider    p})]
        (case (:action decision)
          :login {:ok {:user (:user decision) :link? false}}
          :link  {:ok {:user (:user decision) :link? true}}
          :create
          (let [user (ports/create-user user-repository
                                        (assoc (:user-data decision)
                                               :password-hash (unusable-password-hash)))]
            (when-let [audit-repo (:audit-repository client)]
              (ports/create-audit-log audit-repo
                                      (assoc (audit-core/create-user-audit-entry
                                              nil "system" user
                                              (:ip-address login-context) (:user-agent login-context))
                                             :metadata {:method "oidc" :provider (:id p)})))
            {:ok {:user user :link? true}})
          decision)))))

(defn- sign-in-user
  "Apply the account rules a password login applies, then either open the
   session or ask for the second factor."
  [client user sign-in login-context]
  (let [allowed (auth-core/should-allow-login-attempt? user nil (Instant/now))]
    (cond
      (not (:allowed? allowed))
      (do (audit! client user login-context (:provider-id sign-in) false (:reason allowed))
          {:error {:type :oidc/account-unavailable :message (:reason allowed)}})

      ;; The provider vouches for the person; an account that turned on a
      ;; second factor here still asks for it.
      (:mfa-enabled user)
      {:ok {:outcome :mfa-required
            :user    user
            :cookie  (seal client (assoc sign-in :user-id (str (:id user))) pending-ttl-seconds)}}

      :else
      (finish! client user sign-in login-context))))

(defn callback
  "Finish a sign-in from the provider's redirect.

   `params` are the callback's query params, `pending-cookie` the value start
   set. Returns {:ok {:outcome :signed-in :user … :session … :return-to …}},
   {:ok {:outcome :mfa-required :cookie …}}, or an error."
  [client provider-id params pending-cookie login-context]
  (let [p       (provider client provider-id)
        checked (if p
                  (oidc/check-callback (unseal client pending-cookie) provider-id params (now-epoch))
                  {:error {:type :not-found :message "Unknown sign-in provider"}})]
    (if (:error checked)
      checked
      (let [{:keys [code pending]} (:ok checked)
            d      (discovery client p)
            token  (if (:error d) d (exchange-code client p (:ok d) code (:code-verifier pending)))
            signed (if (:error token) token (verify-signature client (:ok d) (:ok token)))
            claims (if (:error signed)
                     signed
                     (oidc/validate-claims (:ok signed)
                                           {:issuer    (:issuer p)
                                            :client-id (:client-id p)
                                            :nonce     (:nonce pending)
                                            :now-epoch (now-epoch)
                                            :skew-seconds (:clock-skew-seconds p)}))
            acct   (if (:error claims) claims (account client p (:ok claims) login-context))]
        (if (:error acct)
          acct
          (let [c (:ok claims)
                r (sign-in-user client (get-in acct [:ok :user])
                                {:provider-id provider-id
                                 :link?       (get-in acct [:ok :link?])
                                 :identity    {:provider provider-id
                                               :issuer   (:iss c)
                                               :subject  (:sub c)
                                               :email    (:email c)}
                                 :return-to   (:return-to pending)}
                                login-context)]
            (cond-> r (:ok r) (assoc-in [:ok :return-to] (:return-to pending)))))))))

(defn complete-mfa
  "Finish a sign-in that stopped for the second factor."
  [client mfa-cookie code login-context]
  (let [sign-in (unseal client mfa-cookie)
        {:keys [user-repository mfa-service]} (:auth-service client)
        user    (some->> (:user-id sign-in) parse-uuid (ports/find-user-by-id user-repository))
        allowed (when user (auth-core/should-allow-login-attempt? user nil (Instant/now)))]
    (cond
      (nil? user)
      {:error {:type :oidc/state-expired :message "Sign-in expired"}}

      (not (:allowed? allowed))
      {:error {:type :oidc/account-unavailable :message (:reason allowed)}}

      :else
      (let [check (when-not (str/blank? code) (mfa/verify-mfa-code mfa-service user code))]
        (if (:valid? check)
          (let [user (if (:used-backup-code? check)
                       (let [u (merge user (:updates check))] (ports/update-user user-repository u) u)
                       user)]
            (assoc-in (finish! client user sign-in login-context) [:ok :return-to] (:return-to sign-in)))
          ;; Wrong codes count towards the lockout, as they do on the password form.
          (do (ports/update-user user-repository
                                 (merge user (auth-core/calculate-failed-login-consequences
                                              user (get-in client [:auth-service :auth-config]) (Instant/now))))
              (audit! client user login-context (:provider-id sign-in) false "Invalid MFA code")
              {:error {:type :oidc/mfa-failed :message "Invalid MFA code"}}))))))

;; =============================================================================
;; Construction
;; =============================================================================

(defn create-oidc-client
  "Build the client. Throws :configuration-error on bad settings or no secret.

   `secret` keys the sealed cookies; the wiring passes JWT_SECRET unless the
   settings carry :state-secret."
  [{:keys [settings secret auth-service audit-repository identity-repository]}]
  (let [r (oidc/normalize-settings settings)]
    (when (:error r)
      (throw (ex-info (get-in r [:error :message]) (:error r))))
    (when (< (count (str secret)) 32)
      (throw (ex-info "OIDC needs a secret of at least 32 characters for its cookies"
                      {:type :configuration-error})))
    (->OidcClient (:ok r) auth-service audit-repository identity-repository
                  (hash/sha256 (str "wagoe.user.oidc|" secret))
                  (atom {}))))
