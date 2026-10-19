(ns wagoe.external.shell.adapters.mail-tls-config-test
  (:require [clojure.test :refer [deftest is]]
            [wagoe.external.shell.adapters.imap :as imap]
            [wagoe.external.shell.adapters.smtp :as smtp]))

(defn- error-type [create config]
  (try (create config) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(deftest ^:unit invalid-tls-config-test
  (doseq [bad                [{:ssl-trust true} {:ssl-trust ""} {:ssl-trust []} {:ssl-trust "a.example,b.example"}
                               {:ssl-trust ["a.example" "*"]} {:ssl-trust "a.example *"}
                               {:check-server-identity? "false"}]
          [create base]      [[smtp/create-smtp-provider {:host "h" :port 25 :from "a@b.c"}]
                              [imap/create-imap-mailbox {:host "h" :port 993 :username "u" :password "p"}]]]
    (is (= :validation-error (error-type create (merge base bad))) (pr-str bad))))

(deftest ^:unit valid-tls-config-test
  (doseq [ok [{} {:ssl-trust "*"} {:ssl-trust "a.example b.example"} {:ssl-trust ["a.example" "10.0.0.5"]}
              {:ssl-trust nil :check-server-identity? nil} {:check-server-identity? false}]]
    (is (smtp/create-smtp-provider (merge {:host "h" :port 25 :from "a@b.c"} ok)) (pr-str ok))))
