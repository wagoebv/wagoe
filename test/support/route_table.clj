(ns support.route-table
  "The real route table, with every framework module switched on, for tests
   that sweep it: the anonymous-caller sweep (BOU-568) and the error-shape
   sweep (BOU-586)."
  (:require [aero.core :as aero]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [integrant.core :as ig]
            [reitit.core :as r]
            [wagoe.config :as config]
            [wagoe.main]
            [wagoe.platform.shell.modules :as modules]
            [wagoe.system-config :as sys-config]))

(defn- catalogue []
  (edn/read-string {:readers {'ig/ref ig/ref}}
                   (slurp (io/file "libs/wagoe-cli/resources/wagoe/cli/modules-catalogue.edn"))))

(defn- snippet-settings [snippet]
  (when (seq (str/trim (or snippet "")))
    (let [f (java.io.File/createTempFile "snippet" ".edn")]
      (try
        (spit f (str "{" snippet "}"))
        (aero/read-config f {:profile :test})
        (finally (.delete f))))))

(def ^:private not-booted-here
  "Framework modules this setup does not switch on, and why."
  {"external" "its adapters need a host or an account; they serve no routes"
   "devtools" "dev-only; the dashboard refuses to boot in :test"})

(defn- every-module-config
  "The test profile with every framework module on, each from the config
   `wagoe add` writes for tests. Storage exposes its HTTP API, which it does
   only when asked. `db` names an in-memory H2 database of the caller's own:
   a sweep may seed users, and the shared one is read by other suites."
  [db]
  (let [base   (config/load-config {:profile :test})
        by-lib (into {} (map (juxt :name identity)) (:modules (catalogue)))
        extra  (into {}
                     (for [[k lib] modules/framework-modules
                           :when (not (contains? not-booted-here lib))
                           :when (not (contains? (:active base) k))
                           :let  [entry    (get by-lib lib)
                                  settings (snippet-settings
                                            (or (not-empty (:test-config-snippet entry))
                                                (:config-snippet entry)))]
                           :when (contains? settings k)]
                       [k (get settings k)]))]
    (-> base
        (update :active merge extra)
        (assoc-in [:active :wagoe/storage :expose-http?] true)
        (update-in [:active :wagoe/h2] assoc
                   :memory false
                   :db     (str "mem:" db ";DB_CLOSE_DELAY=-1")))))

(defmethod ig/init-key ::push-mounted [_ {:keys [routes]}]
  ;; Push's README says to mount its routes in your router; nothing in the
  ;; framework does. Mounted here as an application would.
  {:static [routes]})

(def ^:dynamic *handler* nil)

(defn system-fixture
  "A :once fixture that boots every module on its own H2 database `db` and
   binds `*handler*` to the HTTP handler."
  [db]
  (fn [f]
    (let [ig-cfg (-> (sys-config/ig-config (every-module-config db))
                     (dissoc :wagoe/http-server)
                     (assoc ::push-mounted {:routes (ig/ref :wagoe.push/routes)})
                     (update-in [:wagoe/http-handler :module-routes]
                                (fnil conj []) (ig/ref ::push-mounted)))
          system (ig/init ig-cfg)]
      (try
        (binding [*handler* (:wagoe/http-handler system)]
          (f))
        (finally (ig/halt! system))))))

(def http-methods [:get :head :post :put :patch :delete])

(defn route-table
  "[path method public?] for every endpoint the router serves."
  []
  (let [router (:reitit/router (meta *handler*))]
    (for [[path data] (r/routes router)
          m           http-methods
          :when       (contains? data m)]
      [path m (boolean (or (:public data) (:public (get data m))))])))

(defn concrete-uri
  "`path` with a value in every parameter, so the router matches it."
  [path]
  (-> path
      (str/replace #"\{\*[^}]+\}" "file.txt")
      (str/replace #"\*[^/]*$" "index.html")
      (str/replace #":[^/]+" "00000000-0000-0000-0000-000000000001")))

(def bodies
  "Neither is a valid body for any route that takes one."
  {:empty     "{}"
   :malformed "{not json"})

(defn call
  "The response to `method` on `uri`, as a JSON client sends it. `headers` are
   added to the request's. A handler that throws has been reached, which is
   an answer too: `{:status \"threw <Class>\"}`."
  ([method uri] (call method uri (:empty bodies) {}))
  ([method uri body] (call method uri body {}))
  ([method uri body headers]
   (try
     (*handler* {:request-method method
                 :uri            uri
                 :scheme         :http
                 :server-name    "localhost"
                 :server-port    80
                 :remote-addr    "127.0.0.1"
                 :headers        (merge {"accept"       "application/json"
                                         "content-type" "application/json"
                                         "host"         "localhost"}
                                        headers)
                 :body           (java.io.ByteArrayInputStream. (.getBytes ^String body))})
     (catch Exception e
       {:status (str "threw " (.getSimpleName (class e)))}))))

(defn decoded
  "The body parsed as JSON, or `::not-json`."
  [{:keys [body]}]
  (try (json/parse-string (cond (string? body) body
                                (nil? body)    ""
                                :else          (slurp body))
                          true)
       (catch Exception _ ::not-json)))
