(ns wagoe.ui-style
  "Shared app-wide UI asset bundles.

   Keep CSS/JS asset ordering centralized here so modules can reuse the same
   rendering contract without hardcoding per-page lists."
  (:require [clojure.java.io :as io])
  (:import [java.security MessageDigest]))

(def pilot-css
  "The default stack: daisyUI/Tailwind on wagoe-tokens."
  ["/css/wagoe-tokens.css"
   "/css/app.css"
   "/css/daisy-admin.css"])

(def admin-pilot-css
  "The admin's one stylesheet, built from resources/tailwind/admin.css
   (`npm run build:css`)."
  ["/css/wagoe-admin.css"])

(def css-bundles
  "Registry of known stylesheet bundles."
  {:pilot       pilot-css
   :admin-pilot admin-pilot-css})

(def pilot-js
  "The default JavaScript stack.
   components.js must load before alpine.min.js to register shared Alpine components.

   forms.js is here rather than in the admin bundle because it makes htmx swap
   the re-rendered form a 400 carries, and every htmx form needs that — not just
   the ones under /web/admin. It shipped admin-only, so the create-user form at
   /web/users/new discarded its own validation errors and pressing Create looked
   like it did nothing (BOU-381)."
  ["/js/theme.js"
   "/js/components.js"
   "/js/alpine.min.js"
   "/js/htmx.min.js"
   "/js/htmx-ext-sse.min.js"
   "/js/forms.js"])

(def admin-pilot-js
  "Admin pilot JavaScript stack.
   components.js and admin-ux.js must load before alpine.min.js because they
   register Alpine components/stores via the alpine:init event."
  ["/js/theme.js"
   "/js/components.js"
   "/js/admin-ux.js"
   "/js/alpine.min.js"
   "/js/htmx.min.js"
   "/js/htmx-ext-sse.min.js"
   "/js/forms.js"
   "/js/keyboard.js"]) ;; forms.js is also in pilot-js; the two bundles are alternatives, not layers

(def js-bundles
  "Registry of known JavaScript bundles."
  {:pilot       pilot-js
   :admin-pilot admin-pilot-js})

(defonce ^:private warned-keys (atom #{}))

(defn- lookup
  "`k`'s bundle in `registry`, else `fallback`, warning once per unknown key —
   a typo, or `:base`, which was removed. nil asks for the default and does not warn."
  [registry k fallback]
  (or (get registry k)
      (let [[before] (swap-vals! warned-keys conj k)]
        (when-not (or (nil? k) (contains? before k))
          (binding [*out* *err*]
            (println (str "WARN wagoe.ui-style: unknown bundle " (pr-str k) ", serving :pilot"))))
        fallback)))

(defn bundle
  "Return stylesheet list for `k` bundle key.
   An unknown key, `:base` included, warns once and gets `:pilot`."
  [k]
  (lookup css-bundles k pilot-css))

(defn js-bundle
  "Return JavaScript list for `k` bundle key.
   An unknown key warns once and gets `:pilot`."
  [k]
  (lookup js-bundles k pilot-js))

(defn- digest
  "The validator the static handler sends as ETag: SHA-256 of the bytes,
   truncated to 16, in hex. The two must agree, or no URL is ever immutable."
  [url]
  (with-open [in (io/input-stream url)]
    (let [md  (MessageDigest/getInstance "SHA-256")
          buf (byte-array 8192)]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n)
            (.update md buf 0 n)
            (recur))))
      (->> (.digest md) (take 16) (map #(format "%02x" (bit-and % 0xff))) (apply str)))))

(defonce ^:private packaged-digests (atom {}))

(defn asset-url
  "`path` with `?v=<digest>` when it names a resource under public/, so a
   changed asset is a new URL and the old one may be cached for good (BOU-391).
   Anything else is returned as it is. A jar entry cannot change while the
   process runs, so its digest is kept; a file a developer edits is hashed again."
  [path]
  (if-let [url (and (string? path) (.startsWith ^String path "/") (io/resource (str "public" path)))]
    (str path "?v=" (if (= "jar" (.getProtocol url))
                      (or (get @packaged-digests path)
                          (let [d (digest url)] (swap! packaged-digests assoc path d) d))
                      (digest url)))
    path))
