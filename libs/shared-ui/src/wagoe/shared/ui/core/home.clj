(ns wagoe.shared.ui.core.home
  "The path layouts link to as home: the logo, \"back\" and dashboard links.")

(def default-path "/web/dashboard")

(def ^:dynamic *path*
  "Home for the request being handled, or nil for `default-path`. Bound by
   the user module from its `:home` setting, the way `csrf/*token*` is bound,
   so layouts need not thread it through every handler."
  nil)

(defn path []
  (or *path* default-path))
