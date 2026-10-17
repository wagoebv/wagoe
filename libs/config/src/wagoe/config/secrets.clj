(ns wagoe.config.secrets
  "The `#secret` Aero reader and the sources it resolves through.

     #secret \"DB_PASSWORD\"            ; the default source, :env unless configured
     #secret [:file \"db-password\"]    ; a named source
     #or [#secret \"DB_PASSWORD\" \"dev\"] ; a default, for dev

   A source is anything implementing `ISecretSource`. `:env` and `:file` are
   built in; pass more to `wagoe.config/load-config` under `:secret-sources`.
   A secret no source has fails the read, naming the secret and never a value."
  (:require [aero.alpha.core :as aero-alpha]
            [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]))

(defprotocol ISecretSource
  (fetch-secret [this secret-name]
    "The value of `secret-name`, or nil when this source does not have it."))

(defrecord EnvSource []
  ISecretSource
  (fetch-secret [_ secret-name] (System/getenv secret-name)))

(defrecord FileSource [dir]
  ISecretSource
  ;; Trimmed: `echo pw > file` and most secret tooling leave a trailing newline.
  (fetch-secret [_ secret-name]
    (let [f (io/file dir secret-name)]
      (when (.isFile f) (str/trim (slurp f))))))

(defn default-sources
  "`:env`, and `:file` reading WAGOE_SECRETS_DIR, else /run/secrets — where
   Docker and Kubernetes mount secrets."
  []
  {:env  (->EnvSource)
   :file (->FileSource (or (System/getenv "WAGOE_SECRETS_DIR") "/run/secrets"))})

;; Label -> value of every secret read, so displays can redact by value: a
;; secret under a key no name pattern catches is still a secret.
(defonce ^:private resolved (atom {}))

(def ^:private ^:dynamic *optional?*
  "True inside #or, where a missing secret is nil so the next value is tried."
  false)

;; The wrapper carries Aero's original, so a reload — including tools.namespace's
;; remove-ns, which defonce does not survive — never wraps the wrapper.
(def ^:private aero-or
  (let [m (get-method aero-alpha/eval-tagged-literal 'or)]
    (::original (meta m) m)))

(declare resolve-secret)

(defn- last-secret
  "The form of the #secret ending an #or, when one does: nothing follows it to
   fall back to, so missing it is an error again."
  [tl]
  (let [form (:form tl)
        end  (when (sequential? form) (last form))]
    (when (and (tagged-literal? end) (= 'secret (:tag end)))
      (:form end))))

(.addMethod ^clojure.lang.MultiFn aero-alpha/eval-tagged-literal 'or
            (with-meta (fn [tl opts env ks]
                         (let [result (binding [*optional?* true]
                                        (aero-or tl opts env ks))]
                           (when-let [form (and (nil? (:aero.core/value result)) (last-secret tl))]
                             (resolve-secret opts form))
                           result))
              {::original aero-or}))

(defn- secret-ref
  "[source-key name] from a #secret form."
  [form default-source]
  (let [[source-key secret-name] (if (vector? form) form [default-source form])]
    (if (and (keyword? source-key) (or (string? secret-name) (symbol? secret-name))
             (or (not (vector? form)) (= 2 (count form))))
      [source-key (str secret-name)]
      (throw (ex-info (str "#secret takes a name or [:source \"name\"], not " (pr-str form))
                      {:type :configuration-error :form form})))))

(defn- label [source-key secret-name]
  (str (name source-key) ":" secret-name))

(defn resolve-secret
  "The value of the secret `form` names. `opts` are Aero's: `:secret-sources`
   adds or replaces sources, `:default-secret-source` picks the one a bare name
   reads (default `:env`), and `::optional?` makes a missing secret or source
   nil, for a reader that only wants the rest of the file."
  [{:keys [secret-sources default-secret-source] :or {default-secret-source :env} :as opts} form]
  (let [[source-key secret-name] (secret-ref form default-secret-source)
        sources (merge (default-sources) secret-sources)
        source  (or (get sources source-key)
                    (when-not (::optional? opts)
                      (throw (ex-info (str "#secret: unknown source " source-key " for " secret-name
                                           ". Known: " (str/join ", " (sort (keys sources))))
                                      {:type :configuration-error :source source-key :secret secret-name}))))
        value   (some-> source (fetch-secret secret-name))]
    (cond
      (and (some? value) (not (string? value)))
      (throw (ex-info (str "#secret: source " source-key " returned a " (.getName (class value))
                           " for " secret-name ", not a string")
                      {:type :configuration-error :source source-key :secret secret-name}))

      (not (str/blank? value))
      (do (swap! resolved assoc (label source-key secret-name) value)
          value)

      (or *optional?* (::optional? opts)) nil

      :else
      (throw (ex-info (str "Secret not found: " secret-name " (source " source-key
                           (when-let [dir (:dir source)] (str ", in " dir))
                           "). Provide it, or give it a default with #or.")
                      {:type :configuration-error :source source-key :secret secret-name})))))

(defmethod aero/reader 'secret
  [opts _tag form]
  (resolve-secret opts form))

(defn- placeholder [l] (str "<secret " l ">"))
(def ^:private placeholder-re #"<secret ([^<>]+)>")

(defn redact
  "`x` with every value a #secret resolved to replaced by `<secret source:name>`,
   inside longer strings too: a DSN built with #join holds the password."
  [x]
  ;; Longest first, so a secret that contains another is replaced whole.
  (let [pairs (sort-by (comp - count key) (set/map-invert @resolved))]
    (if (empty? pairs)
      x
      (walk/postwalk #(if (string? %)
                        (reduce (fn [s [v l]] (str/replace s v (placeholder l))) % pairs)
                        %)
                     x))))

(defn unredact
  "The inverse of `redact`, for an edited copy written back."
  [x]
  (let [values @resolved]
    (walk/postwalk #(if (string? %)
                      (str/replace % placeholder-re (fn [[m l]] (get values l m)))
                      %)
                   x)))
