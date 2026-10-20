(ns wagoe.platform.shell.http.flash
  "Ring flash over a cookie of its own: a response's `:flash` is the next
   request's `:flash`, as with ring.middleware.flash. That one keeps the flash
   in the Ring session, and there is none: the user module puts its database
   session at `:session`, which wrap-flash would write into the session store.

   The cookie is not signed. It holds display text for the browser that holds
   it, and is read back as a map of toast types to a string or keyword, so a
   forged one can only change what its owner sees, as escaped text."
  (:require [clojure.edn :as edn]
            [clojure.tools.logging :as log]))

(def cookie-name "wagoe-flash")

(def ^:private message-keys #{:success :info :warning :error :type :message})

(defn- message? [v] (or (string? v) (keyword? v)))

(defn- flash? [m]
  (and (map? m) (seq m)
       (every? (fn [[k v]] (and (message-keys k) (message? v))) m)))

(defn- read-flash
  "The flash a cookie value names, or nil for anything not in a flash's shape."
  [s]
  (let [m (try (edn/read-string s) (catch Exception _ nil))]
    (when (flash? m) m)))

(defn- outgoing
  "`flash` as a cookie value, or nil, with a warning, when the next request
   would not read it back."
  [flash]
  (if (flash? flash)
    (pr-str flash)
    (log/warn "Dropping a :flash that is not a map of toast types to a string or keyword"
              {:keys (when (map? flash) (keys flash))})))

(defn- flash-cookie [secure? value max-age]
  {:value value :max-age max-age :path "/" :http-only true
   :same-site :lax :secure secure?})

(defn wrap-flash
  "Carries a response's `:flash` to the next request. Runs inside wrap-cookies.
   A flash is read once: the request that brings it clears the cookie, unless
   its own response sets a new one. `:secure?`, default true, marks the cookie
   Secure, as `:secure-cookies?` does the auth cookies."
  ([handler] (wrap-flash handler {}))
  ([handler {:keys [secure?] :or {secure? true}}]
   (fn [request]
     (let [sent     (get-in request [:cookies cookie-name])
           incoming (read-flash (:value sent))]
       (when-let [response (handler (cond-> request incoming (assoc :flash incoming)))]
         (let [value  (some-> (:flash response) outgoing)
               cookie (cond value (flash-cookie secure? value 60)
                            sent  (flash-cookie secure? "" 0))]
           (cond-> (dissoc response :flash)
             cookie (assoc-in [:cookies cookie-name] cookie))))))))
