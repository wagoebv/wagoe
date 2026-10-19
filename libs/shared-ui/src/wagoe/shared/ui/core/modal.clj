(ns wagoe.shared.ui.core.modal
  "Modal on native <dialog>. The browser closes it on Esc, the backdrop and
   the close button, makes the page behind it inert and returns focus on
   close; components.js (ui-style) only opens it.")

(defn open-attrs
  "Attributes for a button that opens the modal `id`."
  [id]
  {:type          "button"
   :aria-haspopup "dialog"
   :aria-controls id
   :data-modal-open id})

(defn confirm-attrs
  "Attributes for the element carrying the htmx request: it waits for the
   confirm modal `id` instead of `hx-confirm`'s window.confirm."
  [id]
  {:data-confirm-modal id})

(defn modal
  "A <dialog> with `body` in daisyUI's modal box.

   opts:
     :id          required; what open-attrs and confirm-attrs point at
     :title       heading, also the dialog's accessible name
     :open?       open as soon as it is in the page: for content an htmx
                  swap brings in
     :closable?   the corner close button (default true)
     :close-label its accessible name (default [:t :common/button-close])
     :class       extra classes on the modal box
     :attrs       extra attributes on the <dialog>"
  [{:keys [id title open? closable? close-label class attrs]
    :or   {closable? true close-label [:t :common/button-close]}}
   & body]
  (let [title-id (str id "-title")]
    [:dialog (cond-> (merge {:id id :class "modal"} attrs)
               title (assoc :aria-labelledby title-id)
               open? (assoc :data-modal-show true))
     (into [:div {:class (cond-> "modal-box" class (str " " class))}
            (when closable?
              [:form {:method "dialog"}
               [:button {:type       "submit"
                         :class      "btn btn-sm btn-circle btn-ghost absolute right-2 top-2"
                         :aria-label close-label}
                "✕"]])
            (when title
              [:h3 {:id title-id :class "text-lg font-bold"} title])]
           body)
     ;; A click outside the box submits this form, which closes the dialog
     [:form {:method "dialog" :class "modal-backdrop"}
      [:button {:type "submit" :tabindex "-1"} close-label]]]))

(defn confirm-modal
  "A modal that answers an htmx request carrying (confirm-attrs id): the
   request goes ahead only on the confirm button. Focus starts on cancel.

   opts:
     :id, :title     as for modal
     :message        the question
     :confirm-label  default [:t :common/button-confirm]
     :cancel-label   default [:t :common/button-cancel]
     :danger?        the confirm button in the error colour"
  [{:keys [id title message confirm-label cancel-label danger?]
    :or   {confirm-label [:t :common/button-confirm]
           cancel-label  [:t :common/button-cancel]}}]
  (modal {:id        id
          :title     title
          :closable? false
          :attrs     {:role "alertdialog" :aria-describedby (str id "-message")}}
         [:p {:id (str id "-message") :class "py-4"} message]
         [:form {:method "dialog" :class "modal-action"}
          [:button {:type "submit" :value "cancel" :class "btn" :autofocus true}
           cancel-label]
          [:button {:type  "submit"
                    :value "confirm"
                    :class (if danger? "btn btn-error" "btn btn-primary")}
           confirm-label]]))
