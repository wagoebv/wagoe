(ns wagoe.core.schema
  "Canonical schemas for the core library's primary domain shapes.

   Documents the two central data structures used across the framework:

   1. Validation result — returned by all core validation functions
   2. Interceptor context — the map that flows through interceptor pipelines

   These schemas are provided as documentation references. Runtime validation
   of these shapes lives in wagoe.core.interceptor-context and
   wagoe.core.validation.result respectively.")

;; =============================================================================
;; Validation Result Shape
;; =============================================================================

(def ValidationResult
  "Schema for the map returned by core validation functions.

   Success example:
     {:valid? true :data {:email \"user@example.com\"}}

   Failure example:
     {:valid? false :errors [{:field :email :code :invalid-format :message \"...\"}]}"
  [:map {:title "ValidationResult"}
   [:valid? :boolean]
   [:data {:optional true} :any]
   [:errors {:optional true}
    [:vector
     [:map {:closed false}
      [:field {:optional true} [:or :keyword :string]]
      [:code {:optional true} [:or :keyword :string]]
      [:message {:optional true} :string]]]]])

;; =============================================================================
;; Interceptor Context Shape
;; =============================================================================

(def InterceptorContext
  "Schema for the map that flows through the interceptor pipeline.

   Every interceptor receives and returns this map. Core interceptors add
   :result; shell interceptors handle :request, :system, and I/O.

   Minimal example (CLI):
     {:op :example-op :system {} :request {:args [\"--help\"]}}

   HTTP example:
     {:op :user-create
      :system {:logger ...}
      :request {:headers {...} :body {...}}
      :result {...}}"
  [:map {:title "InterceptorContext" :closed false}
   [:op :keyword]
   [:system [:map-of :keyword :any]]
   [:request {:optional true}
    [:map {:closed false}
     [:headers {:optional true} [:map-of :string :string]]
     [:body {:optional true} :any]
     [:params {:optional true} [:map-of :keyword :any]]
     [:query {:optional true} [:map-of :string :string]]
     [:args {:optional true} [:vector :string]]
     [:options {:optional true} [:map-of :keyword :any]]]]
   [:interface-type {:optional true} [:enum :http :cli :service]]
   [:correlation-id {:optional true} :string]
   [:now {:optional true} inst?]
   [:result {:optional true} ValidationResult]
   [:response {:optional true} :map]
   [:errors {:optional true} [:vector :map]]
   [:exception {:optional true} :any]
   [:halt? {:optional true} :boolean]
   [:breadcrumbs {:optional true} [:vector :map]]])
