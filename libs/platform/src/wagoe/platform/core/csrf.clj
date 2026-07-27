(ns wagoe.platform.core.csrf
  "Pure CSRF token functions — synchronizer token bound to a session.

   The token is a signed double-submit value:

       token = base64url(nonce) \".\" base64url(HMAC-SHA256(secret, nonce || binding))

   The `binding` is the value the token is tied to: the user's session token for
   authenticated requests, or a per-request pre-session cookie value for the login
   form (which has no session yet). A token is only valid when presented together
   with the same binding it was signed against, which is what defeats CSRF: an
   attacker can forge a cross-site request but cannot read the victim's binding to
   produce a matching token.

   Functional Core: these functions are pure and deterministic. The CSPRNG nonce
   and the secret are produced in the shell and passed in as arguments — nothing
   here performs I/O or reads ambient state.

   Safety parity with ring-anti-forgery: validation uses buddy's `mac/verify`,
   which performs a constant-time comparison of the recomputed HMAC, so token
   checks do not leak timing information.

   Enforcement is opt-in at the interceptor level (default off); see
   `wagoe.platform.shell.http.interceptors/http-csrf-protection`. Emit the token
   with `hidden-field` (server forms) or `hx-headers` (HTMX elements), or via the
   <meta name=\"csrf-token\"> tag + the ui-style init.js htmx:configRequest listener."
  (:require [buddy.core.mac :as mac]
            [buddy.core.bytes :as bytes]
            [buddy.core.codecs :as codecs]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def field-name
  "Hidden form-field / form-param name carrying the CSRF token. Mirrors the
   ring-anti-forgery convention so existing tooling and test helpers interoperate."
  "__anti-forgery-token")

(def header-name
  "Request header carrying the CSRF token for HTMX / fetch requests."
  "x-csrf-token")

(def ^:dynamic *token*
  "The CSRF token for the request currently being handled, or nil. The HTTP
   interceptor binds this around handler execution (where Hiccup is rendered to a
   string), so page layouts and form helpers can emit the token without threading
   it through every handler and component. Mirrors ring-anti-forgery's
   *anti-forgery-token*."
  nil)

(defn current-token
  "The CSRF token bound for the current request, or nil outside a request."
  []
  *token*)

(def ^:private token-separator ".")

(def ^:private mac-algorithm {:alg :hmac+sha256})

(defn- binding-bytes
  "Coerce the binding string to bytes. nil binding becomes an empty byte array
   so pre-session tokens (no binding) still sign deterministically."
  [binding]
  (codecs/str->bytes (or binding "")))

(defn- mac-bytes
  "HMAC-SHA256 over (nonce || binding) keyed by secret."
  [secret nonce-bytes binding]
  (mac/hash (bytes/concat nonce-bytes (binding-bytes binding))
            (assoc mac-algorithm :key secret)))

(defn generate-token
  "Build a CSRF token bound to `binding`, signed with `secret`.

   Args:
     secret      - signing key (String or bytes); supplied by the shell from config
     binding     - value the token is tied to (session token, or login cookie nonce);
                   may be nil for a pre-session token
     nonce-bytes - random bytes from a CSPRNG; supplied by the shell

   Returns the token String `base64url(nonce).base64url(mac)`."
  [secret binding nonce-bytes]
  (str (codecs/bytes->b64-str nonce-bytes true)
       token-separator
       (codecs/bytes->b64-str (mac-bytes secret nonce-bytes binding) true)))

(defn valid-token?
  "True when `submitted` is a well-formed token whose MAC matches the one
   recomputed from its nonce and the supplied `binding` under `secret`.

   Returns false (never throws) for nil, blank, or malformed input, and for any
   token signed against a different binding or secret. The MAC comparison is
   constant-time (buddy `mac/verify`)."
  [secret binding submitted]
  (boolean
   (when (and submitted (string? submitted) (not (str/blank? submitted)))
     (let [parts (str/split submitted (re-pattern (java.util.regex.Pattern/quote token-separator)))]
       (when (= 2 (count parts))
         (let [[nonce-b64 mac-b64] parts]
           (try
             (let [nonce-bytes (codecs/b64->bytes nonce-b64 true)
                   mac         (codecs/b64->bytes mac-b64 true)]
               (mac/verify (bytes/concat nonce-bytes (binding-bytes binding))
                           mac
                           (assoc mac-algorithm :key secret)))
             ;; Malformed base64 / decode failure → invalid, not an error.
             (catch Exception _ false))))))))

(defn extract-token
  "Pull the submitted CSRF token from a Ring request, checking, in order:
     1. form param      __anti-forgery-token  (string or keyword key)
     2. multipart param __anti-forgery-token  (file-upload forms)
     3. header          x-csrf-token
   Returns the token String or nil. Pure map access — no I/O."
  [request]
  (or (get-in request [:form-params field-name])
      (get-in request [:form-params (keyword field-name)])
      (get-in request [:params (keyword field-name)])
      (get-in request [:multipart-params field-name])
      (get-in request [:multipart-params (keyword field-name)])
      (get-in request [:headers header-name])))

(defn hidden-field
  "Hiccup hidden input embedding the CSRF token in a plain (non-HTMX) form.
   HTMX requests instead pick up the token from the <meta> tag via the global
   htmx:configRequest listener, so they do not need this field.

   The 0-arity reads the token bound for the current request (*token*); the
   1-arity takes an explicit token. Returns nil when the token is nil, so callers
   can splice the result into a form unconditionally."
  ([] (hidden-field *token*))
  ([token]
   (when token
     [:input {:type "hidden" :name field-name :value token}])))

(defn hx-headers
  "HTMX attribute fragment carrying the CSRF token, for elements that should send
   it without relying on the global <meta>/init.js listener. Merge into an
   element's attribute map (e.g. on <body>) so all inherited hx-* requests include
   the header: [:body (merge attrs (hx-headers)) ...].

   The 0-arity reads the token bound for the current request (*token*); the 1-arity
   takes an explicit token. Returns nil when the token is nil, so callers can merge
   the result unconditionally. The header key uses `header-name` (\"x-csrf-token\");
   Ring lowercases inbound header names, so the interceptor's `extract-token` reads
   it consistently."
  ([] (hx-headers *token*))
  ([token]
   (when token
     {:hx-headers (json/generate-string {header-name token})})))
