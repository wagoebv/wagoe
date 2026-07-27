(ns wagoe.calendar.ports
  "Protocol definitions for the calendar module.

   FC/IS rule: protocols are interfaces — no implementation here.
   Adapters (shell layer) implement these protocols.")

;; =============================================================================
;; CalendarAdapterProtocol
;; =============================================================================

(defprotocol CalendarAdapterProtocol
  "Adapter contract for iCal serialization and deserialization.

   Implementations live in shell/adapters/. The ical4j-backed implementation
   is in wagoe.calendar.shell.adapters.ical/ICalAdapter."

  (export-ical [this events opts]
    "Convert a seq of EventData maps → iCal string (RFC 5545 VCALENDAR format).

     Args:
       events - seq of EventData maps
       opts   - options map; supported keys:
                  :product-id  string  PRODID property (default \"-//Boundary//Calendar//EN\")
                  :cal-name    string  optional X-WR-CALNAME property

     Returns a string starting with \"BEGIN:VCALENDAR\".")

  (import-ical [this ical-string opts]
    "Parse an iCal string → seq of EventData maps in boundary kebab-case format.

     Args:
       ical-string - RFC 5545 VCALENDAR string
       opts        - options map (reserved for future use)

     Returns a vector of EventData maps. RRULE strings are preserved in :recurrence.
     Unknown VEVENT properties are silently ignored."))
