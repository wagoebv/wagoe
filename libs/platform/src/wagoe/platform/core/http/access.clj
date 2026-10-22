(ns wagoe.platform.core.http.access
  "Who may reach a route, and what a refused caller is told.

   Every route requires a signed-in user unless its route data carries
   `:public true` (BOU-568). A refusal has the shape the user module's
   `require-authenticated` interceptor answers with, so a client sees one 401
   whichever layer refused it: the one error body of
   `wagoe.platform.core.http.errors` (BOU-586). No Content-Type: muuntaja encodes a map body
   only when none is set (ZZP-120)."
  (:require [clojure.string :as str]
            [wagoe.platform.core.http.errors :as errors]))

(defn public?
  "Whether Reitit endpoint data opts out of authentication."
  [data]
  (true? (:public data)))

(defn correlation-id
  "The caller's X-Correlation-ID, or `fresh` when it sent none."
  [request fresh]
  (or (get-in request [:headers "x-correlation-id"]) fresh))

(defn web-request?
  "Whether `request` is for a page under /web."
  [request]
  (str/starts-with? (str (:uri request)) "/web"))

(defn- login-redirect [request]
  {:status  302
   :headers {"Location" (str "/web/login?return-to="
                             (java.net.URLEncoder/encode (str (:uri request)) "UTF-8"))}
   :body    ""})

(defn- error-response [status error message correlation-id]
  (errors/response status error message {:correlation-id correlation-id}))

(defn unauthorized-response
  "401 for an API caller; a web page sends the browser to the login form,
   which returns it here afterwards."
  [message request correlation-id]
  (if (web-request? request)
    (login-redirect request)
    (error-response 401 "unauthorized" message correlation-id)))

(defn forbidden-response
  "403 for an API caller. A web page redirects to the login form, as it always
   has, so a user can sign in as someone who is allowed."
  [message request correlation-id]
  (if (web-request? request)
    (login-redirect request)
    (error-response 403 "forbidden" message correlation-id)))

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

(defn- error-page [status message correlation-id]
  {:status  status
   :headers (cond-> {"Content-Type" "text/html; charset=utf-8"}
              correlation-id (assoc "X-Correlation-ID" (str correlation-id)))
   :body    (error-page-html status message correlation-id)})

(defn refusal-response
  "`status` for a signed-in caller who may not do this: the error body for an
   API caller, a page for a web one. Not the login redirect, which would send
   them back here."
  [status error message request correlation-id]
  (if (web-request? request)
    (error-page status message correlation-id)
    (error-response status error message correlation-id)))

(defn admin?
  "Whether the signed-in user has the global admin role."
  [request]
  (= :admin (some-> (get-in request [:user :role]) keyword)))

(defn platform-admin?
  "Whether `user` is an admin outside any tenant, whom platform-wide pages such
   as the audit trail admit. The role may be a string in an older session."
  [user]
  (and (= :admin (some-> (:role user) keyword))
       (nil? (:tenant-id user))))

(defn same-id?
  "Whether `a` names the same id as `b`, a UUID or its string; never for nil `a`."
  [a b]
  (and (some? a) (= (str a) (str b))))

(defn in-tenant-scope?
  "Whether the caller may use the schema the request runs in. With a tenant
   resolved: a member of it, a user of it, or a platform admin. Without one,
   the public schema: only a user of no tenant."
  [request]
  (let [user      (:user request)
        tenant-id (get-in request [:tenant :id])]
    (if tenant-id
      (boolean (or (:tenant-membership request)
                   (same-id? (:tenant-id user) tenant-id)
                   (platform-admin? user)))
      (nil? (:tenant-id user)))))

(defn tenant-admin?
  "Whether the caller is an admin where the request runs. In a tenant: a
   platform admin, an admin by membership of it, or an admin user of it; an
   admin of another tenant is not one here. Without a tenant, `admin?`."
  [request]
  (let [user      (:user request)
        tenant-id (get-in request [:tenant :id])]
    (if tenant-id
      (or (platform-admin? user)
          (= :admin (some-> request :tenant-membership :role keyword))
          (and (admin? request) (same-id? (:tenant-id user) tenant-id)))
      (admin? request))))
