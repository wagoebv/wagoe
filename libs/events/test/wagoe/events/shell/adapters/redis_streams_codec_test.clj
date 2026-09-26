(ns wagoe.events.shell.adapters.redis-streams-codec-test
  "The Redis bus's wire format, without a Redis. A value transit cannot write
   fails the publish, so the event is lost; these are the values a database row
   carries (BOU-492)."
  (:require [wagoe.events.shell.adapters.redis-streams :as redis-streams]
            [wagoe.events.shell.publisher :as publisher]
            [clojure.test :refer [deftest is testing]])
  (:import (java.time LocalDate LocalDateTime OffsetDateTime ZonedDateTime)))

(def ^:private encode #'redis-streams/encode)
(def ^:private decode #'redis-streams/decode)

(defn- round-trip [payload]
  (:payload (decode (encode (publisher/build :a/b :test payload)))))

(deftest ^:unit java-time-values-survive-the-wire
  (doseq [v [(OffsetDateTime/parse "2026-09-26T10:15:30.123456+02:00")
             (ZonedDateTime/parse "2026-09-26T10:15:30.123456+02:00[Europe/Amsterdam]")
             (LocalDate/parse "2026-09-26")
             (LocalDateTime/parse "2026-09-26T10:15:30.123456")]]
    (testing (.getSimpleName (class v))
      (let [back (:v (round-trip {:v v}))]
        (is (= v back))
        (is (= (class v) (class back)))))))

(deftype ^:private DriverValue [v]
  Object
  (toString [_] v))

(deftest ^:unit an-unknown-type-is-sent-as-its-string
  ;; A PGobject (jsonb) is the case in point; events does not depend on the
  ;; driver, so this stands in for it.
  (is (= {:meta "{\"a\": 1}" :n 1}
         (round-trip {:meta (DriverValue. "{\"a\": 1}") :n 1}))))
