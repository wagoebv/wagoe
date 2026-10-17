(ns wagoe.calendar.shell.module-wiring
  "Integrant wiring for the calendar module.

   `:wagoe/calendar` is a CalendarAdapterProtocol (ical4j). Take
   `(ig/ref :wagoe/calendar)` and call `wagoe.calendar.ports/export-ical` or
   `import-ical` on it; `wagoe.calendar.core.feed/ical-response` serves the
   result. The config keys stay readable on the component."
  (:require [integrant.core :as ig]
            [wagoe.calendar.shell.adapters.ical :as ical]))

(defmethod ig/init-key :wagoe/calendar [_ config]
  (ical/create-ical-adapter config))

(defmethod ig/halt-key! :wagoe/calendar [_ _adapter] nil)
