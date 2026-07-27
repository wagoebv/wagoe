(ns wagoe.calendar.shell.service
  "Public API for the calendar library.

   Wraps the iCal adapter to keep callers decoupled from ical4j internals.
   All functions create an adapter internally unless one is injected via opts.

   Shell layer — may perform I/O via the ical4j adapter."
  (:require [wagoe.calendar.shell.adapters.ical :as ical-adapter]
            [wagoe.calendar.ports :as ports]))

;; =============================================================================
;; iCal export
;; =============================================================================

(defn export-ical
  "Convert a seq of EventData maps to an RFC 5545 VCALENDAR string.

   Args:
     events - seq of EventData maps
     opts   - options map; supported keys:
                :product-id  string  PRODID value (default \"-//Boundary//Calendar//EN\")
                :adapter     CalendarAdapterProtocol implementation (default ICalAdapter)

   Returns a string starting with \"BEGIN:VCALENDAR\"."
  ([events]
   (export-ical events {}))
  ([events opts]
   (let [adapter (get opts :adapter (ical-adapter/create-ical-adapter))]
     (ports/export-ical adapter events (dissoc opts :adapter)))))

;; =============================================================================
;; iCal import
;; =============================================================================

(defn import-ical
  "Parse an RFC 5545 VCALENDAR string into a vector of EventData maps.

   Args:
     ical-string - RFC 5545 VCALENDAR string
     opts        - options map; supported keys:
                     :adapter CalendarAdapterProtocol implementation (default ICalAdapter)

   Returns a vector of EventData maps (kebab-case, :start/:end as Instants)."
  ([ical-string]
   (import-ical ical-string {}))
  ([ical-string opts]
   (let [adapter (get opts :adapter (ical-adapter/create-ical-adapter))]
     (ports/import-ical adapter ical-string (dissoc opts :adapter)))))

;; =============================================================================
;; HTTP feed response
;; =============================================================================

(defn ical-feed-response
  "Build a Ring response map for serving an iCal feed over HTTP.

   Args:
     events - seq of EventData maps
     opts   - same opts as export-ical; also accepts:
                :filename string — Content-Disposition filename (default \"calendar.ics\")

   Returns:
     {:status  200
      :headers {\"Content-Type\"        \"text/calendar; charset=utf-8\"
                \"Content-Disposition\" \"attachment; filename=\\\"calendar.ics\\\"\"}
      :body    \"BEGIN:VCALENDAR...\"}"
  ([events]
   (ical-feed-response events {}))
  ([events opts]
   (let [filename (get opts :filename "calendar.ics")
         body     (export-ical events (dissoc opts :filename))]
     {:status  200
      :headers {"Content-Type"        "text/calendar; charset=utf-8"
                "Content-Disposition" (str "attachment; filename=\"" filename "\"")}
      :body    body})))
