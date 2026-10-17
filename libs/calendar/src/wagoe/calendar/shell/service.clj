(ns wagoe.calendar.shell.service
  "Public API for the calendar library.

   Wraps the iCal adapter to keep callers decoupled from ical4j internals.
   All functions create an adapter internally unless one is injected via opts.

   Shell layer — may perform I/O via the ical4j adapter."
  (:require [wagoe.calendar.core.feed :as feed]
            [wagoe.calendar.shell.adapters.ical :as ical-adapter]
            [wagoe.calendar.ports :as ports]))

;; =============================================================================
;; iCal export
;; =============================================================================

(defn export-ical
  "Convert a seq of EventData maps to an RFC 5545 VCALENDAR string.

   Args:
     events - seq of EventData maps
     opts   - options map; supported keys:
                :product-id  string  PRODID value (default \"-//Wagoe//Calendar//EN\")
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
  "Export `events` and wrap them in a Ring response.

   opts: those of export-ical, plus :filename and :max-age as in
   `wagoe.calendar.core.feed/ical-response`."
  ([events]
   (ical-feed-response events {}))
  ([events opts]
   (feed/ical-response (export-ical events opts) opts)))
