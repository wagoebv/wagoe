(ns wagoe.shared.ui.core.session-timeout
  "The dialog that asks before a session ends (BOU-1007): whether the user is
   still there before it times out, and for the password before its last day
   ends. The user module binds `*session*`; session-timeout.js counts down."
  (:require [wagoe.platform.core.csrf :as csrf]
            [wagoe.shared.ui.core.modal :as modal]))

(def ^:dynamic *session*
  "{:expires-at :ends-at :now :extend-path :reauth-path :login-path
   :logout-path} of the request's session, the instants in epoch
   milliseconds, or nil. :now is the server's, whose clock ends the session."
  nil)

(defn dialog
  "The dialog, closed until session-timeout.js opens it; nil without a session.
   Only an answer closes it: not the backdrop, nor Escape."
  []
  (when-let [{:keys [expires-at ends-at now extend-path reauth-path login-path logout-path]} *session*]
    (modal/modal
     {:id        "session-timeout"
      :title     [:t :user.session/title]
      :closable? false
      :backdrop? false
      :attrs     {:data-expires expires-at :data-ends ends-at :data-now now
                  :data-extend extend-path :data-reauth reauth-path}}
     [:div.session-idle
      [:p [:t :user.session/idle] " " [:strong.session-countdown]]
      [:button.button.primary.session-stay {:type "button"} [:t :user.session/stay]]]
     [:form.session-reauth {:hidden true}
      [:p [:t :user.session/reauth] " " [:strong.session-countdown]]
      [:label {:for "session-reauth-password"} [:t :user.session/password]]
      [:input#session-reauth-password {:type "password" :name "reauth-password" :autocomplete "current-password"}]
      [:div.session-mfa {:hidden true}
       [:label {:for "session-reauth-mfa"} [:t :user.session/mfa-code]]
       [:input#session-reauth-mfa {:type "text" :name "reauth-mfa-code" :inputmode "numeric" :autocomplete "one-time-code"}]]
      [:p.session-reauth-error {:hidden true :role "alert"} [:t :user.session/reauth-refused]]
      [:button.button.primary {:type "submit"} [:t :user.session/reauth-submit]]
      ;; Without a password, signed in through another provider
      [:p [:a.session-sign-in {:href login-path} [:t :user.session/sign-in-again]]]]
     [:form.session-logout {:method "post" :action logout-path}
      (csrf/hidden-field)
      [:button.button {:type "submit"} [:t :common/button-logout]]])))
