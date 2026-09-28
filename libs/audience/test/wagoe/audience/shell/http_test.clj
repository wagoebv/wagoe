(ns wagoe.audience.shell.http-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.audience.shell.http :as sut]))

(deftest ^:unit ^:security an-invalid-definition-names-its-fields-not-the-schema
  ;; `details` was the Malli explanation as a string: the whole schema, fn
  ;; objects included (BOU-586).
  (doseq [[label response]
          [["create" (sut/create-audience-handler nil nil {:parameters {:body {:id :x}}})]
           ["update" (sut/update-audience-handler nil nil {:path-params {:id "x"}
                                                           :parameters  {:body {}}})]]]
    (testing label
      (let [err (get-in response [:body :error])]
        (is (= 422 (:status response)))
        (is (= "unprocessable" (:type err)))
        (is (= ["missing required key"] (get-in err [:details :errors :label])))
        (is (not (str/includes? (pr-str err) ":map")) "the schema leaked")))))
