(ns wagoe.shared.ui.core.toast
  "Toasts: a live region every page layout renders, and toasts an htmx
   response adds to it out of band. The `toast` Alpine component in
   components.js dismisses them."
  (:require [cheshire.core :as json]
            [wagoe.platform.core.http.error-pages :as error-pages]
            [wagoe.shared.ui.core.alpine :as alpine]))

(def region-id "toasts")

(def ^:private types [:success :info :warning :error])

(defn toast
  "One toast. `:type` is :success, :info (default), :warning or :error; a
   keyword `:message` is a catalogue key.
   It goes after five seconds; an error stays until it is closed, since a
   message that leaves on its own may go unread."
  [{:keys [type message]}]
  (let [type (keyword (or type :info))
        error? (= :error type)]
    [:div (cond-> {:class  (str "alert alert-" (name type) " shadow-lg")
                   :x-data (if error? "toast(0)" "toast(5000)")}
            ;; Assertive, unlike the polite region around it
            error? (assoc :role "alert"))
     [:span (if (keyword? message) [:t message] message)]
     [:button (merge {:type "button" :class "btn btn-ghost btn-xs btn-circle"
                      :aria-label [:t :common/button-dismiss]}
                     (alpine/x-on :click "dismiss()"))
      "✕"]]))

(defn flash-toasts
  "Toasts for a Ring flash, in either shape it is set in:
   {:type :success :message m} or {:success m, :error m2}. They wait in a
   template that components.js empties into the region once the page is up,
   since a live region announces only what is added to it."
  [flash]
  (when (seq flash)
    (into [:template {:data-toast-flash ""}]
          (if (contains? flash :message)
            [(toast flash)]
            (for [[type message] flash]
              (toast {:type type :message message}))))))

(defn toast-region
  "The region toasts appear in. A swap of the body keeps it (hx-preserve), so
   it stays the live region a screen reader is watching. An empty toast of
   each type waits in a template, for components.js to fill in. The data
   attributes are the words admin-ux.js toasts a failed request with."
  []
  (into [:div {:id region-id :class "toast toast-top toast-end z-(--z-toast)"
               :aria-live "polite" :hx-preserve "true"
               :data-error-message   [:t :common/error-generic]
               :data-error-reference [:t :common/error-page-reference]}]
        (for [type types]
          [:template {:data-toast-type (name type)} (toast {:type type})])))

(defn oob-toast
  "A toast as an out-of-band swap: htmx appends it to the region, whatever
   the rest of the response replaces."
  [opts]
  [:div {:hx-swap-oob (str "beforeend:#" region-id)} (toast opts)])

(defn with-toast
  "`response` with a toast appended to its HTML body, out of band. `render`
   turns Hiccup into a string, resolving any i18n markers in the message.
   htmx ignores the body of a 4xx/5xx unless told to swap it, and a response
   that replaces the body replaces the region too: there pass a flash to the
   layout instead."
  [response opts render]
  (update response :body #(str % (render (oob-toast opts)))))

;; Toasts components.js shows from response headers, for a response whose
;; body is not swapped. `message` is a string, already translated.

(defn- header-json [x]
  ;; ASCII only: a header value carries no UTF-8
  (json/generate-string x {:escape-non-ascii true}))

(defn redirect-with-toast
  "A response sending htmx to `location` with a toast to show there:
   components.js keeps the X-Toast across the redirect."
  [location type message]
  {:status  200
   :headers {"HX-Redirect" location
             "X-Toast"     (header-json {:type type :message message})}
   :body    ""})

(defn with-error-toast
  "Failed `response` with `message` as an error toast: htmx swaps no 4xx/5xx
   body, so a button's request would fail silently."
  [response message]
  (assoc-in response [:headers "HX-Trigger"] (error-pages/toast-trigger message)))
