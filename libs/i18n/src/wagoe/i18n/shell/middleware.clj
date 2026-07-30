(ns wagoe.i18n.shell.middleware
  "Ring middleware for i18n — injects locale and translation function into requests.

   SHELL FUNCTION: wraps Ring handler (performs I/O boundary).

   The middleware stores the catalogue and config on the request so that
   locale resolution can happen at render time (after authentication
   middleware has added :user to the request).

   Injects into request:
     :i18n/catalogue     - the loaded translation catalogue
     :i18n/default-locale - configured default locale keyword
     :i18n/locale-chain  - ordered vector of locale keywords, e.g. [:nl :en]
     :i18n/t             - translation function (key params? n?) → string"
  (:require [wagoe.i18n.core.translate :as translate]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Locale resolution
;; =============================================================================

(defn get-user-locale
  "Extract the user's preferred locale from the request.

   Checks two locations, in order:
     1. `[:user :language]`    — populated by authentication middleware.
     2. `[:session :user :language]` — populated by a Ring session middleware
        (e.g. `ring.middleware.session/wrap-session`) that runs before
        `wrap-i18n`. This is a defensive fallback so that downstream
        consumers of this library can resolve the user locale inside
        `wrap-i18n`'s eager `:i18n/t` even if authentication middleware runs
        after us — matching the pre-refactor behavior of this function.

   Authenticated handlers should still prefer `resolve-t-fn` because auth
   middleware in this codebase runs *after* `wrap-i18n`; the session fallback
   only helps consumers who populate `:session` upstream of `wrap-i18n`.

   Args:
     request - Ring request map

   Returns:
     locale keyword (e.g. :nl) or nil"
  [request]
  (let [lang (or (get-in request [:user :language])
                 (get-in request [:session :user :language]))]
    (when (and (string? lang) (seq lang))
      (keyword lang))))

(defn get-tenant-locale
  "Extract tenant-configured locale from request (if present).

   Args:
     request - Ring request map

   Returns:
     locale keyword or nil"
  [request]
  (when-let [lang (or (get-in request [:tenant :settings :language])
                      (get-in request [:tenant :default-language]))]
    (when (and (string? lang) (seq lang))
      (keyword lang))))

(defn build-locale-chain
  "Build ordered locale chain: user → tenant → default.

   Nil values are filtered out. :en is always appended as ultimate fallback.

   Args:
     user-locale   - keyword or nil
     tenant-locale - keyword or nil
     default       - keyword (config default, e.g. :en)

   Returns:
     Vector of distinct locale keywords, e.g. [:nl :en]"
  [user-locale tenant-locale default]
  (into [] (distinct (filter identity [user-locale tenant-locale default :en]))))

;; =============================================================================
;; Public: resolve t-fn from enriched request
;; =============================================================================

(defn resolve-t-fn
  "Create a translation function from a fully-enriched request.

   Call this at render time (after authentication middleware has run)
   to get a t-fn that respects the authenticated user's language preference.

   Args:
     request - Ring request map with :i18n/catalogue and :i18n/default-locale

   Returns:
     Translation function (key) | (key params) | (key params n) → string,
     or nil if no catalogue is on the request."
  [request]
  (when-let [catalogue (:i18n/catalogue request)]
    (let [default-locale (:i18n/default-locale request :en)
          user-locale    (get-user-locale request)
          tenant-locale  (get-tenant-locale request)
          locale-chain   (build-locale-chain user-locale tenant-locale default-locale)]
      (fn
        ([key]
         (translate/t catalogue locale-chain key))
        ([key params]
         (translate/t catalogue locale-chain key params))
        ([key params n]
         (translate/t catalogue locale-chain key params n))))))

;; =============================================================================
;; Middleware
;; =============================================================================

(defn wrap-i18n
  "Ring middleware that injects i18n context into each request.

   Stores the catalogue and config on the request so that resolve-t-fn
   can create a properly-localized translation function at render time,
   after authentication middleware has added :user to the request.

   Also injects an eager :i18n/t for non-authenticated routes (login, etc.)
   and for consumers that populate `:session` upstream (via Ring
   `wrap-session`) where :user may already be present at middleware time.
   Authenticated routes that run their auth middleware after `wrap-i18n`
   should prefer `resolve-t-fn` at render time to honor the user locale.

   Args:
     handler - Ring handler
     opts    - map with:
       :catalogue     - loaded catalogue (map or ICatalogue)
       :default-locale - keyword, e.g. :en
       :dev?           - boolean, enables debug markers (optional)

   Returns:
     Ring middleware function"
  [handler {:keys [catalogue default-locale dev?]
            :or   {default-locale :en}}]
  (fn [request]
    (let [user-locale   (get-user-locale request)
          tenant-locale (get-tenant-locale request)
          locale-chain  (build-locale-chain user-locale tenant-locale default-locale)
          t-fn          (fn
                          ([key]
                           (translate/t catalogue locale-chain key))
                          ([key params]
                           (translate/t catalogue locale-chain key params))
                          ([key params n]
                           (translate/t catalogue locale-chain key params n)))
          enriched      (-> request
                            (assoc :i18n/catalogue catalogue)
                            (assoc :i18n/default-locale default-locale)
                            (assoc :i18n/locale-chain locale-chain)
                            (assoc :i18n/t t-fn))]
      (when dev?
        (log/debug "i18n middleware" {:locale-chain locale-chain}))
      (handler enriched))))
