(ns wagoe.user.core.oidc
  "OpenID Connect sign-in decisions: provider config, the authorization URL,
   state and id-token claim checks, and which account a sign-in belongs to.

   Pure. Fetching documents, verifying signatures and writing sessions is
   wagoe.user.shell.oidc. Failures are values: {:error {:type … :message …}}."
  (:require [buddy.core.bytes :as bytes]
            [buddy.core.codecs :as codecs]
            [buddy.core.hash :as hash]
            [clojure.string :as str])
  (:import (java.net URI URLEncoder)))

(def default-scopes ["openid" "email" "profile"])

(def default-clock-skew-seconds
  "Tolerance on exp and iat for clocks that disagree with the provider's."
  60)

(def signing-algs
  "Asymmetric algorithms an id token may be signed with. No HS* (the client
   secret is not a signing key here) and no `none`."
  #{"RS256" "RS384" "RS512" "PS256" "PS384" "PS512" "ES256" "ES384" "ES512"})

(defn- error [type message & {:as extra}]
  {:error (merge {:type type :message message} extra)})

(defn- blank-string? [s]
  (or (not (string? s)) (str/blank? s)))

(defn- secure-url?
  "https, or http on a loopback host — a local test provider."
  [s]
  (try
    (let [uri (URI. s)]
      (or (= "https" (.getScheme uri))
          (and (= "http" (.getScheme uri))
               (contains? #{"localhost" "127.0.0.1"} (.getHost uri)))))
    (catch Exception _ false)))

;; =============================================================================
;; Configuration
;; =============================================================================

(defn normalize-provider
  "Check one provider's settings and fill in defaults.

   Returns {:ok provider} or an error of type :configuration-error."
  [id {:keys [issuer client-id scopes default-role] :as settings}]
  (let [role (or default-role :user)]
    (cond
      (blank-string? issuer)
      (error :configuration-error (str "OIDC provider " id " has no :issuer"))

      (not (secure-url? issuer))
      (error :configuration-error (str "OIDC provider " id " :issuer must be https"))

      (blank-string? client-id)
      (error :configuration-error (str "OIDC provider " id " has no :client-id"))

      (and scopes (not (some #{"openid"} scopes)))
      (error :configuration-error (str "OIDC provider " id " :scopes must include \"openid\""))

      ;; An identity provider must not mint administrators.
      (not (contains? #{:user :viewer} role))
      (error :configuration-error (str "OIDC provider " id " :default-role must be :user or :viewer"))

      :else
      {:ok (merge {:label          (str/capitalize (name id))
                   :link-by-email? true
                   :auto-create?   false}
                  settings
                  {:id           (name id)
                   :issuer       (str/replace issuer #"/+$" "")
                   :scopes       (vec (or scopes default-scopes))
                   :default-role role})})))

(defn normalize-settings
  "The `:oidc` block of `:wagoe/user`, checked.

   Returns {:ok {:base-url … :providers {\"google\" provider}}} or the first
   :configuration-error."
  [{:keys [base-url providers] :as settings}]
  (cond
    (empty? providers)
    {:ok (assoc settings :providers {})}

    (blank-string? base-url)
    (error :configuration-error
           "OIDC needs :base-url, the public URL the provider redirects back to")

    (not (secure-url? base-url))
    (error :configuration-error "OIDC :base-url must be https")

    :else
    (reduce (fn [acc [id p]]
              (let [r (normalize-provider id p)]
                (if (:error r)
                  (reduced r)
                  (assoc-in acc [:ok :providers (name id)] (:ok r)))))
            {:ok (assoc settings
                        :base-url (str/replace base-url #"/+$" "")
                        :providers {})}
            providers)))

(defn login-buttons
  "The providers to offer on the login page, as [{:id :label}] in name order."
  [providers]
  (->> providers
       (map (fn [[id p]] {:id (name id) :label (or (:label p) (str/capitalize (name id)))}))
       (sort-by :id)
       vec))

(defn redirect-uri [base-url provider-id]
  (str base-url "/web/auth/oidc/" provider-id "/callback"))

;; =============================================================================
;; Discovery
;; =============================================================================

(defn check-discovery
  "The discovery document, if it is for `issuer` and names the endpoints the
   code flow needs. A document claiming another issuer is refused (OIDC
   Discovery §4.3)."
  [doc issuer]
  (cond
    (not (map? doc))
    (error :oidc/discovery-failed "Discovery document is not a JSON object")

    (not= issuer (some-> (:issuer doc) (str/replace #"/+$" "")))
    (error :oidc/discovery-failed "Discovery document names another issuer"
           :expected issuer :actual (:issuer doc))

    (some #(not (secure-url? (get doc %))) [:authorization_endpoint :token_endpoint :jwks_uri])
    (error :oidc/discovery-failed "Discovery document lacks a secure endpoint")

    :else {:ok doc}))

;; =============================================================================
;; Authorization request
;; =============================================================================

(defn pkce-challenge
  "S256 code challenge for `verifier` (RFC 7636 §4.2)."
  [verifier]
  (codecs/bytes->b64-str (hash/sha256 ^String verifier) true))

(defn- query-string [params]
  (->> params
       (map (fn [[k v]] (str (URLEncoder/encode (name k) "UTF-8") "=" (URLEncoder/encode (str v) "UTF-8"))))
       (str/join "&")))

(defn authorization-url
  "Where to send the browser to start the code flow."
  [discovery provider {:keys [redirect-uri state nonce code-verifier]}]
  (let [endpoint (:authorization_endpoint discovery)]
    (str endpoint
         (if (str/includes? endpoint "?") "&" "?")
         (query-string (array-map :response_type "code"
                                  :client_id (:client-id provider)
                                  :redirect_uri redirect-uri
                                  :scope (str/join " " (:scopes provider))
                                  :state state
                                  :nonce nonce
                                  :code_challenge (pkce-challenge code-verifier)
                                  :code_challenge_method "S256")))))

;; =============================================================================
;; Callback
;; =============================================================================

(defn- same-secret? [a b]
  (and (string? a) (string? b)
       (bytes/equals? (codecs/str->bytes a) (codecs/str->bytes b))))

(defn check-callback
  "Match the callback's query params against the pending sign-in.

   `pending` is what start stored: {:provider :state :nonce :code-verifier :exp}.
   Returns {:ok {:code … :pending …}} or an :oidc/* error."
  [pending provider-id params now-epoch]
  (cond
    (nil? pending)
    (error :oidc/state-mismatch "No sign-in in progress")

    (not= provider-id (:provider pending))
    (error :oidc/state-mismatch "Sign-in was started for another provider")

    (not (and (number? (:exp pending)) (< now-epoch (:exp pending))))
    (error :oidc/state-expired "Sign-in took too long")

    (not (same-secret? (:state pending) (get params "state")))
    (error :oidc/state-mismatch "State does not match")

    (get params "error")
    (error :oidc/provider-error "Provider refused the sign-in"
           :provider-error (get params "error"))

    (blank-string? (get params "code"))
    (error :oidc/state-mismatch "Callback carries no code")

    :else {:ok {:code (get params "code") :pending pending}}))

(defn select-jwk
  "The key in `jwks` that the id token's `header` names.

   The header's alg must be asymmetric; a key's own alg, when it states one,
   must agree. Returns {:ok jwk} or {:error {:type :oidc/unknown-key …}}, the
   one a caller answers by refetching the key set."
  [jwks {:keys [alg kid]}]
  (let [alg (some-> alg name str/upper-case)]
    (if-not (contains? signing-algs alg)
      (error :oidc/invalid-token "Unsupported id token algorithm" :alg alg)
      (let [kty      (if (str/starts-with? alg "ES") "EC" "RSA")
            usable   (filter #(and (= kty (:kty %))
                                   (contains? #{nil "sig"} (:use %))
                                   (contains? #{nil alg} (:alg %)))
                             (:keys jwks))
            matching (if kid (filter #(= kid (:kid %)) usable) usable)]
        (if (= 1 (count matching))
          {:ok (first matching)}
          (error :oidc/unknown-key "No single signing key matches the id token" :kid kid))))))

(defn- audience-ok? [{:keys [aud azp]} client-id]
  (let [auds (if (string? aud) [aud] (vec aud))]
    (and (some #{client-id} auds)
         ;; Several audiences: the token must have been issued to us (Core §3.1.3.7).
         (or (= 1 (count auds)) (= client-id azp))
         (or (nil? azp) (= client-id azp)))))

(defn validate-claims
  "Check a signature-verified id token's claims (OIDC Core §3.1.3.7).

   opts: {:issuer :client-id :nonce :now-epoch :skew-seconds}.
   Returns {:ok claims} or {:error {:type :oidc/invalid-token :reason …}}."
  [claims {:keys [issuer client-id nonce now-epoch skew-seconds]}]
  (let [skew (or skew-seconds default-clock-skew-seconds)
        bad  (fn [reason] (error :oidc/invalid-token "Id token rejected" :reason reason))]
    (cond
      (not (map? claims))                           (bad :malformed)
      (not= issuer (:iss claims))                   (bad :issuer)
      (not (audience-ok? claims client-id))         (bad :audience)
      (not (number? (:exp claims)))                 (bad :expired)
      (<= (+ (:exp claims) skew) now-epoch)         (bad :expired)
      (not (number? (:iat claims)))                 (bad :issued-at)
      (> (:iat claims) (+ now-epoch skew))          (bad :issued-at)
      (not (same-secret? nonce (:nonce claims)))    (bad :nonce)
      (blank-string? (:sub claims))                 (bad :subject)
      :else                                         {:ok claims})))

;; =============================================================================
;; Account linking
;; =============================================================================

(defn verified-email
  "The token's email, lower-cased, when the provider says it is verified."
  [claims]
  (let [v (:email_verified claims)]
    (when (and (not (blank-string? (:email claims)))
               (or (true? v) (= "true" v)))
      (str/lower-case (:email claims)))))

(defn- display-name [claims email]
  (let [n (str/trim (str (or (:name claims)
                             (str (:given_name claims) " " (:family_name claims)))))
        n (if (str/blank? n) (first (str/split email #"@")) n)]
    (subs n 0 (min 255 (count n)))))

(defn resolve-account
  "Which account this sign-in is.

   In order: the account already linked to (iss, sub); the account with the
   token's verified email, when the provider allows linking by email and that
   account has no link at this issuer yet — a second subject for the same email
   is the address changing hands; a new account, when it allows creating one.
   Nothing else — an unverified email never selects or creates an account.

   Returns one of
     {:action :login  :user u}
     {:action :link   :user u}
     {:action :create :user-data {…}}
     {:error {:type :oidc/no-account …}}"
  [{:keys [linked-user email-user email-user-linked? claims provider]}]
  (let [email (verified-email claims)]
    (cond
      linked-user
      {:action :login :user linked-user}

      (and email-user email (:link-by-email? provider) (not email-user-linked?))
      {:action :link :user email-user}

      (and email (nil? email-user) (:auto-create? provider))
      {:action :create
       :user-data {:email  email
                   :name   (display-name claims email)
                   :role   (:default-role provider)
                   :active true}}

      :else
      (error :oidc/no-account "No account matches this sign-in"
             :email-verified? (some? email)))))
