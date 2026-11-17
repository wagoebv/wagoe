(ns wagoe.shared.ui.core.view-as
  "The bar on every page while an administrator views the app as another
   user, read-only (BOU-868). The user module binds `*viewing*` for the
   request; the page's forms are switched off by view-as.js."
  (:require [wagoe.platform.core.csrf :as csrf]))

(def ^:dynamic *viewing*
  "{:name :stop-path} of the user the request views the app as, or nil."
  nil)

(defn bar
  "The bar saying whom the page shows the app as, with the way to stop; nil
   when the request views as nobody."
  []
  (when-let [{:keys [name stop-path]} *viewing*]
    [:div#view-as-bar.view-as-bar {:role "status" :class "alert alert-warning"
                                   :data-disabled-title [:t :user.view-as/disabled]}
     [:span [:t :user.view-as/banner {:name name}]]
     [:form {:method "post" :action stop-path}
      (csrf/hidden-field)
      [:button.button.primary {:type "submit"} [:t :user.view-as/stop]]]]))
