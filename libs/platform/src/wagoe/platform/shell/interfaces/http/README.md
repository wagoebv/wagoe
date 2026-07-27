# HTTP Interface Module

The `boundary.platform.shell.interfaces.http` module provides a comprehensive HTTP interface infrastructure for the Boundary application. It implements a clean, modular architecture following clean architecture principles and provides reusable components for building REST APIs.

## Overview

This module contains the HTTP layer of the application, providing:

- **Common routing infrastructure** with standardized endpoints
- **Reusable middleware** for cross-cutting concerns
- **RFC 7807 Problem Details** for standardized error responses
- **Health check endpoints** for monitoring and observability
- **OpenAPI/Swagger documentation** with interactive UI
- **Module composition system** for building scalable applications

## Architecture

The module follows a layered architecture with clear separation of concerns:

```
boundary.platform.shell.interfaces.http/
├── routes.clj      # Common routing infrastructure & route composition
├── middleware.clj  # Reusable HTTP middleware 
├── common.clj      # Common utilities & RFC 7807 error handling
└── server.clj      # HTTP server configuration (if needed)
```

## Core Components

### 1. Routes (`routes.clj`)

The routes namespace provides the foundational routing infrastructure:

#### Standard Endpoints

- **Health Checks**: `/health`, `/health/ready`, `/health/live`
- **API Documentation**: `/swagger.json`, `/api-docs/*`

#### Module Composition

```clojure
(require '[boundary.platform.shell.interfaces.http.routes :as routes])

;; Create a router with common + module routes
(def router 
  (routes/create-router config module-routes
                        :additional-health-checks health-fn
                        :error-mappings error-map))

;; Create a complete Ring handler
(def handler 
  (routes/create-handler router))

;; One-step application creation
(def app 
  (routes/create-app config module-routes
                     :additional-health-checks health-fn
                     :error-mappings error-map))
```

### 2. Middleware (`middleware.clj`)

Provides reusable middleware for common HTTP concerns:

#### Available Middleware

- **`wrap-correlation-id`**: Adds unique correlation IDs for request tracing
- **`wrap-request-logging`**: Structured request/response logging
- **`wrap-exception-handling`**: Converts exceptions to RFC 7807 Problem Details

#### Usage

```clojure
(require '[boundary.platform.shell.interfaces.http.middleware :as middleware])

;; Apply individual middleware
(-> handler
    (middleware/wrap-correlation-id)
    (middleware/wrap-request-logging)
    (middleware/wrap-exception-handling error-mappings))

;; Or use the common middleware stack (applied automatically in routes)
(middleware/wrap-common-middleware handler error-mappings)
```

### 3. Common Utilities (`common.clj`)

Provides shared HTTP utilities and standardized error handling:

#### RFC 7807 Problem Details

```clojure
(require '[boundary.platform.shell.interfaces.http.common :as http-common])

;; Convert exceptions to standardized error responses
(http-common/exception->problem 
  (ex-info "User not found" {:type :user-not-found})
  correlation-id
  request-uri)
;; => {:status 404
;;     :body {:type "https://boundary.example.com/problems/user-not-found"
;;            :title "User Not Found"
;;            :status 404
;;            :detail "User not found"
;;            :instance "/api/users/123"
;;            :correlation-id "abc-123"}}
```

#### Health Check Handlers

```clojure
;; Create health check handlers
(def health-handler 
  (http-common/health-check-handler "my-service" "1.0.0" additional-checks))

;; Create 404 handlers
(def not-found-handler 
  (http-common/create-not-found-handler))
```

## Usage Examples

### Basic Module Integration

```clojure
(ns my-module.http
  (:require [boundary.platform.shell.interfaces.http.routes :as routes]))

(defn my-routes [service]
  [["/items" {:get {:handler (list-items-handler service)}}]
   ["/items/:id" {:get {:handler (get-item-handler service)}}]])

(defn my-health-checks [service]
  {:database (fn [] {:status :healthy :details "DB connected"})})

(defn create-app [service config]
  (routes/create-app config 
                     (my-routes service)
                     :additional-health-checks (my-health-checks service)
                     :error-mappings {:item-not-found [404 "Item Not Found"]}))
```

### Advanced Router Configuration

```clojure
(ns my-app.core
  (:require [boundary.platform.shell.interfaces.http.routes :as routes]
            [my-module.http :as my-http]
            [another-module.http :as another-http]))

;; Combine multiple modules
(defn create-application [services config]
  (let [all-routes (concat (my-http/my-routes (:my-service services))
                          (another-http/routes (:another-service services)))
        combined-health-checks (merge (my-http/my-health-checks (:my-service services))
                                     (another-http/health-checks (:another-service services)))
        combined-error-mappings (merge my-http/error-mappings
                                      another-http/error-mappings)]
    (routes/create-app config
                       all-routes
                       :additional-health-checks combined-health-checks
                       :error-mappings combined-error-mappings)))
```

## Standard Endpoints

### Health Checks

The module automatically provides health check endpoints:

#### `/health`
Complete health status with service information and optional additional checks.

**Response:**
```json
{
  "status": "ok",
  "service": "boundary-dev",
  "version": "0.1.0",
  "timestamp": "2024-10-24T10:30:00Z",
  "database": {
    "status": "healthy",
    "details": "Connection pool: 5/10 active"
  }
}
```

#### `/health/ready`
Readiness probe for load balancers and orchestration systems.

**Response:**
```json
{
  "status": "ready"
}
```

#### `/health/live`
Liveness probe for container orchestration.

**Response:**
```json
{
  "status": "alive"
}
```

### API Documentation

#### `/swagger.json`
OpenAPI 3.0 specification for the API.

#### `/api-docs/`
Interactive Swagger UI for exploring and testing the API.

Features:
- Interactive API exploration
- Request/response examples
- Try-it-out functionality
- Schema validation

## Configuration

### Application Configuration

The module uses the standard application configuration structure:

```clojure
{:active 
 {:boundary/settings 
  {:name "my-service"
   :version "1.0.0"}}}
```

### Error Mappings

Define custom error type mappings for your module:

```clojure
(def error-mappings
  {:user-not-found [404 "User Not Found"]
   :invalid-email [400 "Invalid Email Address"]
   :user-exists [409 "User Already Exists"]})
```

### Health Checks

Define additional health checks for your module:

```clojure
(defn health-checks [service]
  {:database (fn [] 
               (if (database-healthy? service)
                 {:status :healthy :details "Connected"}
                 {:status :unhealthy :details "Connection failed"}))
   :cache (fn []
            {:status :healthy :details "Redis connected"})})
```

## Middleware Stack

The default middleware stack (applied automatically) includes:

1. **Correlation ID** - Request tracing
2. **Request Logging** - Structured logging
3. **Parameter Parsing** - Query/form parameters
4. **Content Negotiation** - JSON/EDN/etc.
5. **Schema Coercion** - Request/response validation
6. **Exception Handling** - RFC 7807 error responses

## Best Practices

### 1. Module Structure

- Define routes in a dedicated function
- Separate health checks into their own function
- Use consistent error mappings across your module
- Follow REST conventions for endpoint design

### 2. Error Handling

- Use meaningful error types (keywords)
- Provide detailed error messages
- Map business exceptions to appropriate HTTP status codes
- Include correlation IDs in error responses

### 3. Health Checks

- Check critical dependencies (database, external APIs)
- Keep health checks lightweight and fast
- Return meaningful status information
- Use health checks for monitoring and alerting

### 4. Documentation

- Add comprehensive route documentation
- Use descriptive summaries and tags
- Include request/response examples
- Document error responses

## Integration with Other Modules

### User Module Example

The user module demonstrates proper integration:

```clojure
(ns boundary.user.shell.http
  (:require [boundary.platform.shell.interfaces.http.routes :as routes]))

(defn user-routes [user-service]
  [["/users" {:post {:handler (create-user-handler user-service)
                     :summary "Create user"
                     :tags ["users"]}}]
   ["/users/:id" {:get {:handler (get-user-handler user-service)
                        :summary "Get user by ID"
                        :tags ["users"]}}]])

(defn create-app [user-service config]
  (routes/create-app config
                     (user-routes user-service)
                     :additional-health-checks (user-health-checks user-service)
                     :error-mappings user-error-mappings))
```

## Testing

### Unit Testing Routes

```clojure
(ns my-module.http-test
  (:require [clojure.test :refer :all]
            [ring.mock.request :as mock]
            [my-module.http :as http]))

(deftest test-health-endpoint
  (let [app (http/create-app test-service test-config)
        response (app (mock/request :get "/health"))]
    (is (= 200 (:status response)))
    (is (= "ok" (get-in response [:body :status])))))
```

### Integration Testing

```clojure
(deftest test-full-api
  (with-system [system test-system]
    (let [app (create-app (:services system) (:config system))]
      (testing "health checks work"
        (is (= 200 (:status (app (mock/request :get "/health"))))))
      (testing "swagger docs available"
        (is (= 200 (:status (app (mock/request :get "/swagger.json")))))))))
```

## Contributing

When adding new functionality to this module:

1. Follow the existing architectural patterns
2. Add comprehensive documentation
3. Include unit tests
4. Update this README if adding new features
5. Follow RFC standards for HTTP behavior
6. Use meaningful error types and messages

## Dependencies

This module relies on:

- **Reitit** - Routing and OpenAPI support
- **Ring** - HTTP abstraction
- **Muuntaja** - Content negotiation
- **Malli** - Schema validation
- **Integrant** - System lifecycle management

## Version History

- **0.1.0** - Initial implementation with basic routing infrastructure
- **0.2.0** - Added comprehensive middleware stack
- **0.3.0** - Added RFC 7807 Problem Details support
- **0.4.0** - Added module composition system and enhanced documentation
