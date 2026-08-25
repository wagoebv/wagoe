(ns wagoe.user.shell.middleware-test
  (:require [wagoe.user.shell.middleware :as sut]
            [wagoe.user.shell.auth]
            [buddy.sign.jwt]
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

;; ===========================================================================
;; JWT authentication: a token this system issued, and one it did not
;; ===========================================================================

(defn- echo-user-handler
  "Hands back what the middleware put on the request, so a test can see whether
   authentication happened and with which identity."
  [request]
  {:status 200 :body {:user (:user request) :auth-type (:auth-type request)}})

(deftest ^:unit jwt-authentication-rejects-a-token-it-did-not-issue
  ;; validate-jwt-token answers {:valid? false :error ...} on failure — a map,
  ;; and therefore truthy. The middleware branched on the map itself, so the
  ;; rejection branch was unreachable and any Authorization header
  ;; authenticated. require-authenticated then passed, because a user map with
  ;; nil fields is still `some?`.
  (let [handler (sut/jwt-authentication-middleware echo-user-handler)]

    (testing "garbage is not a token"
      (let [resp (handler {:uri "/x" :headers {"authorization" "Bearer not-a-real-jwt"}})]
        (is (= 401 (:status resp)))
        (is (nil? (get-in resp [:body :user])) "and no identity is manufactured")))

    (testing "a token signed with the wrong secret is not a token"
      (let [forged (buddy.sign.jwt/sign {:sub "1" :email "e@x.c" :role "admin"}
                                        "an-attackers-secret-of-sufficient-length"
                                        {:alg :hs256})
            resp   (handler {:uri "/x" :headers {"authorization" (str "Bearer " forged)}})]
        (is (= 401 (:status resp)))))

    (testing "no token at all"
      (is (= 401 (:status (handler {:uri "/x"})))))))

(deftest ^:unit jwt-authentication-accepts-a-token-this-system-issued
  ;; The round trip, through the real signing path — the claims the middleware
  ;; reads have to be the ones create-jwt-token writes. It read :user-id from
  ;; the wrapper map; the id is :sub, inside :claims. So even a valid token
  ;; produced {:id nil :email nil :role nil}.
  (let [id      (java.util.UUID/randomUUID)
        token   (wagoe.user.shell.auth/create-jwt-token
                 {:id id :email "real@example.com" :role :admin} 1)
        handler (sut/jwt-authentication-middleware echo-user-handler)
        resp    (handler {:uri "/x" :headers {"authorization" (str "Bearer " token)}})]
    (is (= 200 (:status resp)))
    (is (= :jwt (get-in resp [:body :auth-type])))
    (is (= id (get-in resp [:body :user :id])) "the id survives the round trip")
    (is (= "real@example.com" (get-in resp [:body :user :email])))
    (is (= :admin (get-in resp [:body :user :role]))
        "and the role comes back a keyword, as the rest of the codebase uses it")))
