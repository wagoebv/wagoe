(ns wagoe.devtools.shell.dashboard.pages.schemas
  (:require [wagoe.devtools.shell.dashboard.layout :as layout]
            [wagoe.devtools.shell.dashboard.components :as c]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [malli.core :as m]))

;; =============================================================================
;; Schema collection
;; =============================================================================

(defn schema-namespace?
  "Does `ns-sym` name a schema namespace?

   Any namespace ending in `.schema`, not only the framework's own. The scan
   used to match `wagoe.<module>.schema` exactly, so an application namespace
   such as `invoicing.invoice.schema` could never appear — the browser listed
   52 framework schemas and none of the developer's (BOU-509). The same regex
   also missed nested framework namespaces like `wagoe.admin.core.schema`."
  [ns-sym]
  (boolean (re-find #"\.schema$" (str ns-sym))))

(defn- discover-schema-namespaces
  "Namespace symbols to collect Malli schemas from.

   Two sources, unioned: the framework repository's own `libs/*/schema.clj`
   when running inside it, and every already-loaded namespace ending in
   `.schema` — which is how an application's modules are found, since a
   generated project has no `libs/` directory."
  []
  (let [libs-dir (java.io.File. "libs")
        from-libs (when (.isDirectory libs-dir)
                    (->> (.listFiles libs-dir)
                         (filter #(.isDirectory %))
                         (map #(symbol (str "wagoe." (.getName %) ".schema")))
                         (filter (fn [ns-sym]
                                   (try (require ns-sym) true (catch Exception _ false))))))
        from-loaded (->> (all-ns)
                         (map ns-name)
                         (filter schema-namespace?)
                         (map symbol))]
    (vec (distinct (concat from-libs from-loaded)))))

(defn- malli-schema?
  "Return true if value looks like a Malli schema (vector or keyword)."
  [v]
  (and (some? v)
       (not (fn? v))
       (not (var? v))
       (try (m/schema v) true (catch Exception _ false))))

(defn schema-key
  "The browser's key for schema var `var-name` in namespace `ns-sym`: the
   var's fully-qualified name as a keyword, e.g. :wagoe.user.schema/User.

   Not :<module>/<Var>. That shorter key kept only the segment before
   `.schema`, so acme.user.schema/User and wagoe.user.schema/User were both
   :user/User, and every `*.core.schema` namespace landed on :core. Once the
   scan took in every loaded `.schema` namespace (BOU-509) the collision became
   reachable, and discover-all-schemas merges — one schema silently vanished
   from the browser. The full namespace is the only name unique by
   construction."
  [ns-sym var-name]
  (keyword (str ns-sym) (name var-name)))

(defn- discover-schemas-from-ns
  "Try to require a namespace and collect its public Malli schema defs.
   Returns a map of {schema-key schema-value} or nil."
  [ns-sym]
  (try
    (require ns-sym)
    (when-let [ns-obj (find-ns ns-sym)]
      (into {}
            (for [[var-name var-ref] (ns-publics ns-obj)
                  :let [v (try (var-get var-ref) (catch Exception _ nil))]
                  :when (malli-schema? v)]
              [(schema-key ns-sym var-name) v])))
    (catch Exception _ nil)))

(defn- discover-all-schemas
  "Scan every discovered `.schema` namespace and collect Malli schemas.
   Returns a map of {schema-key schema-value}; keys cannot collide, see
   `schema-key`."
  []
  (reduce (fn [acc ns-sym]
            (if-let [schemas (discover-schemas-from-ns ns-sym)]
              (merge acc schemas)
              acc))
          {}
          (discover-schema-namespaces)))

(defonce ^:private known-schemas* (atom nil))

(defn reset-schemas!
  "Clear the cached schema map so it is re-discovered on next access.
   Called automatically on system reload."
  []
  (reset! known-schemas* nil))

(defn- known-schemas
  "Return the discovered schema map, lazily initializing on first call."
  []
  (or @known-schemas*
      (let [schemas (discover-all-schemas)]
        (reset! known-schemas* schemas)
        schemas)))

(defn collect-schemas
  "Collect available Malli schemas. Returns a seq of schema keys."
  []
  (let [schemas (known-schemas)]
    (if (seq schemas)
      (sort-by (juxt namespace name) (keys schemas))
      [])))

;; =============================================================================
;; Schema detail rendering
;; =============================================================================

(defn- schema-type-label
  "Return a readable string for a Malli schema type."
  [s]
  (try
    (let [t (m/type s)]
      (case t
        :enum  (str "[:enum " (clojure.string/join " " (m/children s)) "]")
        :maybe (str "[:maybe " (schema-type-label (first (m/children s))) "]")
        (str t)))
    (catch Exception _ "?")))

(defn- render-field-row
  "Render a single schema field as a Hiccup row."
  [child]
  (let [k       (first child)
        props   (when (= 3 (count child)) (second child))
        schema  (if (= 3 (count child)) (nth child 2) (second child))
        opt?    (true? (:optional props))
        type-str (try (schema-type-label (m/schema schema)) (catch Exception _ (pr-str schema)))]
    [:div.schema-field
     (if opt?
       [:span.schema-optional "○"]
       [:span.schema-required "*"])
     [:span.schema-field-name (pr-str k)]
     [:span.schema-field-type type-str]]))

(defn- generate-example-str
  "Try to generate a malli example. Returns nil if malli.generator is unavailable."
  [schema]
  (try
    (let [gen-fn (requiring-resolve 'malli.generator/generate)]
      (when gen-fn
        (let [example (gen-fn (m/schema schema) {:seed 42})]
          (with-out-str (pprint/pprint example)))))
    (catch Exception _ nil)))

(defn render-schema-detail
  "Render a schema's fields as a tree panel."
  [schema-key]
  (try
    (let [schema-val (get (known-schemas) schema-key)]
      (if-not schema-val
        [:div.detail-panel
         [:p.schema-no-detail (str "No schema loaded for " (pr-str schema-key))]
         [:p.schema-hint "Schema data is only available for modules on the classpath."]]
        (let [s        (m/schema schema-val)
              title    (or (get (m/properties s) :title) (pr-str schema-key))
              children (try (m/children s) (catch Exception _ nil))
              example  (generate-example-str schema-val)]
          [:div.detail-panel
           [:div.schema-detail-header
            [:h3.schema-detail-title title]
            [:span.schema-field-type (str (m/type s))]]
           (when (seq children)
             (let [map-children (filter sequential? children)]
               (if (seq map-children)
                 [:div.schema-fields
                  (for [child map-children]
                    (render-field-row child))]
                 [:div.schema-fields
                  [:pre.code-block (pr-str children)]])))
           (when example
             [:div.schema-example
              [:div.schema-example-label "Example value"]
              (c/code-block example)])])))
    (catch Exception e
      [:div.detail-panel.detail-panel-error
       [:p.error-title "Error rendering schema"]
       [:p (ex-message e)]])))

;; =============================================================================
;; Page
;; =============================================================================

(defn render
  "Render the Schema Browser full page."
  [opts req]
  (let [params         (get req :params {})
        selected-raw   (or (get params "schema") (get params :schema))
        selected-key   (when selected-raw (keyword selected-raw))
        schema-keys    (collect-schemas)
        detail-content (if selected-key
                         (render-schema-detail selected-key)
                         [:div.schema-empty-state
                          [:p "Select a schema from the list to inspect its fields."]])]
    (layout/dashboard-page
     (merge opts {:active-path "/dashboard/schemas"
                  :title       "Schema Browser"})
     [:div.schema-layout
      ;; Left column: schema list
      [:div.schema-list-panel
       (c/card
        {:title "Schemas"
         :right [:span.count-badge (str (count schema-keys))]}
        (c/filter-input {:placeholder "Filter schemas..."
                         :id          "schema-search"
                         :oninput     "let v=this.value.toLowerCase();document.querySelectorAll('#schema-list .schema-list-item').forEach(el=>{el.style.display=el.textContent.toLowerCase().includes(v)?'':'none'})"})
        [:div#schema-list
         (for [sk schema-keys]
           (let [active? (= sk selected-key)
                 href    (str "/dashboard/schemas?schema=" (namespace sk) "/" (name sk))]
             [:a.schema-list-item
              {:href  href
               :class (when active? "active")}
              [:span.schema-list-key (pr-str sk)]]))])]
      ;; Right column: schema detail
      [:div.schema-detail-panel
       detail-content]])))
