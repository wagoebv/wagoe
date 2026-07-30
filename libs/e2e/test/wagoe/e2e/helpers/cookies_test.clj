(ns wagoe.e2e.helpers.cookies-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.e2e.helpers.cookies :as cookies]))

(deftest ^:unit parse-session-token-from-set-cookie
  (testing "returns the token value when Set-Cookie has session-token with HttpOnly"
    (let [headers {"set-cookie" ["session-token=abc123; Path=/; HttpOnly"
                                 "other-cookie=foo; Path=/"]}]
      (is (= "abc123" (cookies/session-token headers))))))

(deftest ^:unit reject-session-token-without-httponly
  (testing "throws when session-token is set without HttpOnly flag"
    (let [headers {"set-cookie" ["session-token=abc123; Path=/"]}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (cookies/session-token headers))))))

(deftest ^:unit remembered-email-parsing
  (testing "returns remembered-email cookie value when present"
    (let [headers {"set-cookie" ["remembered-email=user%40acme.test; Path=/; Max-Age=2592000"]}]
      (is (= "user@acme.test" (cookies/remembered-email headers))))))
