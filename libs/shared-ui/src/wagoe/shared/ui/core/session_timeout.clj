(ns wagoe.shared.ui.core.session-timeout
  "The dialog that asks before a session ends (BOU-1007): whether the user is
   still there before it times out, and for the password before its last day
   ends. The user module binds `*session*`; session-timeout.js counts down."
  (:require [wagoe.platform.core.csrf :as csrf]))

(def ^:dynamic *session*
  "{:expires-at :ends-at :extend-path :reauth-path} of the request's session,
   the instants in epoch milliseconds, or nil."
  nil)

(defn dialog
  "The dialog, closed until session-timeout.js opens it; nil without a session."
  []
  (when-let [{:keys [expires-at ends-at extend-path reauth-path]} *session*]
    [:dialog#session-timeout.modal {:data-expires expires-at :data-ends ends-at
                                    :data-extend extend-path :data-reauth reauth-path
                                    :aria-labelledby "session-timeout-title"}
     [:div.modal-box
      [:h2#session-timeout-title [:t :user.session/title]]
      [:div.session-idle
       [:p [:t :user.session/idle] " " [:strong.session-countdown]]
       [:button.button.primary.session-stay {:type "button"} [:t :user.session/stay]]]
      [:form.session-reauth {:hidden true}
       [:p [:t :user.session/reauth] " " [:strong.session-countdown]]
       [:label {:for "session-reauth-password"} [:t :user.session/password]]
       [:input#session-reauth-password {:type "password" :name "password" :autocomplete "current-password"}]
       [:div.session-mfa {:hidden true}
        [:label {:for "session-reauth-mfa"} [:t :user.session/mfa-code]]
        [:input#session-reauth-mfa {:type "text" :name "mfa-code" :inputmode "numeric" :autocomplete "one-time-code"}]]
       [:p.session-reauth-error {:hidden true :role "alert"} [:t :user.session/reauth-refused]]
       [:button.button.primary {:type "submit"} [:t :user.session/reauth-submit]]]
      [:form.session-logout {:method "post" :action "/web/logout"}
       (csrf/hidden-field)
       [:button.button {:type "submit"} [:t :common/button-logout]]]]]))
