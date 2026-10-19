(ns wagoe.shared.ui.core.combobox
  "Combobox (typeahead): a text input that asks the server for options as the
   user types, and a hidden input holding the chosen value. For a choice among
   more rows than a select can carry. The keyboard and picking behaviour is the
   `combobox` Alpine component in ui-style's components.js."
  (:require [clojure.string :as str]))

(def limit
  "The most options an options endpoint answers one query with."
  20)

(def query-param
  "The query parameter the typed text is sent as."
  "q")

(defn- listbox-id [field-key] (str (name field-key) "-listbox"))

(defn- option-id [field-key i] (str (name field-key) "-option-" i))

(defn combobox
  "A combobox submitted as `field-key`.

   opts:
     :url         where the options come from; answered with `options`
     :name        what the value is submitted as; `field-key` by default
     :value       the chosen value, submitted
     :label       what the chosen value is shown as
     :placeholder, :required, :readonly
     :attrs       extra attributes for the text input
     :value-attrs extra attributes for the hidden input, which fires `change`
                  when an option is picked"
  [field-key {:keys [url value label placeholder required readonly attrs value-attrs]
              submitted-as :name}]
  (let [id (name field-key)
        lb (listbox-id field-key)]
    [:div {:class "combobox" :x-data "combobox" :data-query-param query-param
           (keyword "@click.outside") "close()"}
     [:input (merge {:type "hidden" :name (or submitted-as id) :id (str id "-value") :value (str (or value "")) :x-ref "value"}
                    value-attrs)]
     ;; No name: the typed text is the query, not the value, so the form does
     ;; not submit it. The component sends it as `query-param`.
     [:input (merge {:type             "text"
                     :id               id
                     :class            "form-control ui-input"
                     :value            (str (or label ""))
                     :role             "combobox"
                     :aria-autocomplete "list"
                     :aria-expanded    "false"
                     :aria-controls    lb
                     :autocomplete     "off"
                     :placeholder      placeholder
                     :required         (boolean required)
                     :readonly         (boolean readonly)
                     :x-ref            "input"
                     :x-bind:aria-expanded "open"}
                    (when-not readonly
                      {:hx-get                     url
                       ;; Not on focus: a form that focuses its first field
                       ;; would open the list on load. ArrowDown sends
                       ;; `combobox:open` when the list is empty.
                       :hx-trigger                 "click, combobox:open, input changed delay:300ms"
                       :hx-target                  (str "#" lb)
                       :hx-swap                    "innerHTML"
                       :hx-sync                    "this:replace"
                       :hx-push-url                "false"
                       (keyword "@input")          "typed()"
                       (keyword "@keydown")        "keydown($event)"})
                    attrs)]
     ;; In the flow rather than absolute: a form card with overflow hidden
     ;; would clip a list floating over it.
     [:ul {:id lb :role "listbox" :x-ref "listbox" :x-show "open" :x-cloak true
           :class "relative mt-1 w-full max-h-64 overflow-y-auto rounded-box border border-base-300 bg-base-100 p-1 shadow-sm"
           (keyword "@mousedown.prevent") "choose($event)"}]]))

(defn options
  "The options of the combobox for `field-key`, as its endpoint answers them:
   `items` [[value label] ...], or `empty` when there are none."
  [field-key items empty]
  (if (seq items)
    (map-indexed
     (fn [i [value label]]
       [:li {:id            (option-id field-key i)
             :role          "option"
             :aria-selected "false"
             :data-value    (str value)
             :data-label    (str label)
             :class         "cursor-pointer rounded px-3 py-2 text-sm hover:bg-base-200 aria-selected:bg-primary aria-selected:text-primary-content"}
        (str label)])
     items)
    (list [:li {:role "option" :aria-disabled "true" :class "px-3 py-2 text-sm opacity-60"} empty])))

(defn query
  "The text typed into a combobox, from its options request, or nil."
  [request]
  (some-> (get-in request [:query-params query-param]) str str/trim not-empty))
