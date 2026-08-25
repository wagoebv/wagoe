(ns wagoe.user.shell.middleware-test
  (:require [wagoe.user.shell.middleware :as sut]
            [wagoe.user.shell.auth]
            [wagoe.user.ports]
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

;; ===========================================================================
;; BOU-373: authentication that enriches without rejecting
;; ===========================================================================

(defn- echo-handler
  "Hands the request back as the body, so a test can see what the middleware
   put on it."
  [request]
  {:status 200 :body request})

(deftest ^:unit authenticate-if-present-never-rejects
  ;; The point of this middleware. It runs on every request, including
  ;; /health and public pages, so it must never be the thing that answers 401 —
  ;; that stays with the per-route guards. Its whole job is to put :user on the
  ;; request when it honestly can, so that tenant membership enrichment, which
  ;; runs after it and reads [:user :id], has something to read (BOU-373).
  (let [handler ((sut/authenticate-if-present ::no-service) echo-handler)]

    (testing "no credentials — passes through untouched"
      (let [resp (handler {:uri "/health"})]
        (is (= 200 (:status resp)))
        (is (nil? (:user (:body resp))) "nothing to authenticate, so no :user")
        (is (nil? (:auth-type (:body resp))))))

    (testing "an unparseable bearer token does not become a 401"
      ;; A bad token on a public route is not this middleware's business. A
      ;; route that needs a user still gets none, and its own guard rejects.
      (let [resp (handler {:uri     "/public"
                           :headers {"authorization" "Bearer not-a-real-jwt"}})]
        (is (= 200 (:status resp)) "rejection belongs to the per-route guard")
        (is (nil? (:user (:body resp))) "and an invalid token yields no user")))))

(deftest ^:unit authenticate-if-present-sets-user-from-a-valid-jwt
  ;; A real token rather than a stubbed validator: stubbing means writing down
  ;; what I believe the validator returns, and an earlier draft of this test
  ;; encoded the shape BOU-374 turned out to be a bug.
  (let [id      (java.util.UUID/randomUUID)
        token   (wagoe.user.shell.auth/create-jwt-token
                 {:id id :email "a@b.c" :role :admin} 1)
        handler ((sut/authenticate-if-present ::no-service) echo-handler)
        request (:body (handler {:uri     "/x"
                                 :headers {"authorization" (str "Bearer " token)}}))]
    (is (= {:id id :email "a@b.c" :role :admin} (:user request)))
    (is (= :jwt (:auth-type request)))
    (is (some? (get-in request [:user :id]))
        "wrap-tenant-membership reads exactly this path")))

(deftest ^:unit authenticate-if-present-sets-user-from-a-valid-session
  (let [;; The two methods the session path calls; the other 42 are not
        ;; reachable from here.
        service #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify wagoe.user.ports/IUserService
          (validate-session [_ _] {:user-id 7})
          (get-user-by-id [_ _] {:id 7 :email "s@b.c" :role :user}))
        handler ((sut/authenticate-if-present service) echo-handler)
        request (:body (handler {:uri "/x" :cookies {"session-token" {:value "tok"}}}))]
    (is (= {:id 7 :email "s@b.c" :role :user} (:user request)))
    (is (= :session (:auth-type request)))))
