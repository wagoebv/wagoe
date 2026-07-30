(ns wagoe.e2e.helpers.users
  "API-level helpers for e2e tests: login, register, MFA enable/disable.
   Uses clj-http directly (not spel) because the e2e suite needs to inspect
   Set-Cookie headers and make fine-grained assertions on status codes."
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [wagoe.e2e.helpers.totp :as totp]
            [wagoe.e2e.helpers.reset :as reset]))

(defn- api-post
  ([path body] (api-post path body nil))
  ([path body {:keys [cookie]}]
   (http/post (str (reset/default-base-url) path)
              (cond-> {:content-type     :json
                       :accept           :json
                       :body             (json/generate-string body)
                       :throw-exceptions false
                       :as               :json}
                cookie (assoc-in [:headers "Cookie"] (str "session-token=" cookie))))))

(defn- api-get
  ([path] (api-get path nil))
  ([path {:keys [cookie]}]
   (http/get (str (reset/default-base-url) path)
             (cond-> {:accept           :json
                      :throw-exceptions false
                      :as               :json}
               cookie (assoc-in [:headers "Cookie"] (str "session-token=" cookie))))))

(defn login
  "POST /api/v1/auth/login — returns the full ring response including headers."
  [{:keys [email password]}]
  (api-post "/api/v1/auth/login" {:email email :password password}))

(defn- web-post
  "POST to a /web endpoint with form-encoded body.
   Returns the raw response (no JSON parsing, may be HTML or redirect).
   Uses a raw HTTP connection to preserve Set-Cookie headers that clj-http
   would otherwise consume into its cookie store."
  [path form-params]
  (http/post (str (reset/default-base-url) path)
             {:form-params       form-params
              :throw-exceptions  false
              :redirect-strategy :none
              :decode-cookies    false}))

(defn- api-delete
  "DELETE to an API endpoint. Returns full response with JSON body."
  ([path] (api-delete path nil))
  ([path {:keys [cookie]}]
   (http/delete (str (reset/default-base-url) path)
                (cond-> {:accept           :json
                         :throw-exceptions false
                         :as               :json}
                  cookie (assoc-in [:headers "Cookie"] (str "session-token=" cookie))))))

(defn register
  "POST /web/register (form-encoded) — returns the full ring response.
   Note: there is no JSON API register endpoint; registration is web-only."
  [{:keys [email password name]}]
  (web-post "/web/register" {:email email :password password :name name}))

(defn create-session
  "POST /api/v1/sessions — creates a session via JSON API. Returns full response."
  [{:keys [email password]}]
  (api-post "/api/v1/sessions" {:email email :password password}))

(defn validate-session
  "GET /api/v1/sessions/:token — validates a session by token. Returns full response.
   URL-encodes the token to handle any special characters."
  [token]
  (api-get (str "/api/v1/sessions/" (java.net.URLEncoder/encode (str token) "UTF-8"))))

(defn invalidate-session
  "DELETE /api/v1/sessions/:token — revokes a session by token. Returns full response.
   URL-encodes the token to handle any special characters."
  [token]
  (api-delete (str "/api/v1/sessions/" (java.net.URLEncoder/encode (str token) "UTF-8"))))

(defn enable-mfa!
  "Runs the two-step MFA enable flow (setup → enable with TOTP) using the
   provided session-token. Returns the setup result `{:secret :backupCodes ...}`
   so tests can generate fresh codes or verify backup codes."
  [session-token]
  (let [setup-resp  (api-post "/api/v1/auth/mfa/setup" {} {:cookie session-token})
        _           (when-not (= 200 (:status setup-resp))
                      (throw (ex-info "mfa/setup failed" {:resp setup-resp})))
        setup       (:body setup-resp)
        code        (totp/current-code (:secret setup))
        enable-resp (api-post "/api/v1/auth/mfa/enable"
                              {:secret           (:secret setup)
                               :backupCodes      (:backupCodes setup)
                               :verificationCode code}
                              {:cookie session-token})]
    (when-not (= 200 (:status enable-resp))
      (throw (ex-info "mfa/enable failed" {:resp enable-resp})))
    setup))

(defn disable-mfa!
  "POST /api/v1/auth/mfa/disable — no body."
  [session-token]
  (api-post "/api/v1/auth/mfa/disable" {} {:cookie session-token}))

(defn mfa-status
  "GET /api/v1/auth/mfa/status — returns response with body."
  [session-token]
  (api-get "/api/v1/auth/mfa/status" {:cookie session-token}))
