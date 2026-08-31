(ns wagoe.platform.shell.http.reitit-router
  "Compiles the application's route data into a Ring handler.

   Modules emit Reitit route data and this namespace hands it to Reitit
   (ADR-037), so what is left here is behaviour rather than translation:
   exception handling, the coercion error shape, the Swagger routes, gzip, and
   the interceptor stack each route is decorated with."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [malli.error :as me]
            [muuntaja.core :as m]
            [reitit.coercion.malli :as malli-coercion]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.swagger :as swagger]
            [reitit.swagger-ui :as swagger-ui]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.not-modified :refer [not-modified-response]]
            [ring.middleware.resource :refer [resource-request]]
            [ring.util.codec :as codec]
            [ring.util.io :as ring-io]
            [ring.util.request :as ring-request]
            [wagoe.platform.shell.http.interceptors :as http-interceptors])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.security MessageDigest]
           [java.util.zip GZIPOutputStream]))

;; =============================================================================
;; Symbol Resolution
;; =============================================================================

(defn- resolve-spec
  "A symbol resolved to its value, or the value itself.

   Handlers no longer come through here — they are vars in the route data, which
   is the point of ADR-037. What remains are interceptor specs and the
   middleware an application can name in its `:wagoe/router` config, both of
   which may still be written as qualified symbols."
  [spec]
  (if (symbol? spec)
    ;; Dereferenced, not the var. `requiring-resolve` hands back a Var, and
    ;; Reitit's IntoMiddleware protocol has no implementation for one — so
    ;; naming middleware by symbol failed with "No implementation of method:
    ;; :into-middleware ... for class: clojure.lang.Var". Nobody had hit it
    ;; because the setting never reached the router at all (BOU-357).
    ;;
    ;; Two ways a symbol from config fails, and they throw differently:
    ;; a namespace that does not exist raises FileNotFoundException out of the
    ;; `require`, with no ex-data; a namespace that loads without the var just
    ;; returns nil. Both are the same mistake in a config file, so both leave
    ;; here as :configuration-error (ADR-022 / pitfall #7).
    (let [v (try
              (requiring-resolve spec)
              (catch Exception e
                (throw (ex-info (str "Could not load the namespace for " spec)
                                {:type   :configuration-error
                                 :symbol spec}
                                e))))]
      (if v
        (deref v)
        (throw (ex-info (str "Could not resolve symbol " spec)
                        {:type :configuration-error :symbol spec}))))
    spec))

(defn- resolve-middleware-fns
  "Resolve a vector of middleware specs to functions."
  [specs]
  (mapv resolve-spec (or specs [])))

(defn- resolve-interceptors
  "Resolve vector of interceptor specs to actual interceptor instances.
   
   Interceptor specs can be:
   - Qualified symbols referencing interceptor definitions
   - Functions that return interceptors
   - Interceptor maps directly
   
   Args:
     interceptor-specs - Vector of interceptor specs
     
   Returns:
     Vector of interceptor maps"
  [interceptor-specs]
  (when (seq interceptor-specs)
    (mapv (fn [spec]
            (cond
              ;; Symbol - resolve and call if function
              (symbol? spec)
              (let [resolved (resolve-spec spec)]
                (if (fn? resolved)
                  (resolved)  ; Call function to get interceptor
                  resolved))  ; Already an interceptor map

              ;; Function - call to get interceptor
              (fn? spec)
              (spec)

              ;; Map - assume it's an interceptor
              (map? spec)
              spec

              :else
              (throw (ex-info "Invalid interceptor spec"
                              {:spec spec
                               :type (type spec)}))))
          interceptor-specs)))

(defn- interceptors->middleware
  "Convert vector of HTTP interceptors to a Ring middleware function.
   
   This adapter allows interceptors to be used in Reitit's :middleware chain.
   The middleware function runs the interceptor pipeline and extracts the response.
   
   Args:
     interceptors - Vector of interceptor maps
     system - Observability services map {:logger :metrics-emitter :error-reporter}
     
   Returns:
     Ring middleware function"
  [interceptors system]
  (fn [handler]
    (fn [request]
      ;; Use run-http-interceptors which handles the full pipeline
      (http-interceptors/run-http-interceptors handler interceptors request system))))

;; =============================================================================
;; Route Conversion
;; =============================================================================

;; =============================================================================
;; Router Creation
;; =============================================================================

(defn- field-names-only
  "`{:email [\"invalid\"]}` — which fields, and nothing about why.

   What production returns. `me/humanize` renders the constraints of the schema into
   its messages: an `[:enum \"admin\" \"superuser\" \"internal-auditor\"]` comes back
   as \"should be either admin, superuser or internal-auditor\", so POSTing junk
   to an open endpoint enumerates roles nobody was told about. Bounds and
   regexes go the same way."
  [humanized]
  (when (map? humanized)
    (into {} (map (fn [[k _]] [k ["invalid"]])) humanized)))

(defn- humanize-coercion-errors
  "Field-level messages for a coercion failure — `{:email [\"missing required key\"]}`.

   Built here rather than read off the exception: reitit hands us `:errors`,
   `:schema` and `:value`, and `:errors` carries schema fragments. Malli turns
   the three into a map keyed by the fields the caller got wrong.

   `dev?` decides how much of the why is returned. The keys are what the caller sent
   either way; the messages describe the schema, and that is ours."
  [data dev?]
  (let [humanized (try
                    (me/humanize (select-keys data [:schema :value :errors]))
                    (catch Throwable _ nil))]
    (or (if dev? humanized (field-names-only humanized))
        {})))

(defn- coercion-error-response
  "400 for a request the client got wrong, naming the fields.

   Which fields went wrong is what the caller sent. Why they are wrong is the schema
   talking, so the full message is dev-only — the same gate the BND block uses."
  [system e request]
  (let [dev  (http-interceptors/dev-error-info system e)
        ;; This response never reaches the interceptor stack — it is produced
        ;; outside it — so the correlation header every other response carries
        ;; has to be set here. Without it a client reporting "your API 400s me"
        ;; has no id to hand to whoever reads the log.
        cid  (or (get-in request [:headers "x-correlation-id"])
                 (str (java.util.UUID/randomUUID)))
        body (cond-> {:error         "validation-error"
                      :message       "Request validation failed"
                      :details       (humanize-coercion-errors (ex-data e) (some? dev))
                      :correlationId cid}
               dev (assoc :dev dev))]
    {:status  400
     :headers {"X-Correlation-ID" cid}
     :body    body}))

(defn- server-error-response
  "A 500 that says nothing. What went wrong goes to the log."
  []
  {:status 500
   :body   {:error   "internal-error"
            :message "Internal Server Error"}})

(defn- create-exception-middleware
  "Reitit exception middleware, placed between response formatting and request
   decoding.

   Position is the whole point, and it was wrong twice over. Reitit applies a
   `:middleware` vector first-to-outermost, and this sat last — inside
   `coerce-request` — so a request that failed coercion threw past every handler
   and the app answered 500 to a malformed request (BOU-321).

   Outermost would fix that and break something else: outside
   `format-response`, an error body can only be a pre-encoded string, and a
   client asking for transit or EDN would get JSON it cannot read. Sitting just
   inside `format-response` catches request decoding and coercion while its map
   bodies are still negotiated like any other response.

   The typed errors the framework raises are handled by the interceptor stack
   closer to the handler; what reaches here is what that stack cannot see."
  [config]
  (let [system (or (:system config) {})
        log-error (fn [e request msg]
                    ;; Not the throwable: reitit puts the whole Ring request in
                    ;; the ex-data of a coercion failure — headers, cookies,
                    ;; the match tree — and logging it writes the Authorization
                    ;; header of the caller into the log on every occurrence.
                    (log/error msg
                               {:uri            (:uri request)
                                :method         (some-> (:request-method request) name)
                                :correlation-id (get-in request [:headers "x-correlation-id"])
                                :exception      (some-> ^Throwable e class .getName)
                                :message        (ex-message e)}))]
    (exception/create-exception-middleware
     (merge
      exception/default-handlers
      {:reitit.coercion/request-coercion
       (fn [e request] (coercion-error-response system e request))

       ;; A response that does not match its own schema is a bug in the app, not
       ;; in the request: generic 500, details in the log only.
       :reitit.coercion/response-coercion
       (fn [e request]
         (log-error e request "Response coercion failed")
         (server-error-response))

       ::exception/default
       (fn [e request]
         (log-error e request "Unhandled exception at the HTTP boundary")
         (server-error-response))}))))

(defn- create-default-middleware
  "Create default middleware stack for Reitit router.

  Returns:
    Vector of middleware for Reitit router"
  [config]
  [;; Query params & form params
   parameters/parameters-middleware
   ;; Content negotiation
   muuntaja/format-negotiate-middleware
   ;; Encoding response body
   muuntaja/format-response-middleware
   ;; Exception handling — after response formatting so its bodies are
   ;; negotiated, before request decoding and coercion so their failures are
   ;; caught rather than thrown past everything.
   (create-exception-middleware config)
   ;; Decoding request body
   muuntaja/format-request-middleware
   ;; Coercing request parameters
   coercion/coerce-request-middleware
   ;; Coercing response bodies
   coercion/coerce-response-middleware])

;; =============================================================================
;; Route conflicts
;; =============================================================================

(defn- parameter-segment?
  "Whether a path segment is a parameter (`:id`) or a catch-all (`*rest`)."
  [seg]
  (boolean (re-matches #"[:*].*" seg)))

(defn- decided-by-precedence?
  "Whether Reitit resolves this pair deterministically, so it is safe to keep.

   True for the shape every REST router has: a literal segment beside a
   parameter in the same position, like `/web/users/new` and `/web/users/:id`.
   Reitit matches the literal first, always, so which handler runs is not in
   question — the pair is reported only because the paths overlap.

   False when both sides are parameters in the same position — `/users/:id`
   and `/users/:uid` are the same route written twice, and which one answers
   depends on the order they happened to be concatenated in. That is the case
   worth failing a boot over.

   Catch-alls count as parameters and so are never treated as decided: `*path`
   swallowing a sibling is exactly the kind of accident this should report."
  [path-a path-b]
  (let [a (str/split path-a #"/")
        b (str/split path-b #"/")]
    (and (= (count a) (count b))
         (every? (fn [[x y]]
                   (or (= x y)
                       ;; Exactly one side parameterised — the literal wins.
                       (and (not= (parameter-segment? x) (parameter-segment? y))
                            (not (str/starts-with? x "*"))
                            (not (str/starts-with? y "*")))))
                 (map vector a b)))))

(defn- report-ambiguous-routes
  "Reitit's `:conflicts` handler: throw on pairs precedence does not decide.

   Conflict detection was off entirely — `:conflicts nil` — so two routes that
   could both match a request built a router without complaint and the first
   one silently won (BOU-356). Turning it on wholesale was not an option
   either: the live route table has six overlapping pairs, all of them the
   literal-beside-parameter shape that works correctly, and Reitit's per-route
   `:conflicting true` opt-out has to be set on *both* sides of a pair — which
   would exempt those routes from detection against everything else too.

   So the decision is made here, by shape, and no route has to opt out."
  [conflicts]
  (when-let [ambiguous (seq (for [[route others] conflicts
                                  other others
                                  :let  [a (first route) b (first other)]
                                  :when (not (decided-by-precedence? a b))]
                              [a b]))]
    (throw (ex-info
            (str "Routes that can both match the same request:\n"
                 (str/join "\n" (map (fn [[a b]] (str "  " a "\n  " b)) ambiguous))
                 "\n\nWhich one answers depends on the order the route table was\n"
                 "assembled, which is not something to rely on. Give them distinct\n"
                 "paths, or merge them into one route.")
            {:type   :wagoe/ambiguous-routes
             :routes (mapv (fn [[a b]] {:a a :b b}) ambiguous)}))))

(def ^:private coercions
  "The coercion implementations an application may name in `:wagoe/router`.

   One entry, and that is the honest size of it: Malli is the framework's
   validation vocabulary everywhere else, and a `:spec` entry here would
   promise a second implementation nobody maintains — the shape ADR-037
   removed from routing. The map exists so `:coercion :malli` in a config file
   means something and `:coercion :sepc` says so."
  {:malli malli-coercion/coercion})

(defn- resolve-coercion
  "The coercion for `v`: a name from `coercions`, an instance, or nil.

   `:coercion :malli` sat in every generated config.edn and reached nothing —
   the router built its own options and this component was passed as a
   protocol receiver that ADR-037 then deleted. Making it live means a wrong
   value has to fail rather than be ignored (BOU-357)."
  [v]
  (cond
    (nil? v)         (:malli coercions)
    (keyword? v)     (or (get coercions v)
                         (throw (ex-info
                                 (str "Unknown :coercion " v ". Supported: "
                                      (str/join ", " (sort (keys coercions))) ".")
                                 {:type       :configuration-error
                                  :key        :coercion
                                  :value      v
                                  :supported  (vec (sort (keys coercions)))})))
    :else            v))

(defn- create-router-options
  "Create Reitit router options from config.
   
   Args:
     config - Router configuration map with keys:
              :middleware - Additional middleware vector (symbols resolved)
              :coercion - Coercion configuration (defaults to Malli)
              :muuntaja - Muuntaja configuration (defaults to json/edn/transit)
              :conflicts - Conflict handler (defaults to report-ambiguous-routes)
              
   Returns:
     Map of Reitit router options"
  [config]
  (let [default-middleware (create-default-middleware config)
        custom-middleware (resolve-middleware-fns (:middleware config))
        all-middleware (into default-middleware custom-middleware)]
    {:data {:coercion (resolve-coercion (:coercion config))
            :muuntaja (or (:muuntaja config) m/instance)
            :middleware all-middleware}
     ;; On by default since BOU-356. `/users/new` beside `/users/:id` is
     ;; allowed — Reitit matches the literal first — while two routes neither
     ;; precedence nor anything else decides between fail the boot.
     :conflicts (get config :conflicts report-ambiguous-routes)}))

(defn- create-default-handler
  "Create default handler for routes not matched by router.
  
  Returns:
    Ring handler function"
  []
  (ring/create-default-handler
   {:not-found (constantly {:status 404
                            :headers {"Content-Type" "application/json"}
                            :body (json/generate-string
                                   {:error "Not Found"
                                    :message "The requested resource was not found"})})
    :method-not-allowed (constantly {:status 405
                                     :headers {"Content-Type" "application/json"}
                                     :body (json/generate-string
                                            {:error "Method Not Allowed"
                                             :message "The HTTP method is not allowed for this resource"})})
    :not-acceptable (constantly {:status 406
                                 :headers {"Content-Type" "application/json"}
                                 :body (json/generate-string
                                        {:error "Not Acceptable"
                                         :message "The requested content type is not supported"})})}))

;; =============================================================================
;; Swagger Documentation Routes
;; =============================================================================

(defn- create-swagger-routes
  "Create Swagger documentation routes.

   Returns:
     Vector of Swagger route specs:
     - /swagger.json - OpenAPI/Swagger specification
     - /api-docs/* - Swagger UI interface

   Swagger UI will be available at:
     http://localhost:PORT/api-docs/index.html

   Configuration:
     Swagger metadata (title, version, description) is configured via :swagger-data
     in the router config passed to compile-routes.

   Example swagger-data:
     {:info {:title \"Wagoe API\"
             :description \"Wagoe Framework REST API\"
             :version \"0.1.0\"}
      :tags [{:name \"users\" :description \"User management\"}
             {:name \"inventory\" :description \"Inventory tracking\"}]}"
  [swagger-data]
  [["/swagger.json"
    {:get {:no-doc true
           :swagger swagger-data
           :handler (swagger/create-swagger-handler)}}]

   ["/api-docs/*"
    {:get {:no-doc true
           :handler (swagger-ui/create-swagger-ui-handler
                     {:url "/swagger.json"
                      :config {:validatorUrl nil}})}}]])

;; =============================================================================
;; Gzip Compression Middleware
;; =============================================================================

(defn- accepts-gzip?
  "Returns true when the client advertises gzip support via Accept-Encoding."
  [request]
  (str/includes? (get-in request [:headers "accept-encoding"] "") "gzip"))

(defn- gzip-body
  "Compress a byte-array or String body using gzip.
   Returns a ByteArrayInputStream wrapping the compressed bytes."
  [body]
  (let [out     (ByteArrayOutputStream.)
        bytes   (cond
                  (string? body) (.getBytes ^String body "UTF-8")
                  (bytes? body)  body
                  :else          nil)]
    (when bytes
      (with-open [gzip (GZIPOutputStream. out)]
        (.write gzip ^bytes bytes))
      (ByteArrayInputStream. (.toByteArray out)))))

(defn- wrap-gzip
  "Gzip-compress responses for clients that send Accept-Encoding: gzip.
   Only compresses String or byte-array bodies; other body types pass through."
  [handler]
  (fn [request]
    (let [response (handler request)]
      (if (and response (accepts-gzip? request))
        (if-let [compressed (gzip-body (:body response))]
          (-> response
              (assoc :body compressed)
              (assoc-in [:headers "Content-Encoding"] "gzip")
              (assoc-in [:headers "Vary"] "Accept-Encoding"))
          response)
        response))))

;; =============================================================================
;; Static Asset Caching Middleware
;; =============================================================================

(defn- asset-request?
  "GET/HEAD for a URI with a file extension — the only shape a classpath asset
   can have. Checked before the classloader, so a resource lookup does not
   precede every API call too."
  [request]
  (and (contains? #{:get :head} (:request-method request))
       (str/includes? (or (:uri request) "") ".")))

(defn- digest-stream
  "SHA-256 of everything `in` yields, read in chunks and never held whole.

   Truncated to 16 bytes: this is a cache validator, not a signature. Collisions
   only matter to an attacker who can already write the assets."
  [^java.io.InputStream in]
  (let [md  (MessageDigest/getInstance "SHA-256")
        buf (byte-array 8192)]
    (loop []
      (let [n (.read in buf)]
        (when (pos? n)
          (.update md buf 0 n)
          (recur))))
    (->> (.digest md) (take 16) (map #(format "%02x" (bit-and % 0xff))) (apply str))))

(def ^:private max-cached-digests
  "Ceiling on `packaged-digests`, which is filled from request paths.

   The key is derived the way ring derives it, so the spellings of one asset
   collapse to one entry — but a bound is what makes the size independent of
   anything a caller sends, rather than dependent on having anticipated every
   alias. An application serves tens of assets; past this the map is dropped and
   refills, so the worst case is the hashing cost from before it was cached."
  512)

(def ^:private packaged-digests
  "Digests of resources that cannot change while the process runs.

   A jar entry is fixed once the archive is open, so hashing it again can only
   give the same answer, and a revalidation would otherwise re-read the whole
   asset to answer a 304 that carries no body. Directory-backed resources are
   deliberately absent: those are the ones a developer edits under a running
   server, and a remembered digest there would serve the staleness this whole
   change is about."
  (atom {}))

(defn- remember-digest!
  [path digest]
  (swap! packaged-digests
         (fn [m] (assoc (if (>= (count m) max-cached-digests) {} m) path digest)))
  digest)

(defn- cache-headers
  "Mark a static asset cacheable but always revalidated, with a digest of its
   bytes as the validator.

   `no-cache` means \"store it, but ask before reusing it\" — not \"do not
   store\". With a validator the ask is a 304 carrying no body, so the bytes
   travel once.

   Deliberately not `max-age`/`immutable`: these filenames carry no content
   hash. `app.css` changes meaning under a fixed name, so any lifetime is a
   window in which a shipped fix cannot reach a browser that already holds the
   old one. Nor size-and-mtime, which the ticket first proposed — a rebuild
   producing a same-length file in the same second reuses the validator and the
   304 path keeps serving the old asset.

   Nothing is held in memory whole. A file is hashed by streaming it, leaving
   ring's own body to be streamed out in turn. Hashing a jar entry consumes the
   stream, so `reopen` repeats the lookup that produced this response — rather
   than rebuilding a path, which would mean re-deriving ring's decoding and
   traversal rules."
  [response path reopen]
  (let [body    (:body response)
        with-cc (assoc-in response [:headers "Cache-Control"] "no-cache")
        etag    (fn [r d] (assoc-in r [:headers "ETag"] (str "\"" d "\"")))]
    (cond
      (instance? java.io.File body)
      (etag with-cc (with-open [in (io/input-stream ^java.io.File body)]
                      (digest-stream in)))

      (instance? java.io.InputStream body)
      (if-let [cached (and path (get @packaged-digests path))]
        ;; Nothing to read: ring's stream is the body, and the writer closes it.
        (etag with-cc cached)
        (let [d (with-open [^java.io.InputStream in body] (digest-stream in))]
          ;; No path means no key to remember it under — hash it again next time
          ;; rather than let unrelated resources share an entry.
          (when path (remember-digest! path d))
          (when-let [fresh (:body (reopen))]
            (-> with-cc (assoc :body fresh) (etag d)))))

      :else with-cc)))

(defn- without-header
  "Drop a header from a request or response map, whatever casing it was written
   in — ring lower-cases request headers, but nothing guarantees a producer did."
  [m header]
  (update m :headers
          (fn [headers]
            (into {} (remove #(.equalsIgnoreCase ^String (name (key %)) header)) headers))))

(defn- drop-body-for-head
  "A HEAD carries no body. Closed before it is dropped: once a path's digest is
   remembered, a packaged asset's stream is handed through untouched, and
   replacing it with nil is the last reference anything holds — so every HEAD
   would leak a jar entry. A File body is not Closeable and `close!` ignores it."
  [response request]
  (if (= :head (:request-method request))
    (do (ring-io/close! (:body response))
        (assoc response :body nil))
    response))

(defn- wrap-static-resources
  "Serve classpath resources from resources/public/, with revalidation headers.

   `resource-request` is called directly rather than through `wrap-resource`,
   which answers `(or resource (handler request))` — a hit returns without ever
   calling what it wraps, so a cache middleware placed outside could not run for
   the only responses it was written for. Setting the headers where the resource
   is produced removes the ordering question instead of answering it (BOU-389)."
  [handler]
  (fn [request]
    (or (when (asset-request? request)
          ;; Looked up as a GET even for a HEAD: ring answers a HEAD with a nil
          ;; body, and the validator is a digest of the body, so a HEAD would
          ;; otherwise carry no ETag while the GET it stands in for does.
          (let [lookup #(resource-request (assoc request :request-method :get) "public")
                ;; The path ring itself resolves, derived the way ring derives
                ;; it, so every spelling of one asset lands on one cache entry:
                ;; `/js/forms.js` and `/js/%66orms.js` are the same resource, and
                ;; an unauthenticated caller can mint encodings without limit.
                ;; A malformed escape throws out of the decoder; no key then,
                ;; which costs a rehash rather than a wrong answer.
                path   (try (codec/url-decode (ring-request/path-info request))
                            (catch Exception _ nil))]
            (some-> (lookup)
                    (cache-headers path lookup)
                    ;; The digest decides, so If-Modified-Since gets no vote.
                    ;; Ring resolves a 304 from whichever validators the request
                    ;; carries, and for If-Modified-Since alone that is mtime —
                    ;; a rebuild leaving mtime untouched would answer 304 with
                    ;; the old bytes, and a browser holding an asset cached
                    ;; before any ETag existed revalidates exactly that way.
                    ;; Stripped from the request rather than dropping
                    ;; Last-Modified from the response, which would also deny a
                    ;; 304 to a client sending both validators, ETag included.
                    (not-modified-response (without-header request "if-modified-since"))
                    (drop-body-for-head request))))
        (handler request))))

(def ^:private http-methods
  "The endpoint keys Reitit route data can carry."
  #{:get :post :put :delete :patch :head :options :trace})

(defn- decorate-endpoint
  "Add the framework's default HTTP interceptor stack to one Reitit endpoint.

   This is the one thing the normalized format did that Reitit does not do for
   us: every endpoint gets `default-http-interceptors` — security headers, CSRF,
   rate limiting, metrics, error handling — unless it says `:skip-interceptors?`,
   which genuinely-internal endpoints like the health checks do.

   Appended last, so interceptors sit closest to the handler and still see a
   fully prepared request (session, body, coercions) (BOU-331)."
  [endpoint system]
  (let [;; `{:get my-handler}` is Reitit's own shorthand for
        ;; `{:get {:handler my-handler}}`. Left as-is it has nowhere to hang
        ;; middleware, so the endpoint would quietly serve without security
        ;; headers, CSRF or rate limiting — a hole that opens by writing less.
        {:keys [middleware interceptors skip-interceptors?] :as endpoint}
        (if (map? endpoint) endpoint {:handler endpoint})

        route-interceptors (or (resolve-interceptors interceptors) [])
        all-interceptors   (if skip-interceptors?
                             route-interceptors
                             (vec (concat http-interceptors/default-http-interceptors
                                          route-interceptors)))]
    (cond-> (dissoc endpoint :interceptors :skip-interceptors?)
      (seq all-interceptors)
      (assoc :middleware (vec (concat (or middleware [])
                                      [(interceptors->middleware all-interceptors system)]))))))

(defn- decorate-reitit-route
  "Walk a Reitit route vector, decorating every endpoint it contains.

   `[path data & children]`, where `data` may carry endpoints directly and each
   child is another route vector. Route-level `:middleware`, `:name`, coercion
   and nesting are Reitit's own and pass through untouched."
  [route system]
  (if-not (vector? route)
    route
    (let [[path data & children] route
          data'    (if (map? data)
                     (reduce-kv (fn [m k v]
                                  (assoc m k (if (http-methods k)
                                               (decorate-endpoint v system)
                                               v)))
                                {} data)
                     data)
          children' (mapv #(decorate-reitit-route % system) (if (map? data) children
                                                                (cons data children)))]
      (into (if (map? data) [path data'] [path]) children'))))

;; =============================================================================
;; Compiling routes
;; =============================================================================

(defn compile-routes
  "Reitit route data in, a Ring handler out.

   A plain function rather than a protocol method: `IRouter` had one
   implementation and existed to make the normalized format swappable, and both
   are gone (ADR-037). The Reitit router is attached to the handler's metadata
   so devtools can read the route table without unwrapping middleware."
  [route-specs config]
  (let [;; Extract observability services from config (if provided)
        system (:system config)

          ;; Extract Swagger configuration (optional)
        swagger-enabled? (get config :swagger-enabled true)  ; Enabled by default
        swagger-data (or (:swagger-data config)
                         {:info {:title "Wagoe API"
                                 :description "Wagoe Framework REST API"
                                 :version "0.1.0"}})

        reitit-routes (mapv #(decorate-reitit-route % system) route-specs)

          ;; Add Swagger routes if enabled
        all-routes (if swagger-enabled?
                     (into (create-swagger-routes swagger-data) reitit-routes)
                     reitit-routes)

          ;; Create router options
        router-opts (create-router-options config)

          ;; Create Reitit router
        router (ring/router all-routes router-opts)

          ;; Create default handler for unmatched routes
        default-handler (create-default-handler)

          ;; Wrap handler with middlewares (outermost last):
          ;; 1. Cookies middleware - parse and set cookies
          ;; 2. Static resource middleware - asset-shaped GET/HEAD requests only
          ;; Query/form params are parsed by reitit's parameters-middleware
          ;; (create-default-middleware) — a global wrap-params here would
          ;; parse them a second time for every request.
        handler (-> (ring/ring-handler router default-handler)
                    (wrap-gzip)
                    (wrap-static-resources)
                    (wrap-cookies))]

      ;; Store the Reitit router in metadata so devtools can extract route info
      ;; from the wrapped handler without needing to unwrap middleware layers.
    (with-meta handler {:reitit/router router})))

(comment
  ;; Reitit route data in, Ring handler out.
  (def example-routes
    [["/users"
      {:get  {:handler (fn [_] {:status 200 :body []})
              :summary "List users"
              :parameters {:query [:map
                                   [:limit  {:optional true} :int]
                                   [:offset {:optional true} :int]]}}
       :post {:handler (fn [_] {:status 201 :body {}})
              :summary "Create user"}}]])

  (let [handler (compile-routes example-routes {:middleware []})]
    (handler {:request-method :get :uri "/users"})))
