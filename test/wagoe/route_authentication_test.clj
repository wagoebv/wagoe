(ns wagoe.route-authentication-test
  "Every route requires a signed-in user unless its data says `:public true`.

   Built from the real route table, with every framework module switched on,
   because the hole this closes was in the modules nobody guarded by hand:
   tenants, memberships and search answered anonymous callers (BOU-568). A
   route added without `:public` and without a guard fails here."
  (:require [aero.core :as aero]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
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
  "Framework modules this test does not switch on, and why."
  {"external" "its adapters need a host or an account; they serve no routes"
   "devtools" "dev-only; the dashboard refuses to boot in :test"})

(defn- every-module-config
  "The test profile with every framework module on, each from the config
   `wagoe add` writes for tests. Storage exposes its HTTP API, which it does
   only when asked."
  []
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
        ;; A database of its own: the sweep calls /test/reset, which seeds
        ;; users, and the shared in-memory one is read by other suites.
        (update-in [:active :wagoe/h2] assoc
                   :memory false
                   :db     "mem:route-authentication;DB_CLOSE_DELAY=-1"))))

(defmethod ig/init-key ::push-mounted [_ {:keys [routes]}]
  ;; Push's README says to mount its routes in your router; nothing in the
  ;; framework does. Mounted here as an application would.
  {:static [routes]})

(def ^:dynamic *handler* nil)

(defn- with-system [f]
  (let [cfg    (every-module-config)
        ig-cfg (-> (sys-config/ig-config cfg)
                   (dissoc :wagoe/http-server)
                   (assoc ::push-mounted {:routes (ig/ref :wagoe.push/routes)})
                   (update-in [:wagoe/http-handler :module-routes]
                              (fnil conj []) (ig/ref ::push-mounted)))
        system (ig/init ig-cfg)]
    (try
      (binding [*handler* (:wagoe/http-handler system)]
        (f))
      (finally (ig/halt! system)))))

(use-fixtures :once with-system)

(def ^:private http-methods [:get :head :post :put :patch :delete])

(defn- route-table
  "[path method public?] for every endpoint the router serves."
  []
  (let [router (:reitit/router (meta *handler*))]
    (for [[path data] (r/routes router)
          m           http-methods
          :when       (contains? data m)]
      [path m (boolean (or (:public data) (:public (get data m))))])))

(defn- concrete-uri
  "`path` with a value in every parameter, so the router matches it."
  [path]
  (-> path
      (str/replace #"\{\*[^}]+\}" "file.txt")
      (str/replace #"\*[^/]*$" "index.html")
      (str/replace #":[^/]+" "00000000-0000-0000-0000-000000000001")))

(def ^:private bodies
  "What an anonymous caller sends. Neither is a valid body for any route that
   takes one, so a route that checks the body before the caller would answer
   400 and name the fields it wanted."
  {:empty     "{}"
   :malformed "{not json"})

(defn- anonymous
  "The response to an anonymous `method` on `path`. A handler that throws has
   been reached, which is what matters here, so that is an answer too."
  ([method path] (anonymous method path (:empty bodies)))
  ([method path body]
   (try
     (*handler* {:request-method method
                 :uri            (concrete-uri path)
                 :scheme         :http
                 :server-name    "localhost"
                 :server-port    80
                 :remote-addr    "127.0.0.1"
                 :headers        {"accept"       "application/json"
                                  "content-type" "application/json"
                                  "host"         "localhost"}
                 :body           (java.io.ByteArrayInputStream. (.getBytes ^String body))})
     (catch Exception e
       {:status (str "threw " (.getSimpleName (class e)))}))))

(defn- decoded [{:keys [body]}]
  (try (json/parse-string (if (string? body) body (slurp body)) true)
       (catch Exception _ ::not-json)))

(defn- sent-to-login? [{:keys [status headers]}]
  (and (= 302 status)
       (str/starts-with? (str (get headers "Location")) "/web/login?return-to=")))

(defn- refused?
  "Refused the way the framework refuses everywhere: a login redirect for a
   page, and for anything else a 401 whose body actually reached the wire."
  [path response]
  (if (str/starts-with? path "/web")
    (sent-to-login? response)
    (let [{:keys [error correlation-id]} (decoded response)]
      (and (= 401 (:status response))
           (= "unauthorized" error)
           (some? correlation-id)
           (= correlation-id (get-in response [:headers "X-Correlation-ID"]))))))

(deftest ^:integration ^:security every-route-refuses-anonymous-callers-unless-public
  (let [table (route-table)]
    (testing "the table was built with the modules this is about"
      (doseq [p ["/api/v1/tenants" "/api/v1/memberships/:id/accept"
                 "/api/v1/search/:index-id" "/web/admin/search"
                 "/api/v1/storage/upload" "/api/push/devices"]]
        (is (some #(= p (first %)) table) (str p " is not in the route table"))))

    (doseq [[label body] bodies]
      (testing (str "with a " (name label) " body")
        (let [open (for [[path m public?] table
                         :when (not public?)
                         :let  [response (anonymous m path body)]
                         :when (not (refused? path response))]
                     [(str/upper-case (name m)) path (:status response)])]
          (is (zero? (count open))
              (str "Answered an anonymous caller without being :public true:\n"
                   (str/join "\n" (map #(str "  " (str/join " " %)) open)))))))))

(deftest ^:integration ^:security public-routes-answer-anonymous-callers
  (let [public (filter #(nth % 2) (route-table))]
    (is (seq public) "no :public route at all; login could not work")
    (doseq [[path m _] public]
      (let [response (anonymous m path)]
        (is (not (or (= 401 (:status response)) (sent-to-login? response)))
            (str (str/upper-case (name m)) " " path " is :public but refused an anonymous caller"))))))
