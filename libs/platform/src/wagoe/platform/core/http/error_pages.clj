(ns wagoe.platform.core.http.error-pages
  "What a browser is shown for an error body: a page for a /web request, a
   toast for an htmx one. An API caller, or a script asking for JSON, keeps the
   body of `wagoe.platform.core.http.errors`."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [wagoe.platform.core.http.access :as access]
            [wagoe.platform.core.http.errors :as errors]))

(defn htmx?
  "Whether htmx sent `request` for a fragment. A history restore asks for a
   whole page."
  [request]
  (let [headers (:headers request)]
    (and (= "true" (get headers "hx-request"))
         (not= "true" (get headers "hx-history-restore-request")))))

(defn- wants-json?
  "Whether the caller asked for JSON and not HTML, as a script on a page does."
  [request]
  (let [accept (str (get-in request [:headers "accept"]))]
    (and (str/includes? accept "json")
         (not (str/includes? accept "text/html")))))

(defn page-error
  "`{:status :type :message :correlation-id}` when `response` to `request`
   carries the JSON error body and a browser should see a page instead; nil
   otherwise. A handler's own HTML is left alone."
  [request {:keys [status] :as response}]
  (when (and (number? status) (>= status 400)
             (access/web-request? request)
             (not (wants-json? request)))
    (when-let [error (errors/error-of response)]
      {:status         status
       :type           (some-> (:type error) name)
       :message        (str (or (:message error) "An error occurred"))
       :correlation-id (some-> (:correlation-id error) str)})))

(defn page-response
  "`response` with `body`, HTML, in place of the error body."
  [response body]
  (-> response
      (update :headers dissoc "content-type" "Content-Length" "content-length")
      (assoc :body body)
      (assoc-in [:headers "Content-Type"] "text/html; charset=utf-8")))

(defn toast-trigger
  "The HX-Trigger value components.js shows as an error toast."
  [message]
  ;; ASCII only: a header value carries no UTF-8
  (json/generate-string {:showToast {:type "error" :message message}}
                        {:escape-non-ascii true}))

(defn htmx-response
  "`response` for htmx: its status kept, so nothing is swapped, and the message
   as an error toast. A toast the handler already asked for wins."
  [response {:keys [message]}]
  (cond-> (page-response response (access/escape-html message))
    (nil? (get-in response [:headers "HX-Trigger"]))
    (assoc-in [:headers "HX-Trigger"] (toast-trigger message))))
