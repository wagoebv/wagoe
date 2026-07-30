(ns wagoe.realtime.core.auth-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.realtime.core.auth :as auth]))

(deftest ^:unit parse-query-string-test
  (testing "parsing valid query string"
    (is (= {"token" "abc123" "foo" "bar"}
           (auth/parse-query-string "token=abc123&foo=bar"))))
  
  (testing "parsing query string with empty value"
    (is (= {"token" "abc123" "empty" ""}
           (auth/parse-query-string "token=abc123&empty="))))
  
  (testing "parsing nil query string"
    (is (nil? (auth/parse-query-string nil))))
  
  (testing "parsing empty query string"
    (is (nil? (auth/parse-query-string "")))))

(deftest ^:unit extract-token-from-query-test
  (testing "extracting token from query params"
    (is (= "abc123"
           (auth/extract-token-from-query {"token" "abc123"}))))
  
  (testing "extracting token when not present"
    (is (nil? (auth/extract-token-from-query {"foo" "bar"}))))
  
  (testing "extracting from nil params"
    (is (nil? (auth/extract-token-from-query nil)))))

(deftest ^:unit extract-token-from-query-string-test
  (testing "extracting token from query string"
    (is (= "abc123"
           (auth/extract-token-from-query-string "token=abc123&foo=bar"))))
  
  (testing "extracting when token not in query string"
    (is (nil? (auth/extract-token-from-query-string "foo=bar")))))

(deftest ^:unit token-expired?-test
  (let [claims-with-exp {:exp 1609459200} ;; 2021-01-01 00:00:00 UTC
        claims-without-exp {}]
    
    (testing "token is expired"
      (is (auth/token-expired? claims-with-exp 1609459300))) ;; 100 seconds later
    
    (testing "token is not expired"
      (is (not (auth/token-expired? claims-with-exp 1609459100)))) ;; 100 seconds before
    
    (testing "token with no expiry is never expired"
      (is (not (auth/token-expired? claims-without-exp 9999999999))))))

(deftest ^:unit has-permission?-test
  (let [claims {:permissions #{:read :write}}]
    
    (testing "has required permission"
      (is (auth/has-permission? claims :read))
      (is (auth/has-permission? claims :write)))
    
    (testing "does not have required permission"
      (is (not (auth/has-permission? claims :delete))))))

(deftest ^:unit has-any-permission?-test
  (let [claims {:permissions #{:read}}]
    
    (testing "has at least one permission"
      (is (auth/has-any-permission? claims #{:read :write}))
      (is (auth/has-any-permission? claims #{:read})))
    
    (testing "has no required permissions"
      (is (not (auth/has-any-permission? claims #{:write :delete}))))))

(deftest ^:unit has-all-permissions?-test
  (let [claims {:permissions #{:read :write :delete}}]
    
    (testing "has all required permissions"
      (is (auth/has-all-permissions? claims #{:read :write}))
      (is (auth/has-all-permissions? claims #{:read})))
    
    (testing "missing some required permissions"
      (is (not (auth/has-all-permissions? claims #{:read :admin}))))))

(deftest ^:unit has-role?-test
  (let [claims {:roles #{:user :admin}}]
    
    (testing "has required role"
      (is (auth/has-role? claims :user))
      (is (auth/has-role? claims :admin)))
    
    (testing "does not have required role"
      (is (not (auth/has-role? claims :superuser))))))

(deftest ^:unit has-any-role?-test
  (let [claims {:roles #{:user}}]
    
    (testing "has at least one role"
      (is (auth/has-any-role? claims #{:user :admin}))
      (is (auth/has-any-role? claims #{:user})))
    
    (testing "has no required roles"
      (is (not (auth/has-any-role? claims #{:admin :moderator}))))))

(deftest ^:unit connection-authorized?-test
  (let [valid-claims {:user-id #uuid "550e8400-e29b-41d4-a716-446655440000"
                      :roles #{:user}
                      :permissions #{:read :write}
                      :exp 2000000000}
        now-seconds 1609459200]
    
    (testing "authorized with valid claims and no restrictions"
      (let [result (auth/connection-authorized? valid-claims {} now-seconds)]
        (is (:authorized? result))))
    
    (testing "unauthorized due to expired token"
      (let [expired-claims (assoc valid-claims :exp 1609459100)
            result (auth/connection-authorized? expired-claims {} now-seconds)]
        (is (not (:authorized? result)))
        (is (= "Token expired" (:reason result)))))
    
    (testing "authorized with expired token when explicitly allowed"
      (let [expired-claims (assoc valid-claims :exp 1609459100)
            result (auth/connection-authorized? expired-claims
                                                {:allow-expired true}
                                                now-seconds)]
        (is (:authorized? result))))
    
    (testing "unauthorized due to missing required permissions"
      (let [result (auth/connection-authorized? valid-claims
                                                {:required-permissions #{:admin}}
                                                now-seconds)]
        (is (not (:authorized? result)))
        (is (= "Missing required permissions" (:reason result)))))
    
    (testing "authorized with required permissions"
      (let [result (auth/connection-authorized? valid-claims
                                                {:required-permissions #{:read :write}}
                                                now-seconds)]
        (is (:authorized? result))))
    
    (testing "unauthorized due to missing required role"
      (let [result (auth/connection-authorized? valid-claims
                                                {:required-roles #{:admin}}
                                                now-seconds)]
        (is (not (:authorized? result)))
        (is (= "Missing required role" (:reason result)))))
    
    (testing "authorized with required role"
      (let [result (auth/connection-authorized? valid-claims
                                                {:required-roles #{:user}}
                                                now-seconds)]
        (is (:authorized? result))))))

(deftest ^:unit extract-token-from-request-test
  (testing "extract from query params"
    (let [request {:query-params {"token" "abc123"}}]
      (is (= "abc123" (auth/extract-token-from-request request)))))
  
  (testing "extract from query string when params not parsed"
    (let [request {:query-string "token=abc123"}]
      (is (= "abc123" (auth/extract-token-from-request request)))))
  
  (testing "extract from Authorization header"
    (let [request {:headers {"authorization" "Bearer abc123"}}]
      (is (= "abc123" (auth/extract-token-from-request request)))))
  
  (testing "prefer query params over header"
    (let [request {:query-params {"token" "from-params"}
                   :headers {"authorization" "Bearer from-header"}}]
      (is (= "from-params" (auth/extract-token-from-request request)))))
  
  (testing "return nil when no token found"
    (let [request {:query-params {}}]
      (is (nil? (auth/extract-token-from-request request))))))

(deftest ^:unit auth-error-test
  (testing "create auth error with reason only"
    (let [error (auth/auth-error "Invalid token")]
      (is (= :unauthorized (:error error)))
      (is (= "Invalid token" (:reason error)))
      (is (= {} (:details error)))))
  
  (testing "create auth error with details"
    (let [error (auth/auth-error "Invalid token" {:code 401})]
      (is (= :unauthorized (:error error)))
      (is (= "Invalid token" (:reason error)))
      (is (= {:code 401} (:details error))))))

(deftest ^:unit valid-jwt-claims?-test
  (testing "valid JWT claims pass validation"
    (let [claims {:user-id #uuid "550e8400-e29b-41d4-a716-446655440000"
                  :roles #{:user}}]
      (is (auth/valid-jwt-claims? claims))))
  
  (testing "invalid JWT claims fail validation"
    (let [invalid {:user-id "not-a-uuid"
                   :roles #{:user}}]
      (is (not (auth/valid-jwt-claims? invalid))))))
