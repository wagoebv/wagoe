(ns wagoe.webhooks.schema
  "Malli schemas for subscriptions and the module's settings.")

(def SubscriptionInput
  [:map {:closed true}
   [:url [:string {:min 1 :max 2048}]]
   [:event-pattern [:string {:min 1 :max 255}]]
   [:tenant-id {:optional true} [:maybe [:string {:min 1 :max 255}]]]
   [:description {:optional true} [:maybe [:string {:max 1000}]]]
   [:secret {:optional true} [:string {:min 16 :max 512}]]])

(def SubscriptionChanges
  [:map {:closed true}
   [:url {:optional true} [:string {:min 1 :max 2048}]]
   [:event-pattern {:optional true} [:string {:min 1 :max 255}]]
   [:active? {:optional true} :boolean]
   [:description {:optional true} [:maybe [:string {:max 1000}]]]])

(def Settings
  [:map
   [:topics {:optional true} [:sequential [:or :keyword :string]]]
   [:queue {:optional true} :keyword]
   [:max-attempts {:optional true} [:int {:min 1 :max 50}]]
   [:timeout-ms {:optional true} [:int {:min 1}]]
   [:connect-timeout-ms {:optional true} [:int {:min 1}]]
   [:max-response-bytes {:optional true} [:int {:min 0}]]
   [:allowed-hosts {:optional true} [:set :string]]
   [:require-https? {:optional true} :boolean]
   [:encryption-key {:optional true} [:maybe :string]]])
