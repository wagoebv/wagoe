(ns wagoe.shared.ui.core.form
  "Form fields from a malli map schema.

   One schema answers three questions: which HTML validation attributes a
   field carries (`input-attrs`), what is wrong with a submitted form
   (`validate`), and what the field looks like with its errors (`field`). A
   field rendered with a `:validate-url` posts itself there when it changes,
   and the server answers with the field again, so the browser shows the
   server's verdict without holding any state. Without JavaScript the form
   submits whole and the same `validate` decides.

   Error messages are i18n markers (`[:t :validation/required]`); a page that
   is not rendered through wagoe-i18n resolves them with `plain-errors`."
  (:require [wagoe.core.utils.text :as text]
            [clojure.string :as str]
            [cheshire.core :as json]
            [malli.core :as m]
            [malli.transform :as mt]
            [wagoe.platform.core.csrf :as csrf]
            [wagoe.shared.ui.core.components :as ui]))

;; =============================================================================
;; Schema walking
;; =============================================================================

(defn- entries
  "[key props schema] for each entry of map `schema`."
  [schema]
  (m/children (m/schema schema)))

(defn- entry [schema field-key]
  (some #(when (= field-key (first %)) %) (entries schema)))

(defn- wrappers
  "`schema` and every schema inside its :maybe / :and, outermost first."
  [schema]
  (cons schema (when (#{:maybe :and} (m/type schema))
                 (mapcat wrappers (m/children schema)))))

(defn- leaves
  "The schemas that constrain a value: `schema` with :maybe and :and
   unwrapped."
  [schema]
  (remove #(#{:maybe :and} (m/type %)) (wrappers schema)))

(def ^:private integer-types '#{:int int? integer? pos-int? nat-int? neg-int?})
(def ^:private decimal-types '#{:double :float double? float? decimal? number?})
(def ^:private comparators #{:> :>= :< :<=})

(defn- number-type? [t]
  (or (integer-types t) (decimal-types t)))

(defn- composite? [schema]
  (#{:map :vector :sequential :set :map-of :tuple} (m/type schema)))

(defn field-keys
  "The keys of map `schema` a form can show, in schema order: every entry but
   a nested map or collection."
  [schema]
  (vec (for [[k _ s] (entries schema)
             :when (not-any? composite? (leaves s))]
         k)))

(defn field-key
  "The key of map `schema` named `s`, or nil. A request names the field it
   validates; this keeps an arbitrary name from becoming a keyword."
  [schema s]
  (when s
    (some #(when (= (name %) (str s)) %) (field-keys schema))))

;; =============================================================================
;; HTML attributes
;; =============================================================================

(defn- named-like? [field-key names]
  (let [n (name field-key)]
    (some #(or (= n %) (str/ends-with? n (str "-" %))) names)))

(defn- html-type
  "The input type for a field: a `:html/type` property wins, then the value
   type, then — for a plain string — the field's name."
  [field-key schema]
  (let [types (set (map m/type (leaves schema)))]
    (or (some #(some-> (:html/type (m/properties %)) name) (wrappers schema))
        (cond
          (some number-type? types)                     "number"
          (some comparators types)                      "number"
          (some '#{:boolean boolean?} types)            "checkbox"
          (contains? types :time/local-date)            "date"
          (contains? types :enum)                       nil
          (named-like? field-key ["email"])             "email"
          (named-like? field-key ["url" "website"])     "url"
          :else                                         "text"))))

(defn- bounds
  "{:min :max} from the leaves' properties and comparators."
  [leaves]
  (reduce (fn [acc s]
            (let [{:keys [min max]} (m/properties s)
                  [bound]           (m/children s)]
              (cond-> acc
                min                       (assoc :min min)
                max                       (assoc :max max)
                (= :>= (m/type s))        (assoc :min bound)
                (= :<= (m/type s))        (assoc :max bound)
                (= 'pos-int? (m/type s))  (assoc :min 1)
                (= 'nat-int? (m/type s))  (assoc :min 0))))
          {}
          leaves))

(defn- regex-source
  "The regex of an :re schema, when it is anchored at both ends. malli matches
   with re-find, an HTML pattern the whole value: unanchored, the browser would
   refuse values the server accepts."
  [s]
  (let [src (some-> (first (m/children s)) str)]
    (when (and src (str/starts-with? src "^") (str/ends-with? src "$")
               (not (str/ends-with? src "\\$")))
      src)))

(def ^:private text-types
  "Input types that take minlength, maxlength and pattern."
  #{"text" "email" "url" "search" "tel" "password"})

(defn- nullable? [schema]
  (some #(= :maybe (m/type %)) (wrappers schema)))

(defn- required-entry? [props schema]
  (not (or (:optional props) (nullable? schema))))

(defn input-attrs
  "The HTML validation attributes the schema of `field-key` in map `schema`
   implies: `required`, `type`, `minlength`/`maxlength` for a string,
   `min`/`max` (and `step`) for a number, `pattern` for a regex on a text
   field. nil for a key the schema does not have.

     (input-attrs [:map [:name [:string {:min 2}]]] :name)
     ;=> {:type \"text\" :required true :minlength 2}"
  [schema field-key]
  (when-let [[_ props s] (entry (m/schema schema) field-key)]
    (let [ls          (leaves s)
          type        (html-type field-key s)
          {:keys [min max]} (bounds ls)
          number?     (= "number" type)
          text?       (contains? text-types type)
          pattern     (some #(when (= :re (m/type %)) (regex-source %)) ls)]
      (cond-> {}
        type                                   (assoc :type type)
        (and (required-entry? props s)
             (not= "checkbox" type))           (assoc :required true)
        (and number? min)                      (assoc :min min)
        (and number? max)                      (assoc :max max)
        (and number? (some (comp decimal-types m/type) ls))
        (assoc :step "any")
        (and text? min)                        (assoc :minlength min)
        (and text? max)                        (assoc :maxlength max)
        (and text? pattern)                    (assoc :pattern pattern)))))

(defn attrs-by-field
  "{field-key attrs} for every field of map `schema` (see `input-attrs`)."
  [schema]
  (let [schema (m/schema schema)]
    (into {} (map (juxt identity #(input-attrs schema %))) (field-keys schema))))

;; =============================================================================
;; Validation
;; =============================================================================

(def ^:private form-transformer
  (mt/transformer mt/strip-extra-keys-transformer mt/string-transformer))

(defn value
  "The submitted value of `field-key` in `params`, keyed by string (Ring form
   params) or keyword. A field sent twice — a checkbox and its hidden
   fallback — is its last value."
  [params field-key]
  (let [v (if (contains? params (name field-key))
            (get params (name field-key))
            (get params field-key))]
    (if (sequential? v) (last v) v)))

(defn decode
  "Form `params` as data for map `schema`: its fields only, each decoded from
   the string the browser sent. A blank field is nil where the schema takes
   nil, and left out otherwise."
  [schema params]
  (let [schema (m/schema schema)
        data   (into {} (for [k     (field-keys schema)
                              :let  [[_ _ s] (entry schema k)
                                     v       (value params k)
                                     blank   (or (nil? v) (and (string? v) (str/blank? v)))]
                              :when (or (not blank) (nullable? s))]
                          [k (when-not blank v)]))]
    (m/decode schema data form-transformer)))

(defn- message
  "An i18n marker for one malli error, or the schema's own `:error/message`."
  [{:keys [schema value type]}]
  (let [props (m/properties schema)
        t     (m/type schema)
        {:keys [min max]} props]
    (cond
      (= type ::m/missing-key)  [:t :validation/required]
      (:error/message props)    (:error/message props)
      (and (= :string t) (string? value) min (< (count value) min))
      [:t :validation/too-short {:min min}]
      (and (= :string t) (string? value) max (> (count value) max))
      [:t :validation/too-long {:max max}]
      (and (or (number-type? t) (comparators t)) (not (number? value)))
      [:t :validation/not-a-number]
      (and (number? value) (= :>= t)) [:t :validation/too-small {:min (first (m/children schema))}]
      (and (number? value) (= :> t))  [:t :validation/greater-than {:min (first (m/children schema))}]
      (and (number? value) (= :<= t)) [:t :validation/too-large {:max (first (m/children schema))}]
      (and (number? value) (= :< t))  [:t :validation/less-than {:max (first (m/children schema))}]
      (and (number? value) min (< value min)) [:t :validation/too-small {:min min}]
      (and (number? value) max (> value max)) [:t :validation/too-large {:max max}]
      (= :re t)                 [:t :validation/pattern]
      (= :enum t)               [:t :validation/not-an-option]
      :else                     [:t :validation/invalid])))

(defn errors
  "{field-key [message]} for `data` against map `schema`, or nil when it is
   valid. Messages are i18n markers."
  [schema data]
  (when-let [explained (m/explain schema data)]
    (not-empty
     (reduce (fn [acc {:keys [in] :as error}]
               (let [k (first in)]
                 (if (keyword? k)
                   (update acc k #(vec (distinct (conj (or % []) (message error)))))
                   acc)))
             {}
             (:errors explained)))))

(defn validate
  "Form `params` checked against map `schema`: {:data decoded :errors
   {field-key [message]}}, :errors nil when the form is valid."
  [schema params]
  (let [schema (m/schema schema)
        data   (decode schema params)]
    {:data data :errors (errors schema data)}))

(def english
  "The messages in English, for a page not rendered through wagoe-i18n. The
   catalogues in wagoe-i18n carry the same keys."
  {:validation/required      "Required"
   :validation/too-short     "At least {min} characters"
   :validation/too-long      "At most {max} characters"
   :validation/too-small     "At least {min}"
   :validation/too-large     "At most {max}"
   :validation/greater-than  "More than {min}"
   :validation/less-than     "Less than {max}"
   :validation/not-a-number  "Must be a number"
   :validation/pattern       "Not in the expected format"
   :validation/not-an-option "Not one of the options"
   :validation/invalid       "Invalid value"})

(defn plain
  "`msg` as an English string: a marker looked up in `english`, anything else
   as it is."
  [msg]
  (if (and (vector? msg) (= :t (first msg)))
    (let [[_ k params] msg]
      (reduce-kv (fn [s pk pv] (str/replace s (str "{" (name pk) "}") (str pv)))
                 (get english k (name k))
                 (or params {})))
    msg))

(defn plain-errors
  "`errors` with every message `plain`."
  [errors]
  (update-vals errors #(mapv plain %)))

;; =============================================================================
;; Rendering
;; =============================================================================

(defn wrapper-id
  "Id of the element wrapping a field: what a per-field validation swaps."
  [field-key]
  (str (name field-key) "-field"))

(defn live-attrs
  "htmx attributes that post a field's form to `url` when the field changes,
   naming the field as `__field` (plus `extra` string pairs), and swap the
   answer over the field's wrapper. `hx-push-url` is reset because a control
   inherits it from an htmx form."
  ([url field-key] (live-attrs url field-key nil))
  ([url field-key extra]
   {:hx-post     url
    :hx-trigger  "change delay:300ms"
    :hx-target   "closest .form-field"
    :hx-swap     "outerHTML"
    :hx-push-url "false"
    :hx-vals     (json/generate-string (merge {"__field" (name field-key)} extra))}))

(defn with-live-validation
  "`input-html` with `live-attrs` on the controls named `field-key`."
  ([input-html field-key url] (with-live-validation input-html field-key url nil))
  ([input-html field-key url extra]
   (ui/update-controls input-html field-key #(merge % (live-attrs url field-key extra)))))

(defn label-text
  "A field's default label: its key, humanised."
  [field-key]
  (text/capitalize (str/replace (name field-key) #"[-_]" " ")))

(defn- display-value [v]
  (cond (nil? v)     ""
        ;; With its namespace: the string decoder reads "a/b" back as :a/b
        (keyword? v) (subs (str v) 1)
        :else        (str v)))

(defn- control
  "The control for `field-key`: a select for an enum, a checkbox for a
   boolean, an input otherwise."
  [schema field-key value attrs]
  (let [[_ _ s] (entry schema field-key)
        enum    (some #(when (= :enum (m/type %)) %) (leaves s))
        id      (name field-key)]
    (cond
      enum
      [:select (merge {:id id :name id :class "form-control ui-input"}
                      (select-keys attrs [:required]))
       [:option {:value ""} ""]
       (for [option (m/children enum)
             :let [v (display-value option)]]
         [:option {:value v :selected (= v (display-value value))} (label-text v)])]

      (= "checkbox" (:type attrs))
      (ui/checkbox field-key (contains? #{true "true"} value))

      :else
      (ui/text-input field-key (display-value value) attrs))))

(defn field
  "The wrapper of `field-key` in map `schema`: label, control carrying the
   schema's HTML attributes, and the field's errors, tied to the control for
   screen readers. With `:validate-url` the control posts its form there when
   it changes (see `live-attrs`); the handler answers with this function's
   output for the submitted value.

   opts: :label, :value, :errors (messages), :validate-url, :attrs (merged
   over the derived attributes)."
  [schema field-key {:keys [label value errors validate-url attrs]}]
  (let [schema    (m/schema schema)
        error-id  (ui/field-error-id field-key)
        input     (cond-> (control schema field-key value
                                   (merge (input-attrs schema field-key) attrs))
                    validate-url (with-live-validation field-key validate-url)
                    (seq errors) (ui/describe-input field-key error-id))]
    [:div {:id (wrapper-id field-key)
           :class (cond-> "form-field" (seq errors) (str " has-errors"))}
     [:label {:for (name field-key)} (or label (label-text field-key))]
     input
     (ui/field-errors error-id errors)]))

(defn form
  "A form carrying the request's CSRF token, so it can be submitted without
   JavaScript."
  [attrs & body]
  (into [:form attrs (csrf/hidden-field)] body))
