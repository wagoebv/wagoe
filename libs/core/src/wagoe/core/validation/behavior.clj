;; ^:boundary/allow-throw — validation-testing DSL; the throws guard against
;; misuse of the test API (malformed scenarios), which is intentional developer
;; tooling behaviour, not runtime business logic.
(ns ^:boundary/allow-throw wagoe.core.validation.behavior
  "Behavior Specification DSL for validation testing.

  Data-first DSL for declarative validation testing with reusable scenarios.
  All core functions are pure; only test expansion emits side-effectful deftest forms.

  Example usage:

    ;; Define a scenario
    (def email-required-scenario
      {:name \"email-required-missing\"
       :description \"User email is required\"
       :module :user
       :schema-key :User
       :base {:name \"Test User\" :email \"test@example.com\"}
       :mutations [(remove-field :email)]
       :action validate-user-fn
       :assertions [{:expect :failure
                    :codes #{:user.email/required}
                    :snapshot? true}]})

    ;; Compile to test functions
    (compile-scenarios [email-required-scenario] {})
    ;; => [[\"email-required-missing\" (fn [] ...)]]

    ;; Or use macro for deftest generation
    (defbehavior-suite user-validation-tests
      [email-required-scenario
       email-format-scenario])"
  (:require [clojure.test :as t]
            [clojure.set :as set]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Mutation Helpers (Pure)
;; -----------------------------------------------------------------------------

(defn remove-field
  "Create a mutation that removes a field from the data.

  Args:
    field-path - Keyword or vector path to field

  Returns:
    Mutation function.

  Example:
    ((remove-field :email) {:name \"Test\" :email \"test@ex.com\"})
    ;; => {:name \"Test\"}

    ((remove-field [:address :city]) {:address {:city \"NY\" :zip \"10001\"}})
    ;; => {:address {:zip \"10001\"}}"
  [field-path]
  (let [path (if (vector? field-path) field-path [field-path])]
    (fn [data]
      (if (= 1 (count path))
        (dissoc data (first path))
        (update-in data (butlast path) dissoc (last path))))))

(defn set-field
  "Create a mutation that sets a field to a specific value.

  Args:
    field-path - Keyword or vector path to field
    value      - Value to set

  Returns:
    Mutation function.

  Example:
    ((set-field :email \"invalid\") {:name \"Test\" :email \"valid@ex.com\"})
    ;; => {:name \"Test\" :email \"invalid\"}"
  [field-path value]
  (let [path (if (vector? field-path) field-path [field-path])]
    (fn [data]
      (if (= 1 (count path))
        (assoc data (first path) value)
        (assoc-in data path value)))))

(defn replace-type
  "Create a mutation that replaces a field with wrong type.

  Args:
    field-path - Keyword or vector path to field
    wrong-type - Keyword indicating wrong type (:string, :number, :map, :vector, :boolean)

  Returns:
    Mutation function.

  Example:
    ((replace-type :age :string) {:age 25})
    ;; => {:age \"not-a-number\"}"
  [field-path wrong-type]
  (let [path (if (vector? field-path) field-path [field-path])
        wrong-value (case wrong-type
                      :string "wrong-type"
                      :number 999
                      :map {:wrong "type"}
                      :vector [:wrong :type]
                      :boolean false
                      :nil nil
                      "wrong-type")]
    (set-field path wrong-value)))

(defn out-of-range
  "Create a mutation that sets a numeric field out of range.

  Args:
    field-path - Keyword or vector path to field
    direction  - :above or :below
    boundary   - The boundary value

  Returns:
    Mutation function.

  Example:
    ((out-of-range :age :below 18) {:age 25})
    ;; => {:age 17}"
  [field-path direction boundary]
  (let [path (if (vector? field-path) field-path [field-path])
        value (case direction
                :above (inc boundary)
                :below (dec boundary)
                boundary)]
    (set-field path value)))

;; -----------------------------------------------------------------------------
;; Scenario Validation (Pure)
;; -----------------------------------------------------------------------------

(defn- validate-scenario
  "Validate that a scenario map has required keys.

  Args:
    scenario - Scenario map

  Returns:
    scenario if valid

  Throws:
    ex-info if invalid"
  [scenario]
  (let [required-keys [:name :action :assertions]
        missing-keys (remove #(contains? scenario %) required-keys)]
    (when (seq missing-keys)
      (throw (ex-info "Invalid scenario: missing required keys"
                      {:scenario (:name scenario "unnamed")
                       :missing-keys missing-keys}))))
  (when-not (fn? (:action scenario))
    (throw (ex-info "Invalid scenario: :action must be a function"
                    {:scenario (:name scenario)})))
  (when-not (vector? (:assertions scenario))
    (throw (ex-info "Invalid scenario: :assertions must be a vector"
                    {:scenario (:name scenario)})))
  scenario)

;; -----------------------------------------------------------------------------
;; Scenario Execution (Pure Data Flow)
;; -----------------------------------------------------------------------------

(defn- apply-mutations
  "Apply a sequence of mutations to base data.

  Args:
    base      - Base data map
    mutations - Vector of mutation functions

  Returns:
    Mutated data map."
  [base mutations]
  (reduce (fn [data mutation-fn]
            (mutation-fn data))
          base
          mutations))

(defn- check-assertion
  "Check a single assertion against a result.

  Args:
    assertion - Assertion map {:expect :success|:failure :codes #{...} :message str}
    result    - Validation result map

  Returns:
    Assertion result map {:passed? bool :message str}"
  [assertion result]
  (let [expected-status (:expect assertion)
        actual-status (:status result)
        expected-codes (or (:codes assertion) #{})
        actual-codes (set (map :code (:errors result [])))
        status-match? (= expected-status actual-status)
        codes-match? (or (empty? expected-codes)
                         (set/subset? expected-codes actual-codes))
        passed? (and status-match? codes-match?)
        message (if passed?
                  (str "✓ Expected " expected-status
                       (when (seq expected-codes)
                         (str " with codes " expected-codes)))
                  (str "✗ Expected " expected-status
                       (when (seq expected-codes)
                         (str " with codes " expected-codes))
                       "\n  Got " actual-status
                       (when (seq actual-codes)
                         (str " with codes " actual-codes))))]
    {:passed? passed?
     :message message
     :expected expected-status
     :actual actual-status
     :expected-codes expected-codes
     :actual-codes actual-codes}))

(defn execute-scenario
  "Execute a scenario and return results.

  Pure function that produces data describing test results.
  Does not perform side effects or assertions.

  Args:
    scenario - Scenario map
    opts     - Options (currently unused, for future extension)

  Returns:
    Execution result map:
    {:scenario-name str
     :input data
     :result validation-result
     :assertions [{:passed? bool :message str}]
     :all-passed? bool}"
  [scenario _opts]
  (validate-scenario scenario)
  (let [base (or (:base scenario) {})
        mutations (or (:mutations scenario) [])
        preconditions (or (:preconditions scenario) [])
        action (:action scenario)
        assertions (:assertions scenario)
        ;; Apply mutations to base data
        input (apply-mutations base mutations)
        ;; Check preconditions (all must return truthy)
        precond-results (map (fn [precond-fn] (precond-fn input)) preconditions)
        precond-failed? (some not precond-results)
        ;; Execute action if preconditions pass
        result (when-not precond-failed?
                 (action input))
        ;; Check assertions
        assertion-results (if precond-failed?
                            [{:passed? false
                              :message "Preconditions failed"}]
                            (map #(check-assertion % result) assertions))
        all-passed? (every? :passed? assertion-results)]
    {:scenario-name (:name scenario)
     :description (:description scenario)
     :input input
     :result result
     :assertions assertion-results
     :all-passed? all-passed?
     :precond-failed? (boolean precond-failed?)}))

;; -----------------------------------------------------------------------------
;; Scenario Compilation (Pure)
;; -----------------------------------------------------------------------------

(defn compile-scenarios
  "Compile scenarios into test function pairs.

  Pure function that produces [test-name test-fn] pairs.
  Test functions perform clojure.test assertions when called.

  Args:
    scenarios - Vector of scenario maps
    opts      - Options map (currently unused)

  Returns:
    Vector of [test-name test-fn] pairs.

  Example:
    (compile-scenarios [{:name \"test-1\" :action ... :assertions [...]}] {})
    ;; => [[\"test-1\" (fn [] ...)]]"
  [scenarios opts]
  (mapv (fn [scenario]
          (let [test-name (:name scenario)
                test-fn (fn []
                          (let [exec-result (execute-scenario scenario opts)]
                            (doseq [assertion-result (:assertions exec-result)]
                              (t/is (:passed? assertion-result)
                                    (:message assertion-result)))
                           ;; Return result for inspection if needed
                            exec-result))]
            [test-name test-fn]))
        scenarios))

;; -----------------------------------------------------------------------------
;; Macro Layer
;; -----------------------------------------------------------------------------

(defmacro defbehavior-suite
  "Define a test suite from behavior scenarios.

  Generates a deftest for each scenario in the suite.

  Args:
    suite-name - Symbol for the test suite name
    scenarios  - Vector of scenario maps (evaluated)
    opts       - Optional options map

  Example:
    (defbehavior-suite user-validation-tests
      [{:name \"email-required\"
        :action validate-user
        :base {:name \"Test\"}
        :mutations [(remove-field :email)]
        :assertions [{:expect :failure :codes #{:user.email/required}}]}]
      {:module :user})"
  ([suite-name scenarios]
   `(defbehavior-suite ~suite-name ~scenarios {}))
  ([suite-name scenarios opts]
   (let [suite-sym suite-name]
     `(do
        ~@(for [[test-name test-fn-code] (eval `(compile-scenarios ~scenarios ~opts))]
            (let [test-sym (symbol (str suite-sym "--" (str/replace test-name #"[^a-zA-Z0-9_-]" "-")))]
              `(t/deftest ~test-sym
                 (t/testing ~test-name
                   (~test-fn-code)))))))))

;; -----------------------------------------------------------------------------
;; Scenario Templates (Reusable Patterns)
;; -----------------------------------------------------------------------------

(defn missing-required-field-template
  "Create a scenario for testing missing required field.

  Args:
    field-key   - Keyword for the field
    rule-code   - Expected validation error code keyword
    base-data   - Base valid data
    action-fn   - Validation function

  Returns:
    Scenario map.

  Example:
    (missing-required-field-template
      :email
      :user.email/required
      {:name \"Test\" :email \"test@ex.com\"}
      validate-user)"
  [field-key rule-code base-data action-fn]
  {:name (str (name field-key) "-required-missing")
   :description (str "Field " field-key " is required")
   :base base-data
   :mutations [(remove-field field-key)]
   :action action-fn
   :assertions [{:expect :failure
                 :codes #{rule-code}}]})

(defn wrong-format-template
  "Create a scenario for testing wrong format.

  Args:
    field-key   - Keyword for the field
    rule-code   - Expected validation error code keyword
    invalid-val - Invalid value for the field
    base-data   - Base valid data
    action-fn   - Validation function

  Returns:
    Scenario map."
  [field-key rule-code invalid-val base-data action-fn]
  {:name (str (name field-key) "-wrong-format")
   :description (str "Field " field-key " has wrong format")
   :base base-data
   :mutations [(set-field field-key invalid-val)]
   :action action-fn
   :assertions [{:expect :failure
                 :codes #{rule-code}}]})

(defn valid-data-template
  "Create a scenario for testing valid data.

  Args:
    name        - Scenario name
    base-data   - Valid data
    action-fn   - Validation function

  Returns:
    Scenario map."
  [name base-data action-fn]
  {:name name
   :description "Valid data should pass validation"
   :base base-data
   :mutations []
   :action action-fn
   :assertions [{:expect :success}]})
