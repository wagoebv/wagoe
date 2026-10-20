(ns wagoe.shared.ui.core.home
  "The path layouts link to as home: the logo, \"back\" and dashboard links.")

(def default
  "The dashboard: the admin's home, open to every signed-in user."
  "/web/admin/")

(def ^:dynamic *path*
  "Home for the request being handled. Bound by the user module from its
   `:home` setting, the way `csrf/*token*` is bound, so layouts need not
   thread it through every handler."
  default)
