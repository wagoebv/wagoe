(ns wagoe.platform.core.http.access
  "Who may reach a route, and what a refused caller is told.

   Every route requires a signed-in user unless its route data carries
   `:public true` (BOU-568). A refusal has the shape the user module's
   `require-authenticated` interceptor answers with, so a client sees one 401
   whichever layer refused it: the one error body of
   `wagoe.platform.core.http.errors` (BOU-586). No Content-Type: muuntaja encodes a map body
   only when none is set (ZZP-120). What needs no HTTP stack lives in
   `wagoe.core.http.access` (BOU-930)."
  (:require [clojure.string :as str]
            [wagoe.core.http.access :as core]))

(defn public?
  "Whether Reitit endpoint data opts out of authentication."
  [data]
  (true? (:public data)))

(defn correlation-id
  "See `wagoe.core.http.access/correlation-id`."
  [request fresh]
  (core/correlation-id request fresh))

(defn web-request?
  "Whether `request` is for a page under /web."
  [request]
  (let [uri (str (:uri request))]
    ;; Not /webhooks: a server calling one wants the error body
    (or (= "/web" uri) (str/starts-with? uri "/web/"))))

(defn- login-redirect [request]
  {:status  302
   :headers {"Location" (str "/web/login?return-to="
                             (java.net.URLEncoder/encode (str (:uri request)) "UTF-8"))}
   :body    ""})

(defn unauthorized-response
  "401 for an API caller; a web page sends the browser to the login form,
   which returns it here afterwards."
  [message request correlation-id]
  (if (web-request? request)
    (login-redirect request)
    (core/refusal-response 401 "unauthorized" message correlation-id)))

(defn escape-html
  "`s` safe in HTML text or an attribute."
  [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"}))

(defn error-page-html
  "A bare error page, for an app that renders none of its own."
  [status message correlation-id]
  (str "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>" status
       "</title></head><body><main><h1>" status "</h1><p>" (escape-html message) "</p>"
       (when correlation-id
         (str "<p>Reference: <code>" (escape-html correlation-id) "</code></p>"))
       "<p><a href=\"/\">Home</a></p></main></body></html>"))

(defn refusal-response
  "See `wagoe.core.http.access/refusal-response`."
  [status error message correlation-id]
  (core/refusal-response status error message correlation-id))

(defn forbidden-response
  "See `wagoe.core.http.access/forbidden-response`."
  [message request correlation-id]
  (core/forbidden-response message request correlation-id))

(defn admin?
  "See `wagoe.core.http.access/admin?`."
  [request]
  (core/admin? request))

(defn platform-admin?
  "See `wagoe.core.http.access/platform-admin?`."
  [user]
  (core/platform-admin? user))

(defn same-id?
  "See `wagoe.core.http.access/same-id?`."
  [a b]
  (core/same-id? a b))

(defn in-tenant-scope?
  "See `wagoe.core.http.access/in-tenant-scope?`."
  [request]
  (core/in-tenant-scope? request))

(defn tenant-admin?
  "See `wagoe.core.http.access/tenant-admin?`."
  [request]
  (core/tenant-admin? request))
