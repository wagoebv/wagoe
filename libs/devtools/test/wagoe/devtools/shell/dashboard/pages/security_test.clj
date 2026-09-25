(ns wagoe.devtools.shell.dashboard.pages.security-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.devtools.shell.dashboard.pages.security :as sec-page]
            [clojure.string :as str]))

(deftest ^:unit renders-security-page
  (testing "renders security summary"
    (let [html (sec-page/render {:config {:wagoe/settings
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

(deftest ^:unit says-so-when-no-password-policy-is-configured
  ;; The tile and the card used to render their heading and nothing else, which
  ;; beside five populated tiles reads as a rendering failure (BOU-510).
  (testing "the stat tile carries a value rather than a blank"
    (let [html (sec-page/render {:config {:wagoe/settings {}}})]
      (is (str/includes? html "Password Strength"))
      (is (str/includes? html "Not configured"))))

  (testing "the policy card says why it is empty"
    (let [html (sec-page/render {:config {:wagoe/settings {}}})]
      (is (str/includes? html "No password policy configured"))))

  (testing "a configured policy still renders its checks, not the fallback"
    (let [html (sec-page/render {:config {:wagoe/settings
                                          {:user-validation
                                           {:password-policy {:min-length 12
                                                              :require-uppercase? true
                                                              :require-lowercase? true
                                                              :require-numbers? true
                                                              :require-special-chars? false}}}}})]
      (is (str/includes? html "Min length: 12"))
      (is (not (str/includes? html "No password policy configured"))))))
