(ns wagoe.test
  "Integration tests for a Wagoe application: boot its own system on the test
   profile with in-process adapters, talk to it over Ring, and make the
   asynchronous parts deterministic (BOU-469).

     (with-test-system [sys my-app.system-config/ig-config]
       (let [c (client sys)]
         (sign-in-as! c {:roles [:orders/editor]})
         (request c :post \"/web/orders\" {:form {:sku \"x\"}})
         (run-pending-jobs! sys)
         (drain-events! sys :orders)))

   Lint the two macros as `clojure.core/let`; the jar exports that to
   clj-kondo."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [integrant.core :as ig]
            [wagoe.config :as config]
            [wagoe.events.shell.adapters.in-memory :as events-memory]
            [wagoe.jobs.shell.worker :as worker]
            [wagoe.platform.core.csrf :as csrf]
            ;; The init-keys every app has: database, HTTP, router, observability.
            [wagoe.platform.shell.system.wiring]
            [wagoe.realtime.ports :as realtime-ports]
            [wagoe.tenant.ports :as tenant-ports]
            [wagoe.user.ports :as user-ports])
  (:import [java.net URLDecoder URLEncoder]
           [java.security SecureRandom]
           [java.sql DriverManager]))

;; =============================================================================
;; System
;; =============================================================================

(def ^:private csrf-secret
  "The test system signs CSRF tokens with this, so the client can too."
  "wagoe-test-csrf-secret-at-least-32-characters")

(defn- in-process
  "`config` with cache, events, realtime and jobs kept in process, and its own
   H2 database so two systems never share rows. Other adapters (email,
   payments, storage, another database) are the app's test profile's to choose."
  [config]
  (let [active (:active config)
        on?    #(contains? active %)]
    (cond-> (reduce #(cond-> %1 (on? %2) (assoc-in [:active %2 :provider] :memory))
                    (assoc-in config [:active :wagoe/http :security :csrf :secret] csrf-secret)
                    [:wagoe/cache :wagoe/events :wagoe/realtime :wagoe/jobs])
      (on? :wagoe/h2)       (update-in [:active :wagoe/h2] assoc
                                       :memory false
                                       :db (str "mem:wagoe_test_" (str/replace (str (random-uuid)) "-" "")
                                                ";DB_CLOSE_DELAY=-1"))
      (and (on? :wagoe/tenant) (on? :wagoe/h2))
      (assoc-in [:active :wagoe/tenant :allow-h2?] true))))

(defn- without-workers
  "No worker threads and no recurring-job ticker: jobs run when the test calls
   run-pending-jobs!. Set on the Integrant map, so it holds when the app
   enables jobs in code."
  [ig-map]
  (cond-> (dissoc ig-map :wagoe/http-server :wagoe/job-scheduler)
    (contains? ig-map :wagoe/job-workers) (assoc-in [:wagoe/job-workers :workers :count] 0)))

(defn- drop-h2!
  "DB_CLOSE_DELAY=-1 keeps an in-memory database until the JVM exits, so each
   system's would outlive it."
  [db]
  (when db
    (with-open [c (DriverManager/getConnection (str "jdbc:h2:" db))
                s (.createStatement c)]
      (.execute s "SHUTDOWN"))))

(defn start-system
  "Boot the application's Integrant system for a test and return it.

   Takes the app's own (fn [config] ig-map), e.g.
   `my-app.system-config/ig-config`, or a map:
     :ig-config  that function
     :profile    config profile to load (default :test)
     :config     (fn [config] config), applied before the in-process overrides

   No HTTP port is bound; `client` calls the handler directly."
  [ig-config-or-opts]
  (let [{:keys [ig-config profile] tweak :config :or {profile :test tweak identity}}
        (if (map? ig-config-or-opts) ig-config-or-opts {:ig-config ig-config-or-opts})
        cfg    (in-process (tweak (config/load-config {:profile profile})))
        h2-db  (get-in cfg [:active :wagoe/h2 :db])
        system (try
                 (ig/init (without-workers (ig-config cfg)))
                 (catch clojure.lang.ExceptionInfo e
                   ;; Integrant leaves what it built to the caller.
                   (some-> (:system (ex-data e)) ig/halt!)
                   (drop-h2! h2-db)
                   (throw e)))
        sent   (atom [])]
    (when-let [bus (get-in system [:wagoe/realtime :bus])]
      (realtime-ports/start-subscriber! bus (fn [envelope] (swap! sent conj envelope) 0)))
    (vary-meta system assoc ::realtime-sent sent ::h2-db h2-db)))

(defn stop-system [system]
  (try
    (ig/halt! system)
    (finally
      (drop-h2! (::h2-db (meta system))))))

(defmacro with-test-system
  "Boot a test system from `ig-config-or-opts` (see `start-system`), bind it
   to `sym`, run `body`, halt."
  [[sym ig-config-or-opts] & body]
  `(let [~sym (start-system ~ig-config-or-opts)]
     (try ~@body (finally (stop-system ~sym)))))

;; =============================================================================
;; Tenants
;; =============================================================================

(defn- short-id [] (subs (str (random-uuid)) 0 8))

(defn call-with-tenant
  "Create a tenant from `input` merged over a random slug and a name, call
   (f tenant), then delete it and its schema."
  [system input f]
  (let [tenant (tenant-ports/create-new-tenant
                (:wagoe/tenant-service system)
                (merge {:slug (str "test-" (short-id))
                        :name "Test tenant"}
                       input))
        repo   (:wagoe/tenant-repository system)]
    (try
      (f tenant)
      (finally
        (tenant-ports/drop-tenant-schema repo (:schema-name tenant))
        (tenant-ports/delete-tenant repo (:id tenant))))))

(defmacro with-test-tenant
  "Run `body` with `sym` bound to a fresh tenant of `system`."
  [[sym system] & body]
  `(call-with-tenant ~system {} (fn [~sym] ~@body)))

;; =============================================================================
;; Jobs, events, realtime
;; =============================================================================

(defn run-pending-jobs!
  "Run every due job on this thread, including jobs those jobs enqueue.
   Returns how many ran."
  [system]
  (worker/run-pending! (get-in system [:wagoe/jobs :workers] {})
                       (:wagoe/job-queue system)
                       (:wagoe/job-store system)
                       (:wagoe/job-registry system)))

(defn drain-events!
  "The events published to `topic` since the last drain, once every subscriber
   has handled them."
  [system topic]
  (events-memory/drain! (:wagoe/events system) topic))

(defn drain-realtime!
  "The realtime envelopes sent since the last drain, oldest first:
   {:route :user|:role|:broadcast|:connection|:topic, :target …, :message …}."
  [system]
  (first (reset-vals! (::realtime-sent (meta system)) [])))

;; =============================================================================
;; Ring client
;; =============================================================================

(defn client
  "A Ring client for `system` that keeps cookies and sends a valid CSRF token
   with every state-changing request. `headers` go on every request, e.g.
   {\"x-tenant-slug\" (:slug tenant)}."
  ([system] (client system {}))
  ([system {:keys [headers]}]
   {:handler (:wagoe/http-handler system)
    :system  system
    :headers (or headers {})
    :cookies (atom {})}))

(defn- encode [s] (URLEncoder/encode (str s) "UTF-8"))

(defn- form-string [m]
  (str/join "&" (for [[k v] m] (str (encode (name k)) "=" (encode v)))))

(def ^:private ^SecureRandom rng (SecureRandom.))

(defn- csrf-header
  "A token bound to what the server binds it to: the X-Session-Token header
   (verbatim), else the session cookie, else the pre-session cookie, which the
   client makes up when it has none. Ring decodes cookie values; so does this."
  [{:keys [cookies]} headers]
  (let [binding (or (get headers "x-session-token")
                    (some-> (or (get @cookies "session-token")
                                (get @cookies "csrf-session")
                                (let [id (str (random-uuid))]
                                  (swap! cookies assoc "csrf-session" id)
                                  id))
                            (URLDecoder/decode "UTF-8")))
        nonce   (byte-array 16)]
    (.nextBytes rng nonce)
    {csrf/header-name (csrf/generate-token csrf-secret binding nonce)}))

(defn- remember-cookies! [cookies response]
  (let [set-cookie (get-in response [:headers "Set-Cookie"])]
    (doseq [line (if (string? set-cookie) [set-cookie] set-cookie)
            :let [[pair & attrs] (str/split line #";\s*")
                  [k v]          (str/split pair #"=" 2)
                  expired?       (or (str/blank? v)
                                     (some #(re-matches #"(?i)max-age=0" %) attrs))]]
      (swap! cookies #(if expired? (dissoc % k) (assoc % k v))))))

(defn request
  "Send `method` `uri` through the app's handler and return the Ring response,
   its body a string. Opts: :form (map, urlencoded), :json (data), :body
   (string), :query (map), :headers."
  ([c method uri] (request c method uri {}))
  ([{:keys [handler headers cookies] :as c} method uri {:keys [form json body query] :as opts}]
   (let [body     (cond form (form-string form)
                        (some? json) (json/generate-string json)
                        :else body)
         ;; A query written into the uri is a query, not part of the path.
         [uri uri-query] (str/split uri #"\?" 2)
         query-string    (str/join "&" (remove str/blank? [uri-query (some-> query form-string)]))
         ;; Before reading the jar: it may add the pre-session cookie.
         csrf     (when (#{:post :put :patch :delete} method)
                    (csrf-header c (merge headers (:headers opts))))
         jar      @cookies
         response (handler
                   (cond-> {:request-method method
                            :uri            uri
                            :scheme         :http
                            :server-name    "localhost"
                            :server-port    80
                            :remote-addr    "127.0.0.1"
                            :headers        (cond-> (merge {"host" "localhost"} headers)
                                              (seq jar)    (assoc "cookie" (str/join "; " (for [[k v] jar] (str k "=" v))))
                                              form         (assoc "content-type" "application/x-www-form-urlencoded")
                                              (some? json) (assoc "content-type" "application/json"
                                                                  "accept" "application/json")
                                              true         (merge csrf (:headers opts)))}
                     (seq query-string) (assoc :query-string query-string)
                     body  (assoc :body (java.io.ByteArrayInputStream. (.getBytes ^String body "UTF-8")))))]
     (remember-cookies! cookies response)
     (update response :body #(if (or (nil? %) (string? %)) % (slurp %))))))

(defn sign-in!
  "Sign in through the login form, as a browser does. The client keeps the
   session. Returns the response; throws when no session came back."
  [c email password]
  (swap! (:cookies c) dissoc "session-token")
  (let [response (request c :post "/web/login" {:form {:email email :password password}})]
    (when-not (get @(:cookies c) "session-token")
      (throw (ex-info (str "Sign-in as " email " failed with status " (:status response))
                      {:type :unauthorized :email email :status (:status response)})))
    response))

;; =============================================================================
;; Users and their rights
;; =============================================================================

(defn create-user!
  "Register a user and give them `:roles` through a group of their own.
   Each role is a keyword, or {:role kw :scope map} to narrow it. Opts:
   :roles, :email, :name, :password, :role (`:admin` for a platform admin).
   Returns the user with its :password."
  [system {:keys [roles] :as opts}]
  ;; The `!` passes a policy that requires a special character.
  (let [password (or (:password opts) (str "Test-Passw0rd!-" (random-uuid)))
        user     (user-ports/register-user
                  (:wagoe/user-service system)
                  (merge {:email (str "test-" (short-id) "@example.com")
                          :name  "Test user"
                          :role  :user}
                         (select-keys opts [:email :name :role])
                         {:password password}))]
    (when (seq roles)
      ;; One call checks every role before writing anything.
      (user-ports/import-groups! (:wagoe/user-group-service system)
                                 [{:name    (str "test-" (:id user))
                                   :members [(:id user)]
                                   :roles   (mapv #(if (map? %) % {:role %}) roles)}]
                                 {}))
    (assoc user :password password)))

(defn sign-in-as!
  "Create a user as `create-user!` does and sign `c` in as them. Returns the
   user.

     (sign-in-as! c {:roles [:orders/editor]})"
  [c opts]
  (let [user (create-user! (:system c) opts)]
    (sign-in! c (:email user) (:password user))
    user))
