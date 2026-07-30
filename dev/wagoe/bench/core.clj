(ns wagoe.bench.core
  "CRUD benchmarks for Wagoe core operations.

   Run all benchmarks:
     clojure -M:bench

   Run from REPL:
     (require '[wagoe.bench.core :as bench])
     (bench/run-all)
     (bench/run-validation)
     (bench/run-json)
     (bench/run-interceptor)"
  (:require [criterium.core :as crit]
            [malli.core :as m]
            [malli.transform :as mt]
            [cheshire.core :as json]
            [wagoe.core.interceptor :as interceptor]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- bench
  "Run a quick benchmark and return results summary."
  [label f]
  (println (str "\n--- " label " ---"))
  (crit/quick-bench (f))
  (println))

;; =============================================================================
;; Malli Validation Benchmarks
;; =============================================================================

(def ^:private user-schema
  [:map
   [:id :uuid]
   [:name [:string {:min 2 :max 100}]]
   [:email [:re #".+@.+\..+"]]
   [:role [:enum :user :admin :manager]]
   [:status [:enum :active :suspended :deleted]]
   [:created-at inst?]])

(def ^:private valid-user
  {:id (java.util.UUID/randomUUID)
   :name "Jane Doe"
   :email "jane@example.com"
   :role :user
   :status :active
   :created-at (java.util.Date.)})

(def ^:private invalid-user
  {:id "not-a-uuid"
   :name ""
   :email "bad"
   :role :unknown
   :status :nope
   :created-at "not-a-date"})

(defn run-validation
  "Benchmark Malli schema validation."
  []
  (println "\n========== Malli Validation ==========")

  (bench "validate valid user"
         #(m/validate user-schema valid-user))

  (bench "validate invalid user (with explain)"
         #(m/explain user-schema invalid-user))

  (bench "decode + validate (json transformer)"
         #(m/decode user-schema
                    {"id" (str (:id valid-user))
                     "name" "Jane Doe"
                     "email" "jane@example.com"
                     "role" "user"
                     "status" "active"
                     "created-at" (str (:created-at valid-user))}
                    (mt/json-transformer)))

  (bench "coerce with strip-extra-keys"
         #(m/decode user-schema
                    (assoc valid-user :extra-field "ignored" :another 42)
                    (mt/strip-extra-keys-transformer))))

;; =============================================================================
;; JSON Serialization Benchmarks
;; =============================================================================

(def ^:private sample-response
  {:status "ok"
   :data [{:id (str (java.util.UUID/randomUUID))
           :name "Product A"
           :price 29.99
           :tags ["electronics" "sale"]
           :metadata {:weight 0.5 :dimensions {:w 10 :h 5 :d 3}}}
          {:id (str (java.util.UUID/randomUUID))
           :name "Product B"
           :price 149.00
           :tags ["furniture"]
           :metadata {:weight 15.0 :dimensions {:w 100 :h 50 :d 40}}}]
   :pagination {:page 1 :per-page 20 :total 142}})

(defn run-json
  "Benchmark JSON serialization/deserialization."
  []
  (println "\n========== JSON Serialization ==========")
  (let [json-str (json/generate-string sample-response)]

    (bench "serialize map to JSON"
           #(json/generate-string sample-response))

    (bench "parse JSON to map (keywordize)"
           #(json/parse-string json-str true))

    (bench "roundtrip (serialize + parse)"
           #(-> sample-response
                json/generate-string
                (json/parse-string true)))))

;; =============================================================================
;; Interceptor Pipeline Benchmarks
;; =============================================================================

(def ^:private noop-interceptor
  {:name :noop
   :enter (fn [ctx] ctx)
   :leave (fn [ctx] ctx)})

(def ^:private transform-interceptor
  {:name :transform
   :enter (fn [ctx] (update-in ctx [:request :counter] (fnil inc 0)))
   :leave (fn [ctx] (assoc-in ctx [:response :processed] true))})

(def ^:private auth-interceptor
  {:name :auth
   :enter (fn [ctx]
            (if (get-in ctx [:request :authenticated?])
              ctx
              (assoc ctx :response {:status 401 :body "Unauthorized"})))})

(defn run-interceptor
  "Benchmark interceptor pipeline execution."
  []
  (println "\n========== Interceptor Pipeline ==========")

  (let [short-pipeline [noop-interceptor transform-interceptor]
        long-pipeline (into [auth-interceptor]
                            (repeat 10 transform-interceptor))
        base-ctx {:request {:authenticated? true :counter 0}}]

    (bench "2-interceptor pipeline"
           #(interceptor/run-pipeline base-ctx short-pipeline))

    (bench "11-interceptor pipeline"
           #(interceptor/run-pipeline base-ctx long-pipeline))

    (bench "early termination (auth fail)"
           #(interceptor/run-pipeline
             {:request {:authenticated? false}}
             long-pipeline))))

;; =============================================================================
;; Runner
;; =============================================================================

(defn run-all
  "Run all benchmark suites."
  []
  (println "==============================================")
  (println "  Wagoe CRUD Benchmarks")
  (println "==============================================")
  (run-validation)
  (run-json)
  (run-interceptor)
  (println "\n========== Done =========="))

(defn -main [& args]
  (if (= (first args) "hotpaths")
    ((requiring-resolve 'wagoe.bench.hotpaths/run-all))
    (run-all))
  (shutdown-agents))
