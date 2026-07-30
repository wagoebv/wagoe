(ns wagoe.jobs.schema
  "Malli schemas for background job processing."
  (:require [malli.core :as m]))

;; =============================================================================
;; Job Status
;; =============================================================================

(def JobStatus
  "Possible job statuses."
  [:enum
   :pending      ; Job is queued, waiting to be processed
   :running      ; Job is currently being processed
   :completed    ; Job completed successfully
   :failed       ; Job failed after all retries
   :retrying     ; Job failed but will be retried
   :cancelled])  ; Job was cancelled

(def Priority
  "Job priority levels."
  [:enum :critical :high :normal :low])

;; =============================================================================
;; Job Schema
;; =============================================================================

(def Job
  "Complete job schema with all fields."
  [:map
   [:id uuid?]
   [:job-type keyword?]
   [:queue keyword?]
   [:args [:map-of keyword? any?]]
   [:status JobStatus]
   [:priority {:optional true} Priority]
   [:retry-count {:optional true} [:int {:min 0}]]
   [:max-retries {:optional true} [:int {:min 0 :max 10}]]
   [:execute-at {:optional true} inst?]
   [:created-at inst?]
   [:updated-at inst?]
   [:started-at {:optional true} inst?]
   [:completed-at {:optional true} inst?]
   [:result {:optional true} [:map-of keyword? any?]]
   [:error {:optional true} [:map
                             [:message :string]
                             [:type :string]
                             [:stacktrace {:optional true} :string]]]
   [:metadata {:optional true} [:map-of keyword? any?]]])

(def JobInput
  "Schema for creating a new job."
  [:map
   [:job-type keyword?]
   [:args [:map-of keyword? any?]]
   [:queue {:optional true} keyword?]
   [:priority {:optional true} Priority]
   [:max-retries {:optional true} [:int {:min 0 :max 10}]]
   [:execute-at {:optional true} inst?]
   [:metadata {:optional true} [:map-of keyword? any?]]])

;; =============================================================================
;; Worker Schema
;; =============================================================================

(def WorkerStatus
  "Worker status enum."
  [:enum :idle :running :stopping :stopped])

(def Worker
  "Worker schema."
  [:map
   [:id uuid?]
   [:queue keyword?]
   [:status WorkerStatus]
   [:current-job {:optional true} [:map
                                   [:job-id uuid?]
                                   [:job-type keyword?]
                                   [:started-at inst?]]]
   [:processed-count [:int {:min 0}]]
   [:failed-count [:int {:min 0}]]
   [:started-at inst?]
   [:last-heartbeat inst?]])

;; =============================================================================
;; Queue Stats Schema
;; =============================================================================

(def QueueStats
  "Queue statistics schema."
  [:map
   [:queue-name keyword?]
   [:size [:int {:min 0}]]
   [:processed-total [:int {:min 0}]]
   [:failed-total [:int {:min 0}]]
   [:succeeded-total [:int {:min 0}]]
   [:avg-duration-ms {:optional true} [:double {:min 0}]]
   [:oldest-job {:optional true} inst?]
   [:newest-job {:optional true} inst?]])

(def JobStats
  "Overall job statistics schema."
  [:map
   [:total-processed [:int {:min 0}]]
   [:total-failed [:int {:min 0}]]
   [:total-succeeded [:int {:min 0}]]
   [:active-workers [:int {:min 0}]]
   [:queues [:vector QueueStats]]])

;; =============================================================================
;; Retry Configuration
;; =============================================================================

(def RetryConfig
  "Configuration for job retry behavior."
  [:map
   [:max-retries {:optional true} [:int {:min 0 :max 10}]]
   [:backoff-strategy {:optional true} [:enum :linear :exponential :constant]]
   [:initial-delay-ms {:optional true} [:int {:min 0}]]
   [:max-delay-ms {:optional true} [:int {:min 0}]]
   [:jitter {:optional true} [:boolean]]])

;; =============================================================================
;; Job Handler Schema
;; =============================================================================

(def JobHandler
  "Job handler function signature."
  [:=> [:cat [:map-of keyword? any?]] [:map
                                       [:success? :boolean]
                                       [:result {:optional true} any?]
                                       [:error {:optional true} [:map
                                                                 [:message :string]
                                                                 [:type :string]]]]])

;; =============================================================================
;; Validation Functions
;; =============================================================================

(def ^:private job-validator (m/validator Job))
(def ^:private job-input-validator (m/validator JobInput))
(def ^:private job-explainer (m/explainer Job))

(defn valid-job?
  "Validate job against schema."
  [job]
  (job-validator job))

(defn valid-job-input?
  "Validate job input against schema."
  [job-input]
  (job-input-validator job-input))

(defn explain-job-errors
  "Get human-readable validation errors for job."
  [job]
  (job-explainer job))
