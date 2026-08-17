(ns wagoe.realtime.shell.adapters.jwt-adapter
  "Test implementation of IJWTVerifier.

   A real verifier is supplied by the application, which knows what issues its
   tokens. See `wagoe.realtime.ports/IJWTVerifier`."
  (:require [wagoe.realtime.ports :as ports]))

;; =============================================================================
;; Test Adapter
;; =============================================================================

(defrecord TestJWTAdapter [test-claims]
  ;; test-claims is an atom of claims map
  ports/IJWTVerifier

  (verify-jwt [_this token]
    ;; Return test claims if token matches, otherwise throw
    (if (and @test-claims (= token (:expected-token @test-claims)))
      (dissoc @test-claims :expected-token)
      (throw (ex-info "Unauthorized: Invalid test token"
                      {:type :unauthorized
                       :message "Test token does not match expected"})))))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn create-test-jwt-adapter
  "Create test JWT adapter for testing without user module.
  
  Args:
    test-claims - Map with claims to return for :expected-token
                  Must include :expected-token key
  
  Example:
    (create-test-jwt-adapter
      {:expected-token \"test-token-123\"
       :user-id #uuid \"...\"
       :email \"test@example.com\"
       :roles #{:user}})
  
  Returns:
    TestJWTAdapter instance"
  [test-claims]
  (->TestJWTAdapter (atom test-claims)))
