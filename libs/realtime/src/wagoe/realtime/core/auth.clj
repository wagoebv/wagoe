(ns wagoe.realtime.core.auth
  "Pure functions for WebSocket authentication logic.
  
  Following FC/IS pattern - all functions are pure (no I/O).
  JWT token extraction and validation logic.
  Actual JWT verification delegated to shell/ports."
  (:require [wagoe.realtime.schema :as schema]
            [malli.core :as m]
            [clojure.string :as str]))

;; Query String Parsing (Pure)

(defn parse-query-string
  "Parse query string into map.
  
  Pure function - deterministic for given input.
  
  Args:
    query-string - URL query string (e.g., 'token=abc&foo=bar')
  
  Returns:
    Map of query parameters {\"token\" \"abc\" \"foo\" \"bar\"}"
  [query-string]
  (when (and query-string (not= query-string ""))
    (into {}
          (for [pair (str/split query-string #"&")
                :let [[k v] (str/split pair #"=" 2)]
                :when (and k (not= k ""))]
            [k (or v "")]))))

(defn extract-token-from-query
  "Extract JWT token from query parameters.
  
  Pure function - no side effects.
  
  Args:
    query-params - Map of query parameters
  
  Returns:
    Token string or nil if not found"
  [query-params]
  {:pre [(or (nil? query-params) (map? query-params))]}
  (get query-params "token"))

(defn extract-token-from-query-string
  "Extract JWT token from query string.
  
  Pure function - combines parsing and extraction.
  
  Args:
    query-string - URL query string
  
  Returns:
    Token string or nil if not found"
  [query-string]
  (-> query-string
      parse-query-string
      extract-token-from-query))

;; JWT Claims Validation (Pure)

(def ^:private jwt-claims-validator (m/validator schema/JWTClaims))
(def ^:private jwt-claims-explainer (m/explainer schema/JWTClaims))

(defn valid-jwt-claims?
  "Validate JWT claims against schema.

  Pure function - no side effects.

  Args:
    claims - JWT claims map

  Returns:
    Boolean - true if valid"
  [claims]
  (jwt-claims-validator claims))

(defn explain-jwt-claims
  "Explain why JWT claims are invalid.

  Pure function - returns validation errors.

  Args:
    claims - JWT claims map

  Returns:
    Malli explanation or nil if valid"
  [claims]
  (jwt-claims-explainer claims))

;; Authorization Decisions (Pure)

(defn token-expired?
  "Check if JWT token is expired.
  
  Pure function - deterministic for given inputs.
  
  Args:
    jwt-claims - JWT claims map with :exp
    now-seconds - Current time in seconds since epoch
  
  Returns:
    Boolean - true if expired"
  [jwt-claims now-seconds]
  {:pre [(map? jwt-claims)
         (number? now-seconds)]}
  (if-let [exp (:exp jwt-claims)]
    (>= now-seconds exp)
    false)) ;; No expiry = not expired

(defn has-permission?
  "Check if JWT claims have required permission.
  
  Pure function - no side effects.
  
  Args:
    jwt-claims - JWT claims map with :permissions
    required-permission - Permission keyword required
  
  Returns:
    Boolean - true if permission exists"
  [jwt-claims required-permission]
  {:pre [(map? jwt-claims)
         (keyword? required-permission)]}
  (contains? (set (:permissions jwt-claims)) required-permission))

(defn has-any-permission?
  "Check if JWT claims have any of required permissions.
  
  Pure function - no side effects.
  
  Args:
    jwt-claims - JWT claims map with :permissions
    required-permissions - Set of permission keywords
  
  Returns:
    Boolean - true if has at least one permission"
  [jwt-claims required-permissions]
  {:pre [(map? jwt-claims)
         (set? required-permissions)]}
  (let [user-perms (set (:permissions jwt-claims))]
    (some user-perms required-permissions)))

(defn has-all-permissions?
  "Check if JWT claims have all required permissions.
  
  Pure function - no side effects.
  
  Args:
    jwt-claims - JWT claims map with :permissions
    required-permissions - Set of permission keywords
  
  Returns:
    Boolean - true if has all permissions"
  [jwt-claims required-permissions]
  {:pre [(map? jwt-claims)
         (set? required-permissions)]}
  (let [user-perms (set (:permissions jwt-claims))]
    (every? user-perms required-permissions)))

(defn has-role?
  "Check if JWT claims have required role.
  
  Pure function - no side effects.
  
  Args:
    jwt-claims - JWT claims map with :roles
    required-role - Role keyword required
  
  Returns:
    Boolean - true if role exists"
  [jwt-claims required-role]
  {:pre [(map? jwt-claims)
         (keyword? required-role)]}
  (contains? (set (:roles jwt-claims)) required-role))

(defn has-any-role?
  "Check if JWT claims have any of required roles.
  
  Pure function - no side effects.
  
  Args:
    jwt-claims - JWT claims map with :roles
    required-roles - Set of role keywords
  
  Returns:
    Boolean - true if has at least one role"
  [jwt-claims required-roles]
  {:pre [(map? jwt-claims)
         (set? required-roles)]}
  (let [user-roles (set (:roles jwt-claims))]
    (some user-roles required-roles)))

;; Connection Authorization (Pure)

(defn connection-authorized?
  "Determine if connection should be authorized based on JWT claims.
  
  Pure function - encapsulates authorization logic.
  
  Args:
    jwt-claims - JWT claims map
    config - Authorization config map with:
             :required-permissions (optional) - Set of required permissions
             :required-roles (optional) - Set of required roles
             :allow-expired (optional) - Boolean, default false
    now-seconds - Current time in seconds since epoch
  
  Returns:
    {:authorized? boolean
     :reason string (if not authorized)}"
  [jwt-claims config now-seconds]
  {:pre [(map? jwt-claims)
         (map? config)
         (number? now-seconds)]}
  (cond
    ;; Check expiry (unless explicitly allowed)
    (and (not (:allow-expired config))
         (token-expired? jwt-claims now-seconds))
    {:authorized? false
     :reason "Token expired"}

    ;; Check required permissions (if specified)
    (and (:required-permissions config)
         (not (has-all-permissions? jwt-claims (:required-permissions config))))
    {:authorized? false
     :reason "Missing required permissions"}

    ;; Check required roles (if specified)
    (and (:required-roles config)
         (not (has-any-role? jwt-claims (:required-roles config))))
    {:authorized? false
     :reason "Missing required role"}

    ;; All checks passed
    :else
    {:authorized? true}))

;; Token Extraction Helpers (Pure)

(defn extract-token-from-request
  "Extract JWT token from request map.
  
  Pure function - tries multiple common locations.
  
  Args:
    request - Request map (Ring-style) with:
              :query-string - Query string
              :query-params - Parsed query params map
              :headers - Headers map
  
  Returns:
    Token string or nil if not found"
  [request]
  {:pre [(map? request)]}
  (or
   ;; Try parsed query params first
   (extract-token-from-query (:query-params request))

   ;; Fall back to parsing query string
   (extract-token-from-query-string (:query-string request))

   ;; Try Authorization header (Bearer token)
   (when-let [auth-header (get-in request [:headers "authorization"])]
     (when (str/starts-with? auth-header "Bearer ")
       (subs auth-header 7)))))

;; Error Helpers (Pure)

(defn auth-error
  "Create authentication error result.
  
  Pure function - standardized error format.
  
  Args:
    reason - Error reason string
    details - Optional details map
  
  Returns:
    Error map"
  ([reason]
   (auth-error reason {}))
  ([reason details]
   {:error :unauthorized
    :reason reason
    :details details}))
