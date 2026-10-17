(ns wagoe.config.secrets
  "The `#secret` Aero reader and the sources it resolves through.

     #secret \"DB_PASSWORD\"                         ; the default source, :env unless configured
     #secret [:file \"db-password\"]                 ; a named source
     #secret {:name \"DB_PASSWORD\" :default \"dev\"} ; a default, for dev

   A source is anything implementing `ISecretSource`. `:env` and `:file` are
   built in; declare more under `:secret-sources` in the config file, or pass
   them to `wagoe.config/load-config`. A secret no source has fails the read,
   naming the secret and never a value."
  (:require [aero.core :as aero]
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

(defn- secret-ref
  "{:source :name}, and :default when the form gives one, from a #secret form.
   wagoe.tools.doctor/secret-refs mirrors this; change both."
  [form default-source]
  (let [ref (cond (map? form)    (update form :source #(or % default-source))
                  (vector? form) (zipmap [:source :name] form)
                  :else          {:source default-source :name form})]
    (if (and (every? #{:name :source :default} (keys ref))
             (not (and (vector? form) (not= 2 (count form))))
             (keyword? (:source ref))
             (or (string? (:name ref)) (symbol? (:name ref))))
      (update ref :name str)
      (throw (ex-info (str "#secret takes a name, [:source \"name\"] or {:name \"name\" :default ...}, not "
                           (pr-str form))
                      {:type :configuration-error :form form})))))

(defn- label [source-key secret-name]
  (str (name source-key) ":" secret-name))

(defn resolve-secret
  "The value of the secret `form` names. `opts` are Aero's: `:secret-sources`
   adds or replaces sources, `:default-secret-source` picks the one a bare name
   reads (default `:env`), and `::optional?` makes a missing secret or source
   nil, for a reader that only wants the rest of the file. A form's `:default`
   is returned when the secret is missing or blank."
  [{:keys [secret-sources default-secret-source] :or {default-secret-source :env} :as opts} form]
  (let [{source-key :source secret-name :name :as ref} (secret-ref form default-secret-source)
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

      (contains? ref :default) (:default ref)

      (::optional? opts) nil

      :else
      (throw (ex-info (str "Secret not found: " secret-name " (source " source-key
                           (when-let [dir (:dir source)] (str ", in " dir))
                           "). Provide it, or give it a default: #secret {:name \"" secret-name
                           "\" :default ...}; #or does not supply one.")
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
