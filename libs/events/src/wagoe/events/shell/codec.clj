(ns wagoe.events.shell.codec
  "The wire format: transit JSON, shared by the Redis bus and the outbox so an
   event survives either the same way."
  (:require [cognitect.transit :as transit])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)))

(def ^:private write-handlers
  "transit knows java.util.Date and not java.time.Instant.

   `:published-at` is an Instant, and so is anything time-shaped a caller puts
   in a payload, so without this every publish fails with \"Not supported:
   class java.time.Instant\" — at the broker, which the in-memory adapter never
   reaches. The two adapters agreed on everything except the one thing only a
   real broker exercises."
  {java.time.Instant
   ;; ISO-8601, not epoch millis. `Instant/now` is microsecond-precision on a
   ;; modern JVM — 2026-08-13T08:23:28.536003Z — and `.toEpochMilli` rounds
   ;; that to .536, so every event's :published-at came back different from
   ;; what was published, on every event rather than in some edge case. The
   ;; in-memory adapter does not serialise, so it kept the original and the two
   ;; disagreed. `.toString` and `Instant/parse` round-trip exactly.
   (transit/write-handler "instant" #(.toString ^java.time.Instant %))

   ;; What a database row carries (BOU-492). `.toString` and `parse`
   ;; round-trip each of them exactly, zone id included.
   java.time.OffsetDateTime (transit/write-handler "offset-date-time" str)
   java.time.ZonedDateTime  (transit/write-handler "zoned-date-time" str)
   java.time.LocalDate      (transit/write-handler "local-date" str)
   java.time.LocalDateTime  (transit/write-handler "local-date-time" str)})

(def ^:private default-write-handler
  "Anything else goes as its `str`, so an unexpected type (a driver's PGobject
   for jsonb) changes a value rather than losing the whole event."
  (transit/write-handler "str" str))

(def ^:private read-handlers
  "Read an Instant back as an Instant.

   Mapping it onto transit's built-in time type would hand the consumer a
   java.util.Date instead, so a round trip would change the type of a value
   nobody asked to convert."
  {"instant" (transit/read-handler #(java.time.Instant/parse %))
   "offset-date-time" (transit/read-handler #(java.time.OffsetDateTime/parse %))
   "zoned-date-time"  (transit/read-handler #(java.time.ZonedDateTime/parse %))
   "local-date"       (transit/read-handler #(java.time.LocalDate/parse %))
   "local-date-time"  (transit/read-handler #(java.time.LocalDateTime/parse %))
   "str"              (transit/read-handler identity)

   ;; transit tags a PersistentList as "list" and its default reader hands back
   ;; a LazySeq. The values are equal, so this is invisible to `=` — and not to
   ;; `list?`, which a consumer that worked in development would then fail on
   ;; in production. The tag is precise: only a PersistentList carries it.
   "list"    (transit/read-handler #(apply list %))})

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
