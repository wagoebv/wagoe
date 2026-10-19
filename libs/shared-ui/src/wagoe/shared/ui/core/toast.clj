(ns wagoe.shared.ui.core.toast
  "Toasts: a live region every page layout renders, and toasts an htmx
   response adds to it out of band. The `toast` Alpine component in
   components.js dismisses them."
  (:require [hiccup2.core :as h]
            [wagoe.shared.ui.core.alpine :as alpine]))

(def region-id "toasts")

(defn toast
  "One toast. `:type` is :success, :info (default), :warning or :error.
   It goes after `:duration` ms, 5000 by default; an error stays until it is
   closed, since a message that leaves on its own may go unread. 0 keeps any."
  [{:keys [type message duration]}]
  (let [type (keyword (or type :info))
        error? (= :error type)]
    [:div (cond-> {:class  (str "alert alert-" (name type) " shadow-lg")
                   :x-data (str "toast(" (or duration (if error? 0 5000)) ")")}
            ;; Assertive, unlike the polite region around it
            error? (assoc :role "alert"))
     [:span message]
     [:button (merge {:type "button" :class "btn btn-ghost btn-xs btn-circle"
                      :aria-label "Dismiss"}
                     (alpine/x-on :click "dismiss()"))
      "✕"]]))

(defn flash-toasts
  "Toasts for a Ring flash, in either shape it is set in:
   {:type :success :message m} or {:success m, :error m2}."
  [flash]
  (cond
    (empty? flash)            nil
    (contains? flash :message) [(toast flash)]
    :else                     (for [[type message] flash]
                                (toast {:type type :message message}))))

(defn toast-region
  "The region toasts appear in, holding `toasts` already."
  [toasts]
  (into [:div {:id region-id :class "toast toast-top toast-end z-(--z-toast)" :aria-live "polite"}]
        toasts))

(defn oob-toast
  "A toast as an out-of-band swap: htmx appends it to the region, whatever
   the rest of the response replaces."
  [opts]
  [:div {:hx-swap-oob (str "beforeend:#" region-id)} (toast opts)])

(defn with-toast
  "`response` with a toast appended to its HTML body, out of band. `render`
   turns Hiccup into a string: pass one that resolves i18n markers when the
   message has them. htmx ignores the body of a 4xx/5xx unless told to swap
   it, and a response that replaces the body replaces the region too: there
   pass a flash to the layout instead."
  ([response opts]
   (with-toast response opts #(str (h/html %))))
  ([response opts render]
   (update response :body #(str % (render (oob-toast opts))))))
