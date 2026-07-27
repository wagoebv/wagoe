(ns wagoe.devtools.shell.dashboard.pages.security-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.devtools.shell.dashboard.pages.security :as sec-page]
            [clojure.string :as str]))

(deftest ^:unit renders-security-page
  (testing "renders security summary"
    (let [html (sec-page/render {:config {:boundary/settings
                                          {:user-validation
                                           {:password-policy {:min-length 12
                                                              :require-uppercase? true
                                                              :require-lowercase? true
                                                              :require-numbers? true
                                                              :require-special-chars? false}}}}})]
      (is (string? html))
      (is (str/includes? html "Security"))
      (is (str/includes? html "Password")))))

(deftest ^:unit renders-empty-when-no-config
  (testing "renders empty state"
    (let [html (sec-page/render {})]
      (is (string? html))
      (is (str/includes? html "No security")))))
