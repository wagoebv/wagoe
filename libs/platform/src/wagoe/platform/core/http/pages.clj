(ns wagoe.platform.core.http.pages
  "Whether a page is mounted, for layouts deciding what to link to.")

(def ^:dynamic *mounted?*
  "Predicate on a path: is it a route of its own in the router serving the
   request being handled. Bound around handler execution, the way
   `csrf/*token*` is. Outside a request nothing can be asked, so every path
   counts as mounted."
  (constantly true))

(defn mounted?
  "Whether `path` is mounted, per `*mounted?*`."
  [path]
  (boolean (*mounted?* path)))
