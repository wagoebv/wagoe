(ns wagoe.devtools.schema
  "Malli validation schemas for the devtools library.")

(def GuidanceLevel
  "Valid guidance levels."
  [:enum :full :minimal :off])

(def GuidanceConfig
  "Schema for guidance configuration."
  [:map
   [:guidance-level {:default :full} GuidanceLevel]])

(def ErrorCode
  "Schema for a Wagoe error code."
  [:map
   [:code :string]
   [:category :keyword]
   [:title :string]
   [:description :string]
   [:fix [:maybe :string]]])
