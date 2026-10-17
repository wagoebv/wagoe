(ns wagoe.platform.shell.http.pages
  "Asks the request's router which pages are mounted."
  (:require [reitit.core :as r]))

(defn mounted?
  "Whether `path` is a route of its own in the router serving `request`, not
   just a value for a parameter: /web/admin/groups also matches
   /web/admin/:entity. True when no router is attached, as nothing can be asked."
  [request path]
  (if-let [router (::r/router request)]
    (= path (:template (r/match-by-path router path)))
    true))
