# Troubleshooting

Read when directed by [AGENTS.md](../../AGENTS.md). Paths in commands and inline code are relative to the repository root.

## Common Pitfalls

**Most pitfalls listed below are automatically prevented by the scaffolder.** If you use `bb scaffold` to create modules, you will avoid pitfalls #1, #2, #4, #5, #6, and #9. Manual code creation is where these issues arise.

**LLM Reminder**: When adding functionality, reach for `bb scaffold` first. It's faster, safer, and ensures FC/IS compliance.

<!-- gen:pitfalls -->
### 1. snake_case vs kebab-case mixing

- **Symptom:** Using snake_case internally causes nil lookups.
- **Cause:** snake_case keys used inside Clojure code instead of converting only at boundaries.
- **Fix:** Always use kebab-case internally; convert at the persistence/HTTP boundary only via snake-case->kebab-case-map / kebab-case->snake-case-map.

### 2. defrecord changes not taking effect

- **Symptom:** (ig-repl/reset) doesn't recreate defrecord instances.
- **Cause:** ig-repl/reset reuses existing defrecord instances rather than recompiling them.
- **Fix:** Run (ig-repl/halt) then (ig-repl/go), or restart the REPL entirely.

```clojure
(ig-repl/halt)
(ig-repl/go)
;; OR restart REPL entirely
```

### 3. unbalanced parentheses

- **Symptom:** Manual editing creates syntax errors.
- **Cause:** Manually fixing delimiters by hand.
- **Fix:** ALWAYS use the tool, never manually fix: clj-paren-repair <file>.

### 4. validation in wrong layer

- **Symptom:** Validation side effects placed in pure core code.
- **Cause:** Calling valid?/throwing inside core functions instead of in the shell service.
- **Fix:** Validate in the shell; pass clean data into core functions.

```clojure
;; ❌ WRONG - Validation in core
(defn create-user [user-data]
  (when-not (valid? user-data)  ; Side effect in pure code!
    (throw ...)))

;; ✅ CORRECT - Validation in shell
(defn create-user-service [user-data]
  (let [[valid? errors data] (validate user-data)]
    (if valid?
      (user-core/create-user data)  ; Core gets clean data
      (throw ...))))
```

### 5. core depending on shell

- **Symptom:** Core namespace requires a shell namespace.
- **Cause:** Core requiring shell.persistence (or other shell IO) instead of depending on ports only.
- **Fix:** Core depends on ports only; keep core functions pure.

```clojure
(ns myapp.core.user
  ;; WRONG - Core requires shell namespace
  (:require [myapp.shell.persistence :as db]))  ; BAD!

;; CORRECT - Core depends on ports only
(ns myapp.core.user)
(defn find-user-decision [user-id existing-user] ...)  ; Pure logic
```

### 6. schema-database mismatch

- **Symptom:** 500 errors with cryptic messages, nil values for expected fields, SQL errors about missing columns.
- **Cause:** Adding logic that uses new fields without adding them to the Malli schema, the database columns, and the persistence transformations.
- **Fix:** When adding a field, synchronize all three: Malli schema, database column (migration), and persistence layer transformations.

```clojure
;; ALWAYS follow this checklist when adding new fields:

;; 1. Add to Malli schema (src/{module}/schema.clj)
[:failed-login-count {:optional true} :int]
[:lockout-until {:optional true} [:maybe inst?]]

;; 2. Add to persistence layer transformations (src/{module}/shell/persistence.clj)
;; In entity->db: nothing, the Instant goes to the database as it is
;; In db->entity:
(update :lockout-until type-conversion/string->instant)

;; 3. Verify database has columns (or add migration)
ALTER TABLE users ADD COLUMN failed_login_count INTEGER DEFAULT 0;
ALTER TABLE users ADD COLUMN lockout_until TIMESTAMP WITH TIME ZONE;
```

### 7. exception handling - missing :type in ex-data

- **Symptom:** Exceptions without :type in ex-data trigger generic 500 errors; log warning "Exception reached HTTP boundary without :type in ex-data".
- **Cause:** Throwing exceptions from Java methods or using ex-info without a :type key.
- **Fix:** Wrap external/Java calls in try-catch and ensure ALL ex-info calls include a :type (validation-error, not-found, unauthorized, forbidden, conflict, or internal-error).

```clojure
;; WRONG - No :type in ex-data
(parse-long "invalid")  ; Throws NumberFormatException (no ex-data)
(UUID/fromString "bad") ; Throws IllegalArgumentException (no ex-data)
(throw (ex-info "Error" {:field :foo}))  ; Missing :type

;; CORRECT - Wrap in try-catch with :type
(try
  (parse-long value)
  (catch NumberFormatException _
    (throw (ex-info "Invalid integer value"
                    {:type :validation-error
                     :field field-keyword
                     :value value
                     :message "Must be a valid integer"}))))
```

### 8. Java interop - static vs instance methods

- **Symptom:** IllegalArgumentException: No matching method <method-name> found taking N args for class java.lang.Class.
- **Cause:** Using instance method syntax (.method) for static methods or static syntax for instance methods.
- **Fix:** Use ClassName/method for static methods and (.method object) for instance methods; check Java docs to identify which is which.

```clojure
;; WRONG - Using instance syntax for static method
(.between java.time.Duration instant now)
;; Error: No matching method between found taking 2 args

;; WRONG - Using static syntax for instance method
(java.time.Instant/getSeconds my-instant)
;; Error: No matching field or method getSeconds

;; CORRECT - Static method using ClassName/method
(java.time.Duration/between instant now)

;; CORRECT - Instance method using .method
(.getSeconds duration)

;; Common Java Interop Patterns:
;; Static methods (ClassName/method)
(java.time.Instant/now)
(java.util.UUID/randomUUID)
(java.lang.Math/abs -5)
(java.time.Duration/between start end)

;; Instance methods (.method object)
(.toString my-object)
(.getSeconds duration)
(.format instant formatter)

;; Static fields (ClassName/FIELD)
java.time.temporal.ChronoUnit/DAYS

;; Instance fields (.field object)
(.length my-string)
```

### 9. module routes — the prefix is added for you

- **Symptom:** A route answers at /api/v1/api/my-resource instead of /api/v1/my-resource, or the module serves nothing at all and says nothing about it, or every route answers 401 to a caller who is not signed in.
- **Cause:** A module's :wagoe/<name>-routes component returns a contribution — {:api [..] :web [..] :static [..]} — and the platform prefixes each part before mounting it: :api is versioned under /api/v1, :web mounted under /web. Paths written with the prefix already on them get it twice. A flat vector returned instead of the three-key map contributes zero routes, and nothing errors. Every route requires a signed-in user unless its route data says :public true (BOU-568).
- **Fix:** Write Reitit route data at paths relative to the mount point, and return the three-key map. `bb scaffold generate` writes both; this is what to preserve when editing by hand. Add :public true, on the method or the path, only to a route something else authenticates: a login form, a signed webhook.

```clojure
;; WRONG — prefix written twice, and a flat vector instead of a contribution
(defn my-routes [svc _config]
  [["/api/my-resource"
    {:get {:handler (fn [req] ...)}}]])

;; CORRECT
(defn api-routes [svc]
  [["/my-resource"          ; no /api — versioning adds /api/v1
    {:get {:handler (fn [req] ...)
           :summary "..."}}]
   ["/my-webhook"            ; signed by the sender, so no session
    {:post {:handler (fn [req] ...)
            :public  true}}]])

(defn my-routes [svc config]
  {:api    (api-routes svc)
   :web    (web-routes svc config)   ; paths without /web
   :static []})
```

### 10. forward references — define before use

- **Symptom:** Unable to resolve symbol: my-helper in this context; Syntax error compiling.
- **Cause:** Private helpers placed after the public functions that call them; Clojure compiles top-to-bottom.
- **Fix:** Private helpers must always appear above the first function that calls them (in src/, dev/, and test/ alike).

```clojure
;; WRONG — system-component is called before it is defined
(defn dev-resend-invite! [...]
  (let [svc (system-component :wagoe/my-service)] ...))  ; used here

(defn- system-component [k]   ; defined here — too late!
  (get integrant.repl.state/system k))

;; CORRECT — helper defined first
(defn- system-component [k]
  (get integrant.repl.state/system k))

(defn dev-resend-invite! [...]
  (let [svc (system-component :wagoe/my-service)] ...))
```

### 11. OpenAPI — parameters invisible without explicit declaration

- **Symptom:** Routes with path or query parameters show no input fields in the API docs (/api-docs/).
- **Cause:** The OpenAPI 3.1 document (/openapi.json) only lists parameters a route declares: a Malli :parameters schema, or a raw :openapi spec. A handler that reads :path-params or :query-params itself declares neither.
- **Fix:** Add an :openapi key with OpenAPI 3.x parameter specs (type under :schema) to every handler/route that has path or query parameters. A 2.0-style :swagger key is still translated, with a warning at boot, until 2.0.

```clojure
;; Path parameter — on the method
:get {:handler ...
      :summary "Get product by slug"
      :openapi {:parameters [{:name "slug" :in "path" :required true :schema {:type "string"}
                              :description "Product slug (e.g. wagoe-tshirt)"}]}}

;; Query parameters — on the method
:get {:handler ...
      :summary "List customer orders"
      :openapi {:parameters [{:name "email"  :in "query" :required true  :schema {:type "string"}}
                             {:name "limit"  :in "query" :required false :schema {:type "integer"}}
                             {:name "offset" :in "query" :required false :schema {:type "integer"}}]}}

;; Shared path param when a route has multiple methods — put :openapi at route level
["/api/cart/items/:product-id"
 {:openapi {:parameters [{:name "product-id" :in "path" :required true :schema {:type "string"}}]}
  :patch  {:handler ... :summary "Update item quantity"}
  :delete {:handler ... :summary "Remove item from cart"}}]
```
<!-- /gen:pitfalls -->

---
