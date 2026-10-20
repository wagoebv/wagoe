(ns wagoe.user.shell.cli-error-format-test
  "BOU-447: `bb create-admin` reported a password the policy rejected as

     Error: Invalid user data
     Details: Missing fields: :password

   The CLI printed the fields every validation error mentioned under the label
   `Missing fields`, so a rule violation read as a field the user had not sent
   — and the message saying which rule was dropped."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.cli :as cli]))

(def ^:private policy-violation
  {:type "validation-error"
   :detail "Invalid user data"
   :missing-fields []
   :field-errors [{:field :password
                   :code :password-policy-violation
                   :message "Password must have: at least one number"}]})

(deftest ^:unit a-policy-violation-prints-the-rule
  (let [out (cli/format-error :table policy-violation)]
    (is (str/includes? out "at least one number")
        "the only instruction the user gets must be the one that helps")
    (is (not (str/includes? out "Missing fields"))
        "the password was sent — calling it missing sends the user the wrong way")
    (is (str/includes? out "password"))))

(deftest ^:unit a-genuinely-absent-field-is-still-reported-as-missing
  (let [out (cli/format-error :table {:type "validation-error"
                                      :detail "Invalid user data"
                                      :missing-fields [:email]
                                      :field-errors []})]
    (is (str/includes? out "missing fields: :email"))))

(deftest ^:unit both-kinds-are-reported-together
  (let [out (cli/format-error :table (assoc policy-violation :missing-fields [:email]))]
    (is (str/includes? out "at least one number"))
    (is (str/includes? out "missing fields: :email"))))

(deftest ^:unit json-output-still-carries-the-whole-error
  (let [out (cli/format-error :json policy-violation)]
    (is (str/includes? out "password-policy-violation"))))

;; BOU-789: the pipeline hands the CLI its error body, `{:error {...}}`, and
;; `bb create-admin` printed "Error: Unknown error".

(def ^:private contains-email
  (ex-info "Password does not meet requirements"
           {:type :validation-error
            :errors [{:field :password
                      :code :contains-email
                      :message "Password cannot contain your email address"}]}))

(defn- failing-service [ex]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify ports/IUserService
    (register-user [_ _] (throw ex))
    (get-user-by-id [_ _] (throw ex))))

(deftest ^:unit create-prints-the-validation-message
  (let [{:keys [status message]} (cli/dispatch-command
                                  :create
                                  {:email "abc@b.com" :name "A" :role "admin"
                                   :password "abc@b.comXyz123!"}
                                  (failing-service contains-email))]
    (is (= 1 status))
    (is (str/includes? message "Password does not meet requirements"))
    (is (str/includes? message "Password cannot contain your email address"))
    (is (not (str/includes? message "Unknown error")))))

(deftest ^:unit find-prints-the-error-it-failed-with
  (let [{:keys [status message]} (cli/dispatch-command
                                  :find
                                  {:user-id (str (random-uuid))}
                                  (failing-service (ex-info "User not found"
                                                            {:type :user-not-found})))]
    (is (= 1 status))
    (is (str/includes? message "User not found"))))

(deftest ^:unit json-output-of-an-error-body-is-not-wrapped-twice
  (let [out (cli/format-error :json {:error {:type "validation-error" :message "Bad"}})]
    (is (str/includes? out "Bad"))
    (is (not (re-find #"\"error\"\s*:\s*\{\s*\"error\"" out)))))
