(ns wagoe.calendar.core.feed
  "Ring response for an iCal feed. Pure: it takes the iCal string, so export
   it first through `wagoe.calendar.ports/export-ical`."
  (:require [clojure.string :as str]))

(defn- safe-filename
  "Drop what would end the quoted header value, or the header."
  [filename]
  (str/replace filename #"[\"\\\r\n]" ""))

(defn ical-response
  "Ring response serving `ical`, a VCALENDAR string.

   opts:
     :filename - Content-Disposition filename (default \"calendar.ics\")
     :max-age  - seconds a client may cache the feed (default 300). Cached
                 privately, because a feed URL is usually per user."
  ([ical] (ical-response ical {}))
  ([ical {:keys [filename max-age]}]
   ;; `or`, not `:or`: a caller passing {:filename nil} gets the default too.
   {:status  200
    :headers {"Content-Type"        "text/calendar; charset=utf-8"
              "Content-Disposition" (str "attachment; filename=\""
                                         (safe-filename (or filename "calendar.ics")) "\"")
              "Cache-Control"       (str "private, max-age=" (or max-age 300))}
    :body    ical}))
