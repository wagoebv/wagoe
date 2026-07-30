(ns wagoe.core.validation.context-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.core.validation.context :as ctx]))

(def User
  [:map
   [:id :uuid]
   [:email :string]
   [:name :string]
   [:role [:enum :admin :user :viewer]]
   [:password :string]
   [:api-key :string]])

(deftest ^:unit operation-specific-template-test
  (testing "create operation template"
    (let [template (ctx/get-operation-template :create :required)]
      (is (string? template))
      (is (re-find #"when creating" template))))

  (testing "update operation template"
    (let [template (ctx/get-operation-template :update :required)]
      (is (string? template))
      (is (re-find #"when updating" template))))

  (testing "delete operation template"
    (let [template (ctx/get-operation-template :delete :forbidden)]
      (is (string? template))
      (is (re-find #"Cannot delete" template))))

  (testing "unknown operation returns nil"
    (is (nil? (ctx/get-operation-template :unknown :required)))))

(deftest ^:unit role-based-guidance-test
  (testing "admin role guidance"
    (let [guidance (ctx/get-role-guidance :admin)]
      (is (string? guidance))
      (is (re-find #"full access" guidance))))

  (testing "user role guidance"
    (let [guidance (ctx/get-role-guidance :user)]
      (is (string? guidance))
      (is (re-find #"limited access" guidance))))

  (testing "viewer role guidance"
    (let [guidance (ctx/get-role-guidance :viewer)]
      (is (string? guidance))
      (is (re-find #"view-only access" guidance))))

  (testing "moderator role guidance"
    (let [guidance (ctx/get-role-guidance :moderator)]
      (is (string? guidance))
      (is (re-find #"moderation privileges" guidance))))

  (testing "guest role guidance"
    (let [guidance (ctx/get-role-guidance :guest)]
      (is (string? guidance))
      (is (re-find #"Not logged in" guidance))))

  (testing "unknown role returns nil"
    (is (nil? (ctx/get-role-guidance :unknown)))))

(deftest ^:unit render-contextual-message-test
  (testing "required field with create operation"
    (let [message (ctx/render-contextual-message
                   :required
                   {:field :email}
                   {:operation :create
                    :entity "user"
                    :role :viewer}
                   {})]
      (is (string? message))
      (is (re-find #"Email" message))
      (is (re-find #"when creating" message))
      (is (re-find #"view-only access" message))))

  (testing "invalid field with update operation"
    (let [message (ctx/render-contextual-message
                   :invalid
                   {:field :email
                    :value "not-an-email"}
                   {:operation :update
                    :entity "user"
                    :role :user}
                   {})]
      (is (string? message))
      (is (re-find #"Email" message))
      (is (re-find #"when updating" message))
      (is (re-find #"limited access" message))))

  (testing "duplicate field with no operation context"
    (let [message (ctx/render-contextual-message
                   :duplicate
                   {:field :email
                    :value "test@example.com"}
                   {:role :admin}
                   {})]
      (is (string? message))
      (is (re-find #"Email" message))
      (is (re-find #"full access" message))))

  (testing "with tenant context"
    (let [message (ctx/render-contextual-message
                   :required
                   {:field :email}
                   {:operation :create
                    :entity "user"
                    :role :admin
                    :tenant-id "tenant-123"}
                   {})]
      (is (string? message))
      (is (re-find #"tenant-123" message))))

  (testing "without role or operation context"
    (let [message (ctx/render-contextual-message
                   :required
                   {:field :email}
                   {}
                   {})]
      (is (string? message))
      (is (re-find #"Email" message))
      ;; Should fall back to base message without context
      (is (not (re-find #"when creating" message))))))

(deftest ^:unit generate-example-payload-test
  (testing "generates valid example with deterministic seed"
    (let [example1 (ctx/generate-example-payload User :email {:seed 42})
          example2 (ctx/generate-example-payload User :email {:seed 42})]
      (is (map? example1))
      (is (= example1 example2)) ;; Deterministic
      (is (contains? example1 :email))
      (is (string? (:email example1)))))

  (testing "redacts sensitive fields"
    (let [example (ctx/generate-example-payload User :password {:seed 42})]
      (is (= "<password>" (:password example)))
      (is (= "<api-key>" (:api-key example)))))

  (testing "includes only specified fields"
    (let [example (ctx/generate-example-payload User :email
                                                {:include-fields [:name :role]
                                                 :seed 42})]
      (is (contains? example :email))
      (is (contains? example :name))
      (is (contains? example :role))
      (is (not (contains? example :password)))))

  (testing "excludes specified fields"
    (let [example (ctx/generate-example-payload User :email
                                                {:exclude-fields [:password :api-key]
                                                 :seed 42})]
      (is (contains? example :email))
      (is (not (contains? example :password)))
      (is (not (contains? example :api-key)))))

  (testing "different seeds produce different values"
    (let [example1 (ctx/generate-example-payload User :email {:seed 42})
          example2 (ctx/generate-example-payload User :email {:seed 99})]
      (is (not= example1 example2)))))

(deftest ^:unit format-next-steps-test
  (testing "formats list of steps"
    (let [steps ["Check the email format"
                 "Verify the domain exists"
                 "Ensure no duplicates"]
          formatted (ctx/format-next-steps steps)]
      (is (string? formatted))
      (is (re-find #"Next steps:" formatted))
      (is (re-find #"1\." formatted))
      (is (re-find #"2\." formatted))
      (is (re-find #"3\." formatted))
      (is (re-find #"Check the email format" formatted))
      (is (re-find #"Verify the domain exists" formatted))
      (is (re-find #"Ensure no duplicates" formatted))))

  (testing "handles empty list"
    (is (= "" (ctx/format-next-steps []))))

  (testing "handles single step"
    (let [formatted (ctx/format-next-steps ["Fix the error"])]
      (is (re-find #"Next steps:" formatted))
      (is (re-find #"1\." formatted))
      (is (re-find #"Fix the error" formatted)))))

(deftest ^:unit add-context-to-error-test
  (testing "adds contextual message and example to error"
    (let [error {:field :email
                 :code :user.email/required
                 :message "Email is required"
                 :params {:field-name "Email"}}
          context {:operation :create
                   :entity "user"
                   :role :viewer}
          enriched (ctx/add-context-to-error error User context)]
      (is (contains? enriched :contextual-message))
      (is (string? (:contextual-message enriched)))
      (is (re-find #"when creating" (:contextual-message enriched)))
      (is (contains? enriched :example))
      (is (map? (:example enriched)))
      (is (contains? (:example enriched) :email))))

  (testing "preserves original error fields"
    (let [error {:field :email
                 :code :user.email/required
                 :message "Email is required"
                 :params {:field-name "Email"}
                 :suggestion "Provide a valid email"}
          context {:operation :create
                   :entity "user"}
          enriched (ctx/add-context-to-error error User context)]
      (is (= :email (:field enriched)))
      (is (= :user.email/required (:code enriched)))
      (is (= "Email is required" (:message enriched)))
      (is (= "Provide a valid email" (:suggestion enriched)))))

  (testing "works without schema (no example generation)"
    (let [error {:field :email
                 :code :user.email/required
                 :message "Email is required"}
          context {:operation :create
                   :entity "user"
                   :role :admin}
          enriched (ctx/add-context-to-error error nil context)]
      (is (contains? enriched :contextual-message))
      (is (not (contains? enriched :example))))))

(deftest ^:unit multi-tenant-context-test
  (testing "includes tenant ID in contextual message"
    (let [message (ctx/render-contextual-message
                   :required
                   {:field :email}
                   {:operation :create
                    :entity "user"
                    :tenant-id "acme-corp"}
                   {})]
      (is (re-find #"acme-corp" message))))

  (testing "tenant context in error enrichment"
    (let [error {:field :email
                 :code :user.email/required
                 :message "Email is required"}
          context {:operation :create
                   :entity "user"
                   :tenant-id "acme-corp"}
          enriched (ctx/add-context-to-error error User context)]
      (is (re-find #"acme-corp" (:contextual-message enriched))))))

(deftest ^:unit integration-with-existing-messages-test
  (testing "contextual message complements base message"
    (let [error {:field :email
                 :code :user.email/invalid
                 :message "Email must be a valid email address"
                 :params {:field-name "Email"
                          :value "not-an-email"}}
          context {:operation :update
                   :entity "user"
                   :role :user}
          enriched (ctx/add-context-to-error error User context)]
      ;; Original message still present
      (is (= "Email must be a valid email address" (:message enriched)))
      ;; Contextual message adds operation and role info
      (is (string? (:contextual-message enriched)))
      (is (re-find #"when updating" (:contextual-message enriched)))
      (is (re-find #"limited access" (:contextual-message enriched))))))
