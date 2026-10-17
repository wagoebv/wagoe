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

;; Wrapped once: defonce keeps a reload from wrapping the wrapper.
(defonce ^:private aero-or (get-method aero-alpha/eval-tagged-literal 'or))

(defmethod aero-alpha/eval-tagged-literal 'or
  [tl opts env ks]
  (binding [*optional?* true]
    (aero-or tl opts env ks)))

(defn- secret-ref
  "[source-key name] from a #secret form."
  [form default-source]
  (cond
    (or (string? form) (symbol? form))
    [default-source (str form)]

    (and (vector? form) (= 2 (count form)) (keyword? (first form))
         (or (string? (second form)) (symbol? (second form))))
    [(first form) (str (second form))]

    :else
    (throw (ex-info (str "#secret takes a name or [:source \"name\"], not " (pr-str form))
                    {:type :configuration-error :form form}))))

(defn- label [source-key secret-name]
  (str (name source-key) ":" secret-name))

(defn resolve-secret
  "The value of the secret `form` names. `opts` are Aero's: `:secret-sources`
   adds or replaces sources, `:default-secret-source` picks the one a bare name
   reads (default `:env`)."
  [{:keys [secret-sources default-secret-source] :or {default-secret-source :env}} form]
  (let [[source-key secret-name] (secret-ref form default-secret-source)
        sources (merge (default-sources) secret-sources)
        source  (or (get sources source-key)
                    (throw (ex-info (str "#secret: unknown source " source-key " for " secret-name
                                         ". Known: " (str/join ", " (sort (keys sources))))
                                    {:type :configuration-error :source source-key :secret secret-name})))
        value   (fetch-secret source secret-name)]
    (cond
      (not (str/blank? value))
      (do (swap! resolved assoc (label source-key secret-name) value)
          value)

      *optional?* nil

      :else
      (throw (ex-info (str "Secret not found: " secret-name " (source " source-key
                           (when-let [dir (:dir source)] (str ", in " dir))
                           "). Provide it, or give it a default with #or.")
                      {:type :configuration-error :source source-key :secret secret-name})))))

(defmethod aero/reader 'secret
  [opts _tag form]
  (resolve-secret opts form))

(defn- placeholder [l] (str "<secret " l ">"))

(defn redact
  "`x` with every value a #secret resolved to replaced by `<secret source:name>`."
  [x]
  (let [labels (set/map-invert @resolved)]
    (if (empty? labels)
      x
      (walk/postwalk #(if-let [l (and (string? %) (labels %))] (placeholder l) %) x))))

(defn unredact
  "The inverse of `redact`, for an edited copy written back."
  [x]
  (let [values @resolved]
    (walk/postwalk #(or (when (string? %)
                          (some-> (re-matches #"<secret (.+)>" %) second values))
                        %)
                   x)))
