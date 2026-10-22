(ns wagoe.platform.core.http.error-pages
  "What a browser is shown for an error body: a page for a /web request, a
   toast for an htmx one. An API caller, or a script asking for JSON, keeps the
   body of `wagoe.platform.core.http.errors`."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [wagoe.platform.core.http.access :as access]))

(defn htmx?
  "Whether htmx sent `request`."
  [request]
  (= "true" (get-in request [:headers "hx-request"])))

(defn- wants-json?
  "Whether the caller asked for JSON and not HTML, as a script on a page does."
  [request]
  (let [accept (str (get-in request [:headers "accept"]))]
    (and (str/includes? accept "json")
         (not (str/includes? accept "text/html")))))

(defn page-error
  "`{:status :type :message :correlation-id}` when `response` to `request`
   carries the JSON error body and a browser should see a page instead; nil
   otherwise. A handler's own HTML is left alone. `correlation-id` is used when
   the body has none."
  [request {:keys [status body]} correlation-id]
  (let [error (when (map? body) (:error body))]
    (when (and (access/web-request? request)
               (number? status) (>= status 400)
               (map? error)
               (not (wants-json? request)))
      {:status         status
       :type           (some-> (:type error) name)
       :message        (str (or (:message error) "An error occurred"))
       :correlation-id (some-> (or (:correlation-id error) correlation-id) str)})))

(defn page
  "The page shown when the app supplies none of its own."
  [{:keys [status message correlation-id]}]
  (access/error-page-html status message correlation-id))

(defn page-response
  "`response` with `body`, HTML, in place of the error body."
  [response body]
  (-> response
      (assoc :body body)
      (assoc-in [:headers "Content-Type"] "text/html; charset=utf-8")))

(defn htmx-response
  "`response` for htmx: its status kept, so nothing is swapped, and the message
   as an error toast. A toast the handler already asked for wins."
  [response {:keys [message]}]
  (cond-> (page-response response (access/escape-html message))
    (nil? (get-in response [:headers "HX-Trigger"]))
    (assoc-in [:headers "HX-Trigger"]
              ;; ASCII only: a header value carries no UTF-8
              (json/generate-string {:showToast {:type "error" :message message}}
                                    {:escape-non-ascii true}))))
