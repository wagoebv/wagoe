(ns wagoe.platform.shell.http.flash
  "Ring flash over a cookie of its own: a response's `:flash` is the next
   request's `:flash`, as with ring.middleware.flash. That one keeps the flash
   in the Ring session, and there is none: the user module puts its database
   session at `:session`, which wrap-flash would write into the session store.

   The cookie is not signed. It holds display text for the browser that holds
   it, and is read back as a map of toast types to a string or keyword, so a
   forged one can only change what its owner sees, as escaped text."
  (:require [clojure.edn :as edn]))

(def cookie-name "wagoe-flash")

(def ^:private message-keys #{:success :info :warning :error :type :message})

(defn- message? [v] (or (string? v) (keyword? v)))

(defn- read-flash
  "The flash a cookie value names, or nil for anything not in a flash's shape."
  [s]
  (let [m (try (edn/read-string s) (catch Exception _ nil))]
    (when (and (map? m) (seq m)
               (every? (fn [[k v]] (and (message-keys k) (message? v))) m))
      m)))

(defn- secure? [request]
  (or (= :https (:scheme request))
      (= "https" (get-in request [:headers "x-forwarded-proto"]))))

(defn- flash-cookie [request value max-age]
  {:value value :max-age max-age :path "/" :http-only true
   :same-site :lax :secure (secure? request)})

(defn wrap-flash
  "Carries a response's `:flash` to the next request. Runs inside wrap-cookies.
   A flash is read once: the request that brings it clears the cookie, unless
   its own response sets a new one."
  [handler]
  (fn [request]
    (let [incoming (some-> (get-in request [:cookies cookie-name :value]) read-flash)
          stale?   (contains? (:cookies request) cookie-name)]
      (when-let [response (handler (cond-> request incoming (assoc :flash incoming)))]
        (let [flash  (:flash response)
              cookie (cond (some? flash) (flash-cookie request (pr-str flash) 60)
                           stale?        (flash-cookie request "" 0))]
          (cond-> (dissoc response :flash)
            cookie (assoc-in [:cookies cookie-name] cookie)))))))
