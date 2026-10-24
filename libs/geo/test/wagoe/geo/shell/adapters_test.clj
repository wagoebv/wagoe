(ns wagoe.geo.shell.adapters-test
  "Adapters against provider-shaped JSON. clj-http.core/request is stubbed below
   the middleware, so `:as :json` decodes the body exactly as it does live."
  (:require [wagoe.geo.ports :as ports]
            [wagoe.geo.shell.adapters.google :as google]
            [wagoe.geo.shell.adapters.mapbox :as mapbox]
            [wagoe.geo.shell.adapters.osm :as osm]
            [wagoe.geo.shell.adapters.pdok :as pdok]
            [wagoe.geo.shell.adapters.support :as support]
            [wagoe.geo.shell.service :as service]
            [cheshire.core :as json]
            [clj-http.core]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging.test :as log-test])
  (:import [java.io ByteArrayInputStream]
           [java.net URLDecoder]))

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

(defn- params
  "The request's query string as a map; a repeated key's values in a vector."
  [request]
  (reduce (fn [m pair]
            (let [[k v] (map #(URLDecoder/decode ^String % "UTF-8") (str/split pair #"=" 2))]
              (if (= k "fq")
                (update m k (fnil conj []) v)
                (assoc m k v))))
          {}
          (str/split (:query-string request) #"&")))

;; Trimmed from live responses.
(def ^:private nominatim-item
  {"place_id"     12345
   "lat"          "52.3730796"
   "lon"          "4.8924534"
   "display_name" "Dam, Amsterdam, Noord-Holland, Nederland, 1012 JS, Nederland"
   "address"      {"road" "Dam" "house_number" "1" "city" "Amsterdam" "postcode" "1012 JS"
                   "country" "Nederland"}})

(def ^:private google-body
  {"status"  "OK"
   "results" [{"formatted_address"  "Damrak 1, 1012 LG Amsterdam, Netherlands"
               "geometry"           {"location" {"lat" 52.3765 "lng" 4.8975}}
               "address_components" [{"long_name" "1" "types" ["street_number"]}
                                     {"long_name" "Damrak" "types" ["route"]}
                                     {"long_name" "1012 LG" "types" ["postal_code"]}
                                     {"long_name" "Amsterdam" "types" ["locality" "political"]}
                                     {"long_name" "Netherlands" "types" ["country" "political"]}]}]})

(def ^:private mapbox-body
  {"type"     "FeatureCollection"
   "features" [{"place_type" ["address"]
                "text"       "Damrak"
                "address"    "1"
                "place_name" "Damrak 1, 1012 LG Amsterdam, Netherlands"
                "geometry"   {"type" "Point" "coordinates" [4.8975 52.3765]}
                "context"    [{"id" "postcode.1" "text" "1012 LG"}
                              {"id" "place.2" "text" "Amsterdam"}
                              {"id" "country.3" "text" "Netherlands"}]}]})

(def ^:private damrak
  {:lat 52.3765 :lng 4.8975 :formatted-address "Damrak 1, 1012 LG Amsterdam, Netherlands"
   :street "Damrak" :house-number "1"
   :postcode "1012 LG" :city "Amsterdam" :country "Netherlands"})

(defn- osm-adapter []
  (osm/create-nominatim-adapter {:user-agent "wagoe-test/1.0 (test@example.com)"
                                 :base-url   "http://nominatim.test"
                                 :min-interval-ms 0}))

(deftest ^:integration osm-geocode-test
  (let [requests (with-response (json-response [nominatim-item])
                   #(is (= {:lat 52.3730796 :lng 4.8924534
                            :formatted-address "Dam, Amsterdam, Noord-Holland, Nederland, 1012 JS, Nederland"
                            :street "Dam" :house-number "1"
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
    (testing "reverse-geocode, without the limit Mapbox rejects there"
      (let [requests (with-response (json-response mapbox-body)
                       #(is (= "Amsterdam" (:city (ports/reverse-geocode adapter {:lat 52.3765 :lng 4.8975})))))]
        (is (not (re-find #"limit=" (str (:query-string (first requests))))))))))

(deftest ^:integration house-number-in-free-text-test
  (let [requests (with-response (json-response [nominatim-item])
                   #(ports/geocode (osm-adapter) {:address "Dam" :house-number 1
                                                  :house-number-addition "A" :postcode "1012 JS"}))]
    (is (= "Dam 1 A, 1012 JS, Netherlands" (get (params (first requests)) "q")))))

;; =============================================================================
;; PDOK
;; =============================================================================

;; Trimmed from live responses to fl=<the adapter's fields>.
(defn- pdok-doc [huisnummer & {:as more}]
  (merge {"weergavenaam"   (str "Dam " huisnummer ", 1012JS Amsterdam")
          "straatnaam"     "Dam"
          "huisnummer"     huisnummer
          "postcode"       "1012JS"
          "woonplaatsnaam" "Amsterdam"
          "centroide_ll"   "POINT(4.8937175 52.37329259)"}
         more))

(defn- pdok-body [& docs]
  {"response" {"numFound" (count docs) "start" 0 "docs" (vec docs)}})

(defn- pdok-adapter []
  (pdok/create-pdok-adapter {:base-url "http://pdok.test" :min-interval-ms 0}))

(deftest ^:integration pdok-postcode-and-house-number-test
  (let [requests (with-response (json-response (pdok-body (pdok-doc 1)))
                   #(is (= {:lat 52.37329259 :lng 4.8937175
                            :formatted-address "Dam 1, 1012JS Amsterdam"
                            :street "Dam" :house-number "1"
                            :postcode "1012JS" :city "Amsterdam" :country "Netherlands"
                            :provider :pdok :cached? false}
                           (ports/geocode (pdok-adapter) {:postcode "1012 js" :house-number 1}))))
        request  (first requests)]
    (is (= "http://pdok.test/free" (str (name (:scheme request)) "://" (:server-name request) (:uri request))))
    (is (= ["type:adres" "postcode:1012JS" "huisnummer:1"] (get (params request) "fq")))
    (is (nil? (get (params request) "q")))))

(deftest ^:integration pdok-addition-test
  (let [docs [(pdok-doc 1 "huisletter" "A") (pdok-doc 1) (pdok-doc 1 "huisletter" "A" "huisnummertoevoeging" "2")]
        geocode (fn [query]
                  (let [result (atom nil)]
                    (with-response (json-response (apply pdok-body docs))
                      #(reset! result (ports/geocode (pdok-adapter) query)))
                    @result))]
    (testing "no addition asked: the plain number"
      (is (nil? (:house-number-addition (geocode {:postcode "1012JS" :house-number "1"})))))
    (testing "the addition asked, however it is written"
      (is (= "A-2" (:house-number-addition (geocode {:postcode "1012JS" :house-number 1
                                                     :house-number-addition "a 2"}))))
      (is (= "A" (:house-number-addition (geocode {:postcode "1012JS" :house-number "1a"})))))
    (testing "an addition that does not exist is no match"
      (is (nil? (geocode {:postcode "1012JS" :house-number 1 :house-number-addition "Z"}))))))

(deftest ^:integration pdok-free-text-test
  (let [requests (with-response (json-response (pdok-body (pdok-doc 1)))
                   #(is (= "Dam" (:street (ports/geocode (pdok-adapter) {:address "Dam 1" :city "Amsterdam"})))))]
    (is (= {"q" "Dam 1 Amsterdam" "fq" ["type:adres"] "rows" "1"}
           (dissoc (params (first requests)) "fl")))))

(deftest ^:integration pdok-misses-test
  (testing "another country is not asked"
    (is (empty? (with-response (json-response (pdok-body))
                  #(is (nil? (ports/geocode (pdok-adapter) {:city "London" :country "United Kingdom"}))))))
    (is (= 1 (count (with-response (json-response (pdok-body))
                      #(ports/geocode (pdok-adapter) {:city "Utrecht" :country "Nederland"}))))))
  (testing "no address found"
    (with-response (json-response (pdok-body))
      #(is (nil? (ports/geocode (pdok-adapter) {:postcode "9999ZZ" :house-number 1})))))
  (testing "a non-200 is a WARN and nil"
    (log-test/with-log
      (with-response {:status 500 :headers {} :body (ByteArrayInputStream. (.getBytes "{}"))}
        #(is (nil? (ports/geocode (pdok-adapter) {:postcode "1012JS" :house-number 1}))))
      (is (log-test/logged? 'wagoe.geo.shell.adapters.pdok :warn #"non-200"))))
  (testing "a failed request is an ERROR and nil"
    (log-test/with-log
      (with-redefs [clj-http.core/request (fn [_] (throw (java.net.SocketTimeoutException. "Read timed out")))]
        (is (nil? (ports/geocode (pdok-adapter) {:postcode "1012JS" :house-number 1}))))
      (is (log-test/logged? 'wagoe.geo.shell.adapters.pdok :error java.net.SocketTimeoutException
                            #"request failed"))))
  (testing "a doc without a point is a malformed response"
    (log-test/with-log
      (with-response (json-response (pdok-body (dissoc (pdok-doc 1) "centroide_ll")))
        #(is (nil? (ports/geocode (pdok-adapter) {:postcode "1012JS" :house-number 1}))))
      (is (log-test/logged? 'wagoe.geo.shell.adapters.support :warn #"malformed response.*:pdok")))))

(deftest ^:integration pdok-reverse-geocode-test
  (let [requests (with-response (json-response (pdok-body (pdok-doc 1)))
                   #(is (= "Dam" (:street (ports/reverse-geocode (pdok-adapter) {:lat 52.3733 :lng 4.8937})))))
        request  (first requests)]
    (is (= "/reverse" (:uri request)))
    (is (= {"lat" "52.3733" "lon" "4.8937" "type" "adres" "rows" "1"}
           (dissoc (params request) "fl")))))

(defrecord ^:private AtomCache [store]
  ports/GeoCacheProtocol
  (cache-lookup [_ h] (get @store h))
  (cache-store! [_ h result] (swap! store assoc h (assoc result :cached? true)) nil))

(deftest ^:integration pdok-cached-per-house-number-test
  (let [svc      {:providers [(pdok-adapter)] :cache (->AtomCache (atom {}))}
        requests (with-response (json-response (pdok-body (pdok-doc 1)))
                   (fn []
                     (is (false? (:cached? (service/geocode! svc {:postcode "1012JS" :house-number 1}))))
                     (is (= {:street "Dam" :house-number "1" :cached? true}
                            (select-keys (service/geocode! svc {:postcode "1012JS" :house-number 1})
                                         [:street :house-number :cached?])))
                     (service/geocode! svc {:postcode "1012JS" :house-number 2})))]
    (is (= 2 (count requests)) "number 1 once, then number 2")))

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
