(ns wagoe.user.shell.cli-password-test
  "bb create-admin pipes the password into the user CLI, which printed a third
   `Password: ` prompt that a script driving the wizard then answered."
  (:require [clojure.test :refer [deftest is]]
            [wagoe.user.shell.cli :as cli]))

(deftest ^:unit piped-password-is-read-without-a-prompt
  (let [result (atom nil)
        out (with-out-str
              (binding [*in* (java.io.BufferedReader. (java.io.StringReader. "s3cret!\n"))]
                (reset! result (cli/read-hidden-password nil "Password"))))]
    (is (= "s3cret!" @result))
    (is (= "" out))))
