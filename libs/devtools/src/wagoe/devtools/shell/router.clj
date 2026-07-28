(ns wagoe.devtools.shell.router
  "Stateful router management for runtime route/tap modifications.
   Tracks dynamic routes and taps in atoms, rebuilds the handler via
   platform's swap-handler!."
  (:require [wagoe.devtools.core.router :as core-router]
            [clojure.string]
            [muuntaja.middleware :as muuntaja-mw]
            [reitit.core :as rc]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.params :refer [wrap-params]]))

(defonce ^:private dynamic-routes (atom {}))
(defonce ^:private dynamic-router (atom nil))
(defonce ^:private taps (atom {}))

(defn- rebuild-dynamic-router!
  "Rebuild the internal Reitit router from the current dynamic-routes atom.
   This gives us proper path matching (including path params like :id)."
  []
  (let [routes @dynamic-routes]
    (if (empty? routes)
      (reset! dynamic-router nil)
      ;; Group routes by path, then build Reitit route vectors
      (let [by-path (reduce (fn [acc [[method path] handler-map]]
                              (update acc path assoc method handler-map))
                            {} routes)
            reitit-routes (mapv (fn [[path methods]]
                                  [path methods])
                                by-path)]
        (reset! dynamic-router (rc/router reitit-routes))))))

(defn add-dynamic-route! [method path handler-fn]
  (swap! dynamic-routes assoc [method path] {:handler handler-fn})
  (rebuild-dynamic-router!)
  nil)

(defn remove-dynamic-route! [method path]
  (swap! dynamic-routes dissoc [method path])
  (rebuild-dynamic-router!)
  nil)

(defn list-dynamic-routes []
  (mapv (fn [[[method path] _]] {:method method :path path}) @dynamic-routes))

(defn add-tap! [handler-name tap-fn]
  (swap! taps assoc handler-name tap-fn)
  nil)

(defn remove-tap! [handler-name]
  (swap! taps dissoc handler-name)
  nil)

(defn list-taps []
  (vec (keys @taps)))

(defn apply-dynamic-routes [base-routes]
  (reduce
   (fn [routes [[method path] {:keys [handler]}]]
     (core-router/add-route routes method path handler))
   base-routes
   @dynamic-routes))

(defn apply-taps [routes]
  (reduce
   (fn [routes [handler-name tap-fn]]
     (core-router/inject-tap-interceptor routes handler-name tap-fn))
   routes
   @taps))

(defn rebuild-router!
  "Rebuild the HTTP handler with current dynamic routes and taps applied."
  [base-routes compile-fn swap-fn]
  (let [modified-routes (-> base-routes apply-dynamic-routes apply-taps)
        new-handler (compile-fn modified-routes)]
    (swap-fn new-handler)))

(defn- match-dynamic-route
  "Match a request against the dynamic Reitit router.
   Returns the handler-map for the matched method, or nil."
  [request]
  (when-let [router @dynamic-router]
    (when-let [match (rc/match-by-path router (:uri request))]
      (let [method (:request-method request)
            handler-map (get-in match [:data method])]
        (when handler-map
          {:handler-map handler-map
           :path-params (:path-params match)})))))

(defn wrap-dynamic-dispatch
  "Ring middleware that checks dynamic routes via a Reitit router.
   Supports path parameters (e.g. /api/foo/:id matches /api/foo/123).
   Matched requests go through the standard HTTP middleware stack
   (params, cookies, content negotiation/body parsing via Muuntaja)
   so dynamic handlers behave like normal Wagoe routes.
   Otherwise the request falls through to the base handler."
  [base-handler]
  (let [;; Middleware stack for dynamic routes — applied once at wrap time.
        mw-stack (comp wrap-params wrap-cookies muuntaja-mw/wrap-format)]
    (fn [request]
      (if-let [match (match-dynamic-route request)]
        (let [handler-fn (get-in match [:handler-map :handler])
              ;; Build a one-shot handler with the middleware stack applied,
              ;; merging path params so the handler sees :id etc.
              wrapped (mw-stack
                       (fn [req]
                         (handler-fn
                          (update req :path-params merge (:path-params match)))))]
          (wrapped request))
        (base-handler request)))))

(defn- handler-name->pattern
  "Convert a tap handler keyword like :create-user to a regex pattern
   that matches the handler function's string representation.
   Matches both kebab-case (symbol form) and underscore (compiled fn)."
  [handler-kw]
  (let [s (name handler-kw)
        ;; Match either kebab-case or underscore variant in the fn string
        pattern (str "(?:" s "|" (clojure.string/replace s "-" "_") ")")]
    (re-pattern pattern)))

(defn- find-matching-tap
  "Find a registered tap whose keyword matches the handler function string.
   Returns the tap function or nil."
  [active-taps handler-str]
  (when handler-str
    (some (fn [[tap-kw tap-fn]]
            (when (re-find (handler-name->pattern tap-kw) handler-str)
              tap-fn))
          active-taps)))

(defn wrap-taps
  "Ring middleware that invokes registered tap callbacks.
   Uses the Reitit router (from handler metadata) to pre-match the request.
   Matches taps in order of preference:
   1. Route :name keyword (exact match — works for all named routes)
   2. Handler function string (regex match — fallback for unnamed routes)
   The tap receives {:request request :match match-data} and its return
   value replaces the context (allowing request modification)."
  [base-handler]
  (let [router (:reitit/router (meta base-handler))]
    (fn [request]
      (let [active-taps @taps]
        (if (or (empty? active-taps) (nil? router))
          (base-handler request)
          (let [match      (rc/match-by-path router (:uri request))
                method     (:request-method request)
                match-data (when match (get-in match [:data method]))
                ;; Try matching by route :name first (exact keyword match)
                route-name (:name match-data)
                tap-fn     (or (when route-name (get active-taps route-name))
                               ;; Fall back to handler string matching
                               (let [handler-fn (:handler match-data)
                                     handler-str (when handler-fn (str handler-fn))]
                                 (find-matching-tap active-taps handler-str)))]
            (if tap-fn
              (let [ctx     {:request request :match (:data match)}
                    result  (tap-fn ctx)
                    request (or (:request result) request)]
                (base-handler request))
              (base-handler request))))))))

(defn has-taps? []
  (not (empty? @taps)))

(defn clear-dynamic-state!
  "Clear ephemeral dynamic state on reset.
   Taps are NOT cleared — they persist across resets because (reset) is the
   documented way to activate them."
  []
  (reset! dynamic-routes {})
  (reset! dynamic-router nil)
  nil)

(defn clear-all-state!
  "Clear ALL dynamic state including taps. Use for full cleanup."
  []
  (clear-dynamic-state!)
  (reset! taps {})
  nil)
