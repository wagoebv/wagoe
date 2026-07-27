(ns wagoe.user.shell.middleware-test
  (:require [wagoe.user.shell.middleware :as sut]
            [clojure.test :refer [deftest is testing]]))

(deftest ^:unit extract-session-token-test
  (testing "decodes percent-encoded session token from cookie"
    (is (= "abc/def=="
           (sut/extract-session-token
            {:cookies {"session-token" {:value "abc%2Fdef%3D%3D"}}}))))

  (testing "decodes percent-encoded session token from header"
    (is (= "abc/def=="
           (sut/extract-session-token
            {:headers {"x-session-token" "abc%2Fdef%3D%3D"}}))))

  (testing "returns raw token when not encoded"
    (is (= "plain-token"
           (sut/extract-session-token
            {:cookies {"session-token" {:value "plain-token"}}}))))

  (testing "preserves raw plus signs in unencoded base64 tokens"
    (is (= "abc+def/ghi=="
           (sut/extract-session-token
            {:cookies {"session-token" {:value "abc+def/ghi=="}}}))))

  (testing "preserves plus signs when percent-decoding encoded tokens"
    (is (= "abc+def/ghi=="
           (sut/extract-session-token
            {:cookies {"session-token" {:value "abc%2Bdef%2Fghi%3D%3D"}}}))))

  (testing "falls back to original token on malformed encoding"
    (is (= "bad%2"
           (sut/extract-session-token
            {:cookies {"session-token" {:value "bad%2"}}})))))
