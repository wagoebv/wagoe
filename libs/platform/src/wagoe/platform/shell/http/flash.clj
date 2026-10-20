(ns wagoe.platform.shell.http.flash
  "Ring flash in a cookie, not the Ring session: `:session` is the user
   module's database session. Unsigned, since only toast text is read back."
  (:require [clojure.edn :as edn]
            [clojure.tools.logging :as log]))

(def cookie-name "wagoe-flash")

(def ^:private toast-types #{:success :info :warning :error})

(defn- message? [v] (or (string? v) (keyword? v)))

(defn- flash?
  "One of the two shapes toast/flash-toasts renders: {:type t :message m},
   :type optional, or {t m, t2 m2}."
  [m]
  (and (map? m) (seq m)
       (if (contains? m :message)
         (and (message? (:message m))
              (every? #{:type :message} (keys m))
              (or (not (contains? m :type)) (contains? toast-types (:type m))))
         (every? (fn [[k v]] (and (toast-types k) (message? v))) m))))

(defn- read-flash
  "The flash a cookie value names, or nil for anything not in a flash's shape."
  [s]
  (let [m (try (edn/read-string s) (catch Exception _ nil))]
    (when (flash? m) m)))

(defn- outgoing
  "`flash` as a cookie value, or nil, with a warning, when the next request
   would not read it back."
  [flash]
  (cond
    (flash? flash) (pr-str flash)
    (= {} flash)   nil
    :else
    (log/warn "Dropping a :flash that is not a map of toast types to a string or keyword"
              {:keys (when (map? flash) (keys flash))})))

(def ^:private redirect-statuses #{301 302 303 307 308})

(defn- flash-cookie [secure? value max-age]
  {:value value :max-age max-age :path "/" :http-only true
   :same-site :lax :secure secure?})

(defn wrap-flash
  "Carries a response's `:flash` to the next request. Runs inside wrap-cookies.
   A flash is read once: the request that brings it clears the cookie, unless
   its response sets a new one or is a redirect without a `:flash`, which
   renders nothing. `:secure?`, default true, marks the cookie Secure, as
   `:secure-cookies?` does the auth cookies."
  ([handler] (wrap-flash handler {}))
  ([handler {:keys [secure?] :or {secure? true}}]
   (fn [request]
     (let [sent     (get-in request [:cookies cookie-name])
           incoming (read-flash (:value sent))]
       (when-let [response (handler (cond-> request incoming (assoc :flash incoming)))]
         (let [value  (some-> (:flash response) outgoing)
               cookie (cond value (flash-cookie secure? value 60)
                            (and sent (or (contains? response :flash)
                                          (not (redirect-statuses (:status response)))))
                            (flash-cookie secure? "" 0))]
           (cond-> (dissoc response :flash)
             cookie (assoc-in [:cookies cookie-name] cookie))))))))
