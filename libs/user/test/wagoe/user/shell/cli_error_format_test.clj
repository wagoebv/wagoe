(ns wagoe.user.shell.cli-error-format-test
  "BOU-447: `bb create-admin` reported a password the policy rejected as

     Error: Invalid user data
     Details: Missing fields: :password

   The CLI printed the fields every validation error mentioned under the label
   `Missing fields`, so a rule violation read as a field the user had not sent
   — and the message saying which rule was dropped."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
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
