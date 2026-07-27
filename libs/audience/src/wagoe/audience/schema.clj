(ns wagoe.audience.schema
  "Malli schemas for the boundary-audience library.
   All internal data uses kebab-case keywords.
   snake_case conversion happens only at DB boundaries.")

;; =============================================================================
;; Filter primitives
;; =============================================================================

(def FilterDef
  [:map
   [:type :keyword]
   [:field {:optional true} :keyword]
   [:op :keyword]
   [:value :any]])

(def DynamicFilterDef
  "Like FilterDef but rejects fn values for :value (safe for DB persistence)."
  [:map
   [:type :keyword]
   [:field {:optional true} :keyword]
   [:op :keyword]
   [:value [:fn (complement fn?)]]])

;; =============================================================================
;; Composable segment references
;; =============================================================================

(def SegmentRef
  [:map [:ref :keyword]])

(def Composable
  [:schema
   {:registry
    {::composable
     [:or
      SegmentRef
      [:map [:and [:vector [:ref ::composable]]]]
      [:map [:or  [:vector [:ref ::composable]]]]
      [:map [:not [:ref ::composable]]]]}}
   ::composable])

;; =============================================================================
;; Cache configuration
;; =============================================================================

(def CacheConfig
  [:map
   [:ttl-minutes {:optional true} :int]
   [:refresh-schedule {:optional true} :string]])

;; =============================================================================
;; Audience definitions
;; =============================================================================

(def AudienceDefinition
  [:map
   [:id :keyword]
   [:label :string]
   [:description {:optional true} :string]
   [:filters [:vector FilterDef]]
   [:compose {:optional true} Composable]
   [:cache-config {:optional true} CacheConfig]
   [:tags {:optional true} [:vector :keyword]]])

(def DynamicAudienceDefinition
  "Stricter variant of AudienceDefinition — no fn values allowed in filters.
   Use this schema when persisting audience definitions to the database."
  [:map
   [:id :keyword]
   [:label :string]
   [:description {:optional true} :string]
   [:filters [:vector DynamicFilterDef]]
   [:compose {:optional true} Composable]
   [:cache-config {:optional true} CacheConfig]
   [:tags {:optional true} [:vector :keyword]]])

;; =============================================================================
;; Evaluation results
;; =============================================================================

(def SegmentResult
  [:map
   [:user-ids [:set :uuid]]
   [:count :int]
   [:cached? :boolean]
   [:evaluated-at inst?]])

(def MembershipRecord
  [:map
   [:audience-id :uuid]
   [:user-id :uuid]
   [:entered-at inst?]])
