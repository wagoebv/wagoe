(ns wagoe.devtools.shell.dashboard.pages.security
  "Dashboard page for Security Status overview."
  (:require [wagoe.devtools.shell.dashboard.layout :as layout]
            [wagoe.devtools.shell.dashboard.components :as c]
            [wagoe.devtools.core.security-analyzer :as sec]
            [clojure.string :as str]))

(defn- strength-class [strength]
  (case strength
    :strong   "green"
    :moderate "stat-value-warning"
    :weak     "stat-value-error"
    nil))

(defn- check-item [ok? label]
  [:div {:style "display:flex;align-items:center;gap:8px;padding:4px 0"}
   (if ok?
     [:span {:style "color:var(--accent-green)"} "✓"]
     [:span {:style "color:var(--color-red,#f87171)"} "✗"])
   [:span label]])

(defn- auth-failures-list
  "Render recent authentication failures."
  [failures]
  (if (empty? failures)
    [:div.empty-state "No recent auth failures."]
    (c/data-table
     {:columns      ["Time" "Type" "Detail"]
      :col-template "120px 100px 1fr"
      :rows         (for [{:keys [timestamp type detail]} (take 10 failures)]
                      {:cells [[:span.text-mono (or timestamp "—")]
                               [:span {:style "color:var(--color-red,#f87171)"} (name (or type :unknown))]
                               [:span (or detail "—")]]})})))

(defn- security-content [config runtime-data]
  (let [summary    (sec/build-security-summary config runtime-data)
        pp         (:password-policy summary)
        auth       (:auth-methods summary)
        roles      (:roles summary)
        lockout    (:lockout summary)
        csp        (:csp summary)]
    [:div
     [:div.stat-row
      (c/stat-card {:label "Password Strength"
                    :value (when pp (str/capitalize (name (:strength pp))))
                    :value-class (when pp (strength-class (:strength pp)))})
      (c/stat-card {:label "Auth Methods"
                    :value (count (:methods auth))})
      (c/stat-card {:label "MFA"
                    :value (if (:mfa-enabled? auth) "Enabled" "Disabled")
                    :value-class (if (:mfa-enabled? auth) "green" "stat-value-warning")})
      (c/stat-card {:label "Active Sessions"
                    :value (:active-sessions summary)})
      (c/stat-card {:label "CSRF"
                    :value (if (:csrf-enabled? summary) "Active" "Inactive")
                    :value-class (if (:csrf-enabled? summary) "green" "stat-value-error")})
      (c/stat-card {:label "Rate Limiting"
                    :value (if (:rate-limiting? summary) "Active" "Inactive")
                    :value-class (if (:rate-limiting? summary) "green" "stat-value-warning")})]
     [:div.two-col
      (c/card {:title "Password Policy"}
              (when pp
                [:div
                 [:div {:style "font-family:var(--font-mono);font-size:12px;line-height:2"}
                  (check-item (>= (:min-length pp) 8) (str "Min length: " (:min-length pp)))
                  (check-item (:require-uppercase? pp) "Require uppercase")
                  (check-item (:require-lowercase? pp) "Require lowercase")
                  (check-item (:require-numbers? pp) "Require numbers")
                  (check-item (:require-special? pp) "Require special characters")]]))
      (c/card {:title "Authentication & Access"}
              [:div {:style "font-family:var(--font-mono);font-size:12px;line-height:2"}
               (for [method (:methods auth)]
                 (check-item true (str/upper-case (name method))))
               (check-item (:configured? csp) (str "CSP: " (if (:configured? csp) "configured" "not configured")))
               [:div {:style "margin-top:12px"}
                [:span.text-muted "Roles: "]
                [:span (str/join ", " (map name (:allowed-roles roles)))]
                [:br]
                [:span.text-muted "Default: "]
                [:span (name (:default-role roles))]
                [:br]
                [:span.text-muted "Lockout: "]
                [:span (str (:max-attempts lockout) " attempts / "
                            (:duration-mins lockout) " min")]]])]
     (c/card {:title "Recent Auth Failures"}
             (auth-failures-list (:recent-failures summary)))]))

(defn render
  "Render the Security Status full page."
  [opts]
  (let [config       (:config opts)
        runtime-data {:active-sessions      (:active-sessions opts)
                      :recent-auth-failures (:recent-auth-failures opts)
                      :rate-limiting?       (:rate-limiting? opts)}]
    (layout/dashboard-page
     (merge opts {:active-path "/dashboard/security"
                  :title       "Security Status"})
     (if config
       (security-content config runtime-data)
       [:div.empty-state "No security configuration available. Start the system with (go) first."]))))
