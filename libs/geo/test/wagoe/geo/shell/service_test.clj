(ns wagoe.geo.shell.service-test
  "Integration tests for GeoService — mock provider + atom-backed cache."
  (:require [wagoe.geo.shell.service :as sut]
            [wagoe.geo.shell.module-wiring]
            [wagoe.geo.ports :as ports]
            [integrant.core :as ig]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging.test :as log-test]))

;; =============================================================================
;; Mock provider
;; =============================================================================

(defrecord MockProvider [results-atom call-count-atom]
  ports/GeoProviderProtocol

  (geocode [_this query]
    (swap! call-count-atom inc)
    (get @results-atom (:city query)))

  (reverse-geocode [_this point]
    (swap! call-count-atom inc)
    (when (= 52.3676 (:lat point))
      {:lat               52.3676
       :lng               4.9041
       :formatted-address "Amsterdam, Netherlands"
       :provider          :mock
       :cached?           false})))

(defn- make-mock-provider
  "Return a mock provider with pre-seeded results keyed by :city."
  [results]
  (->MockProvider (atom results) (atom 0)))

;; =============================================================================
;; Mock cache (atom-backed)
;; =============================================================================

(defrecord AtomCache [store-atom]
  ports/GeoCacheProtocol

  (cache-lookup [_this hash]
    (get @store-atom hash))

  (cache-store! [_this hash result]
    (swap! store-atom assoc hash (assoc result :cached? true))
    nil))

(defn- make-atom-cache []
  (->AtomCache (atom {})))

;; =============================================================================
;; Test fixtures
;; =============================================================================

(def amsterdam-result
  {:lat               52.3676
   :lng               4.9041
   :formatted-address "Amsterdam, Netherlands"
   :city              "Amsterdam"
   :country           "Netherlands"
   :provider          :mock
   :cached?           false})

;; =============================================================================
;; geocode! — no cache
;; =============================================================================

(deftest ^:integration geocode-no-cache-test
  (let [provider (make-mock-provider {"Amsterdam" amsterdam-result})
        service  {:providers [provider] :cache nil}]
    (testing "returns result from provider"
      (let [result (sut/geocode! service {:city "Amsterdam"})]
        (is (some? result))
        (is (= 52.3676 (:lat result)))
        (is (= :mock (:provider result)))))
    (testing "returns nil when provider has no match"
      (is (nil? (sut/geocode! service {:city "Unknown"}))))))

;; =============================================================================
;; geocode! — with cache
;; =============================================================================

(deftest ^:integration geocode-cache-miss-then-hit-test
  (let [provider  (make-mock-provider {"Amsterdam" amsterdam-result})
        geo-cache (make-atom-cache)
        service   {:providers [provider] :cache geo-cache}]
    (testing "first call is a cache miss — provider is called"
      (let [result (sut/geocode! service {:city "Amsterdam"})]
        (is (some? result))
        (is (= 1 @(:call-count-atom provider)))))

    (testing "second call is a cache hit — provider not called again"
      (let [result (sut/geocode! service {:city "Amsterdam"})]
        (is (some? result))
        (is (true? (:cached? result)))
        ;; call-count should still be 1
        (is (= 1 @(:call-count-atom provider)))))))

;; =============================================================================
;; geocode! — fallback chain
;; =============================================================================

(deftest ^:integration geocode-fallback-chain-test
  (let [provider-a (make-mock-provider {})                     ; always nil
        provider-b (make-mock-provider {"Amsterdam" amsterdam-result})
        service    {:providers [provider-a provider-b] :cache nil}]
    (testing "falls back to second provider when first returns nil"
      (let [result (sut/geocode! service {:city "Amsterdam"})]
        (is (some? result))
        (is (= 1 @(:call-count-atom provider-a)))
        (is (= 1 @(:call-count-atom provider-b)))))))

;; =============================================================================
;; reverse-geocode!
;; =============================================================================

(deftest ^:integration reverse-geocode-test
  (let [provider (make-mock-provider {})
        service  {:providers [provider] :cache nil}]
    (testing "returns result for known coordinates"
      (let [result (sut/reverse-geocode! service {:lat 52.3676 :lng 4.9041})]
        (is (some? result))
        (is (= :mock (:provider result)))))

    (testing "returns nil for unknown coordinates"
      (is (nil? (sut/reverse-geocode! service {:lat 0.0 :lng 0.0}))))))

;; =============================================================================
;; distance — delegates to core/math
;; =============================================================================

(deftest ^:integration distance-test
  (testing "distance between Amsterdam and Rotterdam is approximately 57 km"
    (let [d (sut/distance {:lat 52.3676 :lng 4.9041}
                          {:lat 51.9225 :lng 4.4792})]
      (is (> d 55.0))
      (is (< d 60.0))))

  (testing "distance is zero for same point"
    (let [amsterdam {:lat 52.3676 :lng 4.9041}]
      (is (< (sut/distance amsterdam amsterdam) 0.001)))))

;; =============================================================================
;; Providers per call (BOU-833)
;; =============================================================================

(def ^:private abroad (assoc amsterdam-result :country "Belgium" :provider :openstreetmap))

(deftest ^:integration geocode-only-named-providers-test
  (let [pdok    (make-mock-provider {})
        osm     (make-mock-provider {"Amsterdam" abroad})
        service (sut/map->GeoService {:providers      [pdok osm]
                                      :provider-names [:pdok :openstreetmap]
                                      :cache          (make-atom-cache)})]
    (testing "only the named provider is asked"
      (is (nil? (sut/geocode! service {:city "Amsterdam"} {:only #{:pdok}})))
      (is (= 1 @(:call-count-atom pdok)))
      (is (zero? @(:call-count-atom osm))))
    (testing "without :only the chain runs, and its answer is not served to a :pdok-only call"
      (is (= "Belgium" (:country (sut/geocode! service {:city "Amsterdam"}))))
      (is (nil? (sut/geocode! service {:city "Amsterdam"} {:only #{:pdok}}))))
    (testing "a restricted call is cached under its restriction"
      (reset! (:results-atom pdok) {"Utrecht" (assoc amsterdam-result :city "Utrecht")})
      (reset! (:call-count-atom pdok) 0)
      (is (false? (:cached? (sut/geocode! service {:city "Utrecht"} {:only #{:pdok}}))))
      (is (true? (:cached? (sut/geocode! service {:city "Utrecht"} {:only #{:pdok}}))))
      (is (= 1 @(:call-count-atom pdok))))
    (testing "a name not configured is a WARN and a miss"
      (log-test/with-log
        (is (nil? (sut/geocode! service {:city "Amsterdam"} {:only #{:google}})))
        (is (log-test/logged? 'wagoe.geo.shell.service :warn #"No configured geo provider"))))))

(deftest ^:integration geo-service-port-test
  (let [pdok    (make-mock-provider {"Amsterdam" amsterdam-result})
        osm     (make-mock-provider {"Amsterdam" abroad})
        service (sut/map->GeoService {:providers [pdok osm] :provider-names [:pdok :openstreetmap]})]
    (is (= :openstreetmap (:provider (ports/lookup service {:city "Amsterdam"} {:only [:openstreetmap]}))))
    (is (= :mock (:provider (ports/lookup service {:city "Amsterdam"} nil))))
    (is (= :mock (:provider (ports/reverse-lookup service {:lat 52.3676 :lng 4.9041} {:only #{:pdok}}))))
    (is (= 1 @(:call-count-atom osm)) "asked by the first lookup only")))

(deftest ^:integration wiring-names-providers-and-sets-timeouts-test
  (let [svc (ig/init-key :wagoe/geo-service {:provider [:pdok :openstreetmap] :timeout-ms 500
                                             :user-agent "wagoe-test/1.0 (test@example.com)"})]
    (is (satisfies? ports/GeoServiceProtocol svc))
    (is (= [:pdok :openstreetmap] (:provider-names svc)))
    (is (= [500 500] (map :timeout-ms (:providers svc)))))
  (is (= [3000] (map :timeout-ms (:providers (ig/init-key :wagoe/geo-service {:provider :pdok}))))))
