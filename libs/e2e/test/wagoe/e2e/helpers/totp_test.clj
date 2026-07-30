(ns wagoe.e2e.helpers.totp-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.e2e.helpers.totp :as totp]))

(deftest ^:unit current-code-is-six-digits
  (testing "current-code returns a 6-digit numeric string for a base32 secret"
    (let [secret "JBSWY3DPEHPK3PXP"
          code   (totp/current-code secret)]
      (is (string? code))
      (is (re-matches #"\d{6}" code)))))
