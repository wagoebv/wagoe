(ns wagoe.geo.shell.adapters-test
  "Adapters against provider-shaped JSON. clj-http.core/request is stubbed below
   the middleware, so `:as :json` decodes the body exactly as it does live."
  (:require [wagoe.geo.ports :as ports]
            [wagoe.geo.shell.adapters.google :as google]
            [wagoe.geo.shell.adapters.mapbox :as mapbox]
            [wagoe.geo.shell.adapters.osm :as osm]
            [wagoe.geo.shell.adapters.support :as support]
            [cheshire.core :as json]
            [clj-http.core]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging.test :as log-test])
  (:import [java.io ByteArrayInputStream]))

(defn- json-response [body]
  {:status  200
   :headers {"content-type" "application/json; charset=utf-8"}
   :body    (ByteArrayInputStream. (.getBytes (json/generate-string body) "UTF-8"))})

(defn- with-response
  "Call `f` with every HTTP request answered by `response`; returns the requests seen."
  [response f]
  (let [requests (atom [])]
    (with-redefs [clj-http.core/request (fn [req] (swap! requests conj req) response)]
      (f))
    @requests))

;; Trimmed from live responses.
(def ^:private nominatim-item
  {"place_id"     12345
   "lat"          "52.3730796"
   "lon"          "4.8924534"
   "display_name" "Dam, Amsterdam, Noord-Holland, Nederland, 1012 JS, Nederland"
   "address"      {"road" "Dam" "city" "Amsterdam" "postcode" "1012 JS" "country" "Nederland"}})

(def ^:private google-body
  {"status"  "OK"
   "results" [{"formatted_address"  "Damrak 1, 1012 LG Amsterdam, Netherlands"
               "geometry"           {"location" {"lat" 52.3765 "lng" 4.8975}}
               "address_components" [{"long_name" "1012 LG" "types" ["postal_code"]}
                                     {"long_name" "Amsterdam" "types" ["locality" "political"]}
                                     {"long_name" "Netherlands" "types" ["country" "political"]}]}]})

(def ^:private mapbox-body
  {"type"     "FeatureCollection"
   "features" [{"place_name" "Damrak 1, 1012 LG Amsterdam, Netherlands"
                "geometry"   {"type" "Point" "coordinates" [4.8975 52.3765]}
                "context"    [{"id" "postcode.1" "text" "1012 LG"}
                              {"id" "place.2" "text" "Amsterdam"}
                              {"id" "country.3" "text" "Netherlands"}]}]})

(def ^:private damrak
  {:lat 52.3765 :lng 4.8975 :formatted-address "Damrak 1, 1012 LG Amsterdam, Netherlands"
   :postcode "1012 LG" :city "Amsterdam" :country "Netherlands"})

(defn- osm-adapter []
  (osm/create-nominatim-adapter {:user-agent "wagoe-test/1.0 (test@example.com)"
                                 :base-url   "http://nominatim.test"
                                 :min-interval-ms 0}))

(deftest ^:integration osm-geocode-test
  (let [requests (with-response (json-response [nominatim-item])
                   #(is (= {:lat 52.3730796 :lng 4.8924534
                            :formatted-address "Dam, Amsterdam, Noord-Holland, Nederland, 1012 JS, Nederland"
                            :postcode "1012 JS" :city "Amsterdam" :country "Nederland"
                            :provider :openstreetmap :cached? false}
                           (ports/geocode (osm-adapter) {:postcode "1012 JS" :city "Amsterdam"}))))]
    (is (= "wagoe-test/1.0 (test@example.com)"
           (get-in (first requests) [:headers "User-Agent"])))))

(deftest ^:integration osm-reverse-geocode-test
  (testing "a match"
    (with-response (json-response nominatim-item)
      #(is (= 52.3730796 (:lat (ports/reverse-geocode (osm-adapter) {:lat 52.373 :lng 4.892}))))))
  (testing "no address there is nil, not a malformed response"
    (log-test/with-log
      (with-response (json-response {"error" "Unable to geocode"})
        #(is (nil? (ports/reverse-geocode (osm-adapter) {:lat 0.0 :lng 0.0}))))
      (is (not (log-test/logged? 'wagoe.geo.shell.adapters.support :warn #".*"))))))

(deftest ^:integration osm-malformed-response-test
  (log-test/with-log
    (with-response (json-response [{"lat" "" "lon" "4.89"}])
      #(is (nil? (ports/geocode (osm-adapter) {:city "Amsterdam"}))))
    (is (log-test/logged? 'wagoe.geo.shell.adapters.support :warn
                          #"malformed response.*:openstreetmap"))
    (is (not (log-test/logged? 'wagoe.geo.shell.adapters.osm :error #".*")))))

(deftest ^:integration google-test
  (let [adapter (google/create-google-adapter {:api-key "k" :base-url "http://google.test"
                                               :min-interval-ms 0})]
    (testing "geocode"
      (with-response (json-response google-body)
        #(is (= (assoc damrak :provider :google :cached? false)
                (ports/geocode adapter {:address "Damrak 1"})))))
    (testing "reverse-geocode"
      (with-response (json-response google-body)
        #(is (= "Amsterdam" (:city (ports/reverse-geocode adapter {:lat 52.3765 :lng 4.8975}))))))
    (testing "a rejected key is a WARN naming Google's reason"
      (log-test/with-log
        (with-response (json-response {"status" "REQUEST_DENIED" "results" []
                                       "error_message" "The provided API key is invalid."})
          #(is (nil? (ports/geocode adapter {:address "Damrak 1"}))))
        (is (log-test/logged? 'wagoe.geo.shell.adapters.google :warn #"API key is invalid"))))))

(deftest ^:integration mapbox-test
  (let [adapter (mapbox/create-mapbox-adapter {:api-key "k" :base-url "http://mapbox.test"
                                               :min-interval-ms 0})]
    (testing "geocode"
      (with-response (json-response mapbox-body)
        #(is (= (assoc damrak :provider :mapbox :cached? false)
                (ports/geocode adapter {:address "Damrak 1"})))))
    (testing "reverse-geocode"
      (with-response (json-response mapbox-body)
        #(is (= "Amsterdam" (:city (ports/reverse-geocode adapter {:lat 52.3765 :lng 4.8975}))))))))

(deftest ^:unit rate-limit-test
  (testing "the public Nominatim server is never asked more than once a second"
    (is (= 1000 (:min-interval-ms (osm/create-nominatim-adapter {:user-agent "x" :min-interval-ms 10}))))
    (is (= 1000 (:min-interval-ms (osm/create-nominatim-adapter
                                   {:user-agent "x" :min-interval-ms 10
                                    :base-url "https://nominatim.openstreetmap.org/"})))))
  (testing "no :user-agent for the public server warns"
    (log-test/with-log
      (osm/create-nominatim-adapter {})
      (is (log-test/logged? 'wagoe.geo.shell.adapters.osm :warn #"no :user-agent"))))
  (testing "concurrent callers are spaced, not just sequential ones"
    (let [last-ms (atom 0)
          starts  (->> (range 3)
                       ;; returns the time it let the request through
                       (mapv (fn [_] (future (support/enforce-rate-limit! last-ms 50))))
                       (mapv deref)
                       sort)]
      (is (every? #(>= % 50) (map - (rest starts) starts))))))
