(ns wagoe.calendar.ports
  "Protocol definitions for the calendar module.

   FC/IS rule: protocols are interfaces — no implementation here.
   Adapters (shell layer) implement these protocols.")

;; =============================================================================
;; CalendarAdapterProtocol
;; =============================================================================

(defprotocol CalendarAdapterProtocol
  "Adapter contract for iCal serialization and deserialization.

   The `:wagoe/calendar` component implements it with ical4j, so an
   application takes `(ig/ref :wagoe/calendar)` and needs only this namespace."

  (export-ical [this events opts]
    "Convert a seq of EventData maps → iCal string (RFC 5545 VCALENDAR format).
     An `:all-day? true` event is written as DTSTART;VALUE=DATE with an
     exclusive DTEND, its dates taken in the event's :timezone.

     Args:
       events - seq of EventData maps
       opts   - options map; supported keys:
                  :product-id  string  PRODID property (default \"-//Wagoe//Calendar//EN\")
                  :cal-name    string  optional X-WR-CALNAME property

     Returns a string starting with \"BEGIN:VCALENDAR\".")

  (import-ical [this ical-string opts]
    "Parse an iCal string → seq of EventData maps in boundary kebab-case format.

     Args:
       ical-string - RFC 5545 VCALENDAR string
       opts        - options map; supported keys:
                       :timezone  IANA zone for events without a TZID: dates
                                  and floating times (default \"UTC\")

     Returns a vector of EventData maps. RRULE strings are preserved in :recurrence.
     A VALUE=DATE event comes back `:all-day? true`.
     Unknown VEVENT properties are silently ignored."))
