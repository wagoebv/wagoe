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

(defn- web-request? [request]
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

(defn admin?
  "Whether the signed-in user has the global admin role."
  [request]
  (= :admin (some-> (get-in request [:user :role]) keyword)))
