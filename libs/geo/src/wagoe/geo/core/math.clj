(ns wagoe.geo.core.math
  "Pure geographic math functions — no I/O, no side effects.")

(def ^:private earth-radius-km 6371.0)

(defn- deg->rad [deg]
  (* deg (/ Math/PI 180.0)))

(defn haversine-distance
  "Calculate the great-circle distance between two points using the Haversine formula.

   Args:
     point-a - map with :lat and :lng (decimal degrees)
     point-b - map with :lat and :lng (decimal degrees)

   Returns:
     Distance in kilometres as a double."
  [{lat-a :lat lng-a :lng} {lat-b :lat lng-b :lng}]
  (let [dlat  (deg->rad (- lat-b lat-a))
        dlng  (deg->rad (- lng-b lng-a))
        rlat-a (deg->rad lat-a)
        rlat-b (deg->rad lat-b)
        a (+ (* (Math/sin (/ dlat 2)) (Math/sin (/ dlat 2)))
             (* (Math/cos rlat-a) (Math/cos rlat-b)
                (Math/sin (/ dlng 2)) (Math/sin (/ dlng 2))))
        c (* 2.0 (Math/atan2 (Math/sqrt a) (Math/sqrt (- 1.0 a))))]
    (* earth-radius-km c)))

(defn bearing
  "Calculate the initial bearing (forward azimuth) from point `from` to point `to`.

   Args:
     from - map with :lat and :lng (decimal degrees)
     to   - map with :lat and :lng (decimal degrees)

   Returns:
     Bearing in degrees clockwise from north (0–360)."
  [{lat-from :lat lng-from :lng} {lat-to :lat lng-to :lng}]
  (let [rlat-from (deg->rad lat-from)
        rlat-to   (deg->rad lat-to)
        dlng      (deg->rad (- lng-to lng-from))
        x         (* (Math/sin dlng) (Math/cos rlat-to))
        y         (- (* (Math/cos rlat-from) (Math/sin rlat-to))
                     (* (Math/sin rlat-from) (Math/cos rlat-to) (Math/cos dlng)))
        theta     (Math/toDegrees (Math/atan2 x y))]
    (mod (+ theta 360.0) 360.0)))
