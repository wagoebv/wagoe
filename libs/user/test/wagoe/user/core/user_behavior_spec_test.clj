(ns wagoe.user.core.user-behavior-spec-test
  "Behavior Specification DSL applied to user validation (CreateUserRequest).

  These scenarios demonstrate declarative validation testing using the
  behavior DSL. We intentionally assert only on the expected status to keep
  the tests robust against internal error structure changes."
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.core.validation.behavior :as behavior]
            [wagoe.core.validation.coverage :as coverage]
            [clojure.java.io :as io]
            [wagoe.user.core.user :as user-core]
            [support.validation-helpers :as vh]))

;; Tag for Phase 3
(alter-meta! *ns* assoc :kaocha/tags [:phase3 :behavior])

;; ----------------------------------------------------------------------------
;; Action Adapter: Adapt core validation result to Behavior DSL format
;; ----------------------------------------------------------------------------

(defn validate-create-user
  "Wrap user-core/validate-user-creation-request to the DSL result shape.

  Returns {:status :success :data ...} or {:status :failure :errors [...]}
  where errors is a vector (codes omitted intentionally)."
  [data]
  (let [res (user-core/validate-user-creation-request data vh/test-validation-config)]
    (if (:valid? res)
      {:status :success :data (:data res)}
      (let [explain (:errors res)
            errs (vec (for [e (:errors explain)]
                        {:path (:path e) :message (:message e)}))]
        {:status :failure :errors errs}))))

(def base-valid
  {:email "user@example.com"
   :name "Behavior Spec User"
   :role :user
   :password "behavior-test-password"})

(def ^:private registered-rule-ids
  #{:user.create/valid
    :user.email/required
    :user.name/required
    :user.role/required
    :user.email/invalid-format})

(def ^:private executed-rule-ids (atom #{}))

(def scenarios
  [;; Happy path
   {:name "create-user--valid"
    :description "Valid CreateUserRequest passes"
    :rule-id :user.create/valid
    :base base-valid
    :mutations []
    :action validate-create-user
    :assertions [{:expect :success}]}

   ;; Missing required fields (status-only assertions to avoid coupling to error codes)
   {:name "create-user--email-required-missing"
    :description "Email required"
    :rule-id :user.email/required
    :base base-valid
    :mutations [(behavior/remove-field :email)]
    :action validate-create-user
    :assertions [{:expect :failure}]}

   {:name "create-user--name-required-missing"
    :description "Name required"
    :rule-id :user.name/required
    :base base-valid
    :mutations [(behavior/remove-field :name)]
    :action validate-create-user
    :assertions [{:expect :failure}]}

   {:name "create-user--role-required-missing"
    :description "Role required"
    :rule-id :user.role/required
    :base base-valid
    :mutations [(behavior/remove-field :role)]
    :action validate-create-user
    :assertions [{:expect :failure}]}

   ;; Wrong format (email)
   {:name "create-user--email-wrong-format"
    :description "Invalid email format is rejected"
    :rule-id :user.email/invalid-format
    :base base-valid
    :mutations [(behavior/set-field :email "not-an-email")]
    :action validate-create-user
    :assertions [{:expect :failure}]}])

(deftest ^:unit user-create-behavior-suite
  (testing "User Create behavior scenarios"
    (doseq [sc scenarios]
      (let [[_test-name f] (first (behavior/compile-scenarios [sc] {}))
            exec (f)]
        (when (:all-passed? exec)
          (when-let [rid (:rule-id sc)]
            (swap! executed-rule-ids conj rid))))))
  (testing "Compute and write coverage report for user module"
    (let [cov (coverage/compute {:registered registered-rule-ids
                                 :executed @executed-rule-ids
                                 :by-module {:user registered-rule-ids}})
          ts (.toString (java.time.Instant/now))
          edn (coverage/edn-report cov {:timestamp ts :metadata {:module :user}})
          human (coverage/human-report cov {})
          out-edn (io/file "test/reports/coverage/user.edn")
          out-txt (io/file "test/reports/coverage/user.txt")]
      (.mkdirs (.getParentFile out-edn))
      (spit out-edn (pr-str edn))
      (spit out-txt human)
      (is (string? human)))))
