(ns wagoe.platform.transit
  "Transit JSON for values that leave the process and come back as Clojure:
   events on the Redis bus and outbox, jobs on the :db and :redis queues. JSON
   would turn their keywords, UUIDs and instants into strings (BOU-689)."
  (:require [cognitect.transit :as transit])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)))

(def ^:private java-time
  "java.time types by tag, each read back from its ISO `str` as itself:
   transit alone has none of them, and epoch millis would drop microseconds."
  {"instant"          [java.time.Instant        #(java.time.Instant/parse %)]
   "offset-date-time" [java.time.OffsetDateTime #(java.time.OffsetDateTime/parse %)]
   "zoned-date-time"  [java.time.ZonedDateTime  #(java.time.ZonedDateTime/parse %)]
   "local-date"       [java.time.LocalDate      #(java.time.LocalDate/parse %)]
   "local-date-time"  [java.time.LocalDateTime  #(java.time.LocalDateTime/parse %)]
   "local-time"       [java.time.LocalTime      #(java.time.LocalTime/parse %)]
   "offset-time"      [java.time.OffsetTime     #(java.time.OffsetTime/parse %)]
   "year-month"       [java.time.YearMonth      #(java.time.YearMonth/parse %)]
   "duration"         [java.time.Duration       #(java.time.Duration/parse %)]
   "period"           [java.time.Period         #(java.time.Period/parse %)]
   "zone-id"          [java.time.ZoneId         #(java.time.ZoneId/of %)]})

(def ^:private write-handlers
  (transit/write-handler-map
   (into {} (for [[tag [klass _]] java-time] [klass (transit/write-handler tag str)]))))

(def ^:private default-write-handler
  "Anything else goes as its `str`, so an unexpected type (a driver's PGobject
   for jsonb) changes a value rather than losing the whole event."
  (transit/write-handler "str" str))

(def ^:private read-handlers
  (transit/read-handler-map
   (into
    {"str"  (transit/read-handler identity)
     ;; transit tags a PersistentList as "list" and its default reader hands back
     ;; a LazySeq. The values are equal, so this is invisible to `=` — and not to
     ;; `list?`, which a consumer that worked in development would then fail on
     ;; in production. The tag is precise: only a PersistentList carries it.
     "list" (transit/read-handler #(apply list %))
     ;; transit's own reader returns its URI type, not the java.net.URI written.
     "r"    (transit/read-handler #(java.net.URI/create %))}
    (for [[tag [_ parse]] java-time] [tag (transit/read-handler parse)]))))

(defn encode
  "`value` as a transit JSON string."
  [value]
  (let [out (ByteArrayOutputStream.)]
    (transit/write (transit/writer out :json {:handlers        write-handlers
                                              :default-handler default-write-handler}) value)
    (.toString out "UTF-8")))

(defn decode
  "The value `encode` wrote."
  [^String s]
  (transit/read (transit/reader (ByteArrayInputStream. (.getBytes s "UTF-8")) :json
                                {:handlers read-handlers})))
