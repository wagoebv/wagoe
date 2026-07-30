(ns wagoe.realtime.shell.adapters.jwt-adapter
  "JWT verification adapter that delegates to boundary/user module.
   
   Wraps wagoe.user.shell.auth/validate-jwt-token to provide IJWTVerifier
   protocol implementation. Avoids direct dependency from core layer to user module.
   
   Responsibilities (Shell/I/O):
   - Call user module for JWT verification (I/O - external dependency)
   - Transform user module response to expected format
   - Handle verification errors"
  (:require [wagoe.realtime.ports :as ports]
            [clojure.tools.logging :as log]))

;; Require user module optionally to avoid hard dependency
(try
  (require '[wagoe.user.shell.auth :as user-auth])
  (catch Exception _e
    ;; User module not available - UserJWTAdapter will throw at runtime if used
    nil))

;; =============================================================================
;; User Module JWT Adapter
;; =============================================================================

(defrecord UserJWTAdapter []
  ports/IJWTVerifier

  (verify-jwt [_this token]
    ;; Check if user module is available
    (if-not (resolve 'wagoe.user.shell.auth/validate-jwt-token)
      (throw (ex-info "User module not available - cannot verify JWT"
                      {:type :internal-error
                       :message "wagoe.user.shell.auth/validate-jwt-token not found"}))
      ;; Delegate to user module for JWT verification
      (let [user-auth-ns (the-ns 'wagoe.user.shell.auth)
            validate-fn (ns-resolve user-auth-ns 'validate-jwt-token)
            result (validate-fn token)]
        (if (:valid? result)
          ;; Transform claims to expected format
          (let [claims (:claims result)]
            {:user-id (java.util.UUID/fromString (:sub claims))
             :email (:email claims)
             :roles #{(keyword (:role claims))} ; Convert single role to set
             :permissions #{} ; User module doesn't track permissions yet
             :exp (:exp claims)
             :iat (:iat claims)})
          ;; JWT invalid - throw unauthorized exception
          (do
            (log/warn "JWT verification failed" {:error (:error result)})
            (throw (ex-info "Unauthorized: Invalid JWT token"
                            {:type :unauthorized
                             :message (:error result "Invalid or expired token")}))))))))

;; =============================================================================
;; Test Adapter (for testing without user module)
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

(defn create-user-jwt-adapter
  "Create JWT adapter that delegates to boundary/user module.
  
  Returns:
    UserJWTAdapter instance implementing IJWTVerifier"
  []
  (->UserJWTAdapter))

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
