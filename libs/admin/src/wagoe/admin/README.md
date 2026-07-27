# Boundary Admin Module

Auto-generated CRUD admin interface for the Boundary framework - a "Django Admin killer" that creates database administration UIs with zero configuration.

## Features

- ✅ **Auto-generated CRUD** - Automatically creates admin UI from database schema
- ✅ **Zero Configuration** - Works out-of-the-box, customize only what you need
- ✅ **Database Agnostic** - Works with PostgreSQL, MySQL, SQLite, and H2
- ✅ **Role-Based Access Control** - Secure admin-only access
- ✅ **Search & Filter** - Full-text search across configured fields
- ✅ **Pagination & Sorting** - Handle large datasets efficiently
- ✅ **Soft/Hard Delete** - Auto-detects from schema
- ✅ **HTMX-Powered** - Dynamic UI updates without JavaScript build
- ✅ **Mobile Responsive** - Works on all devices

## Quick Start

### 1. Enable the Admin Module

Add configuration to `resources/conf/dev/config.edn`:

```clojure
:active
{:boundary/admin
 {:enabled? true
  :base-path "/web/admin"
  :require-role :admin
  :entity-discovery {:mode :allowlist
                     :allowlist #{:users :products}}
  :pagination {:default-page-size 50
               :max-page-size 200}}}
```

### 2. Start the Application

```bash
clojure -M:repl-clj
```

```clojure
(require '[integrant.repl :as ig-repl])
(ig-repl/go)
```

### 3. Access the Admin Interface

Navigate to: **http://localhost:3000/web/admin**

Login with an admin user and start managing your entities!

## Configuration

### Entity Discovery Modes

**Allowlist (Recommended for Production)**
```clojure
:entity-discovery {:mode :allowlist
                   :allowlist #{:users :products :orders}}
```

Only entities in the allowlist are accessible. Safe and explicit.

**Denylist (Week 2+)**
```clojure
:entity-discovery {:mode :denylist
                   :denylist #{:internal-logs :system-config}}
```

All tables except those in denylist. Use with caution.

**All (Week 2+)**
```clojure
:entity-discovery {:mode :all}
```

Expose all database tables. **Not recommended for production.**

### Entity Customization

Override auto-detected defaults with manual configuration:

```clojure
:entities
{:users
 {:label "System Users"                    ; Display name
  :list-fields [:email :name :role :active :created-at]
  :search-fields [:email :name]            ; Full-text search
  :hide-fields #{:password-hash :deleted-at}
  :readonly-fields #{:id :created-at :updated-at}
  :sort-field :created-at                  ; Default sort
  :sort-dir :desc}}                        ; :asc or :desc
```

### Pagination Settings

```clojure
:pagination
{:default-page-size 50    ; Default items per page
 :max-page-size 200}      ; Maximum allowed
```

## Architecture

### FC/IS (Functional Core / Imperative Shell)

The admin module follows strict architectural layering:

```
src/boundary/admin/
├── core/                  # FUNCTIONAL CORE (Pure Functions)
│   ├── schema_introspection.clj   # DB metadata → UI config
│   ├── permissions.clj             # RBAC logic
│   └── ui.clj                      # Hiccup UI components
├── shell/                 # IMPERATIVE SHELL (I/O)
│   ├── schema_repository.clj       # ISchemaProvider (DB access)
│   ├── service.clj                 # IAdminService (CRUD ops)
│   ├── http.clj                    # HTTP routes/handlers
│   └── module_wiring.clj           # Integrant lifecycle
├── ports.clj              # Protocol definitions
└── schema.clj             # Malli validation schemas
```

### Key Design Principles

1. **Protocol-Based** - Dependency inversion via protocols
2. **Pure Business Logic** - Core functions have no side effects
3. **Testable** - Unit tests for core, integration tests for shell
4. **Database Agnostic** - Adapter pattern for multi-DB support
5. **Observable** - All operations logged with metrics

## Usage Examples

### Basic CRUD Operations

The admin interface auto-generates:

- **List View** - Paginated table with search and sort
- **Create Form** - Field widgets based on data types
- **Edit Form** - Pre-populated with entity data
- **Delete Action** - Soft or hard delete based on schema

### Search and Filter

**Full-Text Search**
```
Search: john@example.com
→ Searches across :email and :name fields
```

**Column Sorting**
```
Click column header to sort ascending/descending
```

**Pagination**
```
Navigate: ← Previous | Page 1 of 5 | Next →
```

### Field Widgets (Auto-Detected)

| Database Type | Widget | Example |
|---------------|--------|---------|
| `VARCHAR` with "email" | Email input | `<input type="email">` |
| `VARCHAR` with "password" | Password input | `<input type="password">` |
| `VARCHAR` with "url" | URL input | `<input type="url">` |
| `TEXT` | Textarea | `<textarea>` |
| `BOOLEAN` | Checkbox | `<input type="checkbox">` |
| `INTEGER`, `DECIMAL` | Number input | `<input type="number">` |
| `TIMESTAMP` | Datetime input | `<input type="datetime-local">` |
| `DATE` | Date input | `<input type="date">` |
| `UUID` | Text input (readonly) | `<input readonly>` |

### Permissions (Week 1)

Week 1 implements simple role-based access control:

```clojure
;; Only users with :admin role can access
(= :admin (:role user))  ;=> true (access granted)
(= :user (:role user))   ;=> false (403 Forbidden)
```

Week 2+ will add:
- Entity-level permissions
- Action-level permissions (view vs edit)
- Field-level permissions (hide salary from managers)

## Advanced Features

### Soft Delete Detection

Admin automatically detects soft delete from database schema:

```sql
-- Table with deleted_at column = Soft Delete
CREATE TABLE users (
  id UUID PRIMARY KEY,
  email VARCHAR NOT NULL,
  deleted_at TIMESTAMP  -- Admin will soft-delete
);

-- Table without deleted_at = Hard Delete
CREATE TABLE logs (
  id UUID PRIMARY KEY,
  message TEXT
);  -- Admin will hard-delete (permanent)
```

### Hidden Fields

Fields are automatically hidden based on naming patterns:

- `*_hash` (e.g., `password_hash`)
- `*_token` (e.g., `reset_token`)
- `deleted_at`

Override with manual configuration:

```clojure
:hide-fields #{:password-hash :secret-key :internal-notes}
```

### Readonly Fields

Auto-detected readonly fields:

- Primary keys (`id`)
- Audit timestamps (`created_at`, `updated_at`)
- Fields with database defaults

Override:

```clojure
:readonly-fields #{:id :email :created-at :updated-at}
```

## Integration with Existing Modules

### User Module Integration

Admin module leverages existing authentication:

```clojure
;; From user module
:boundary/admin-routes
{:user-service (ig/ref :boundary/user-service)  ; For auth
 :admin-service (ig/ref :boundary/admin-service)
 :schema-provider (ig/ref :boundary/admin-schema-provider)}
```

Session-based or token-based auth both supported.

### Database Integration

Works with existing database infrastructure:

```clojure
;; Reuses platform database context
:boundary/admin-service
{:db-ctx (ig/ref :boundary/database-context)  ; Same DB connection
 :schema-provider (ig/ref :boundary/admin-schema-provider)
 :logger (ig/ref :boundary/logging)
 :error-reporter (ig/ref :boundary/error-reporting)}
```

## Testing

### Run All Admin Tests

```bash
# Unit tests (pure functions)
clojure -M:test:db/h2 --focus-meta :unit --focus-meta :admin

# Integration tests (with H2 database)
clojure -M:test:db/h2 --focus-meta :integration --focus-meta :admin

# Contract tests (HTTP endpoints)
clojure -M:test:db/h2 --focus-meta :contract --focus-meta :admin

# All admin tests
clojure -M:test:db/h2 --focus boundary.admin
```

### Test Coverage

- **Unit Tests**: Core layer (schema introspection, permissions, UI)
- **Integration Tests**: Service layer with real database
- **Contract Tests**: HTTP endpoints with full request/response cycle

Coverage Target: >80% for core, >70% for shell

## Roadmap

### Week 1 (MVP) ✅
- [x] Auto-generate CRUD UI from schema
- [x] Simple RBAC (admin-only access)
- [x] Pagination, sorting, search
- [x] Soft/hard delete detection
- [x] Mobile-responsive HTMX UI

### Week 2 (Enhancements)
- [ ] Advanced permissions (entity/field-level)
- [ ] Relationship management (foreign keys)
- [ ] Bulk actions (export CSV, bulk edit)
- [ ] Custom actions per entity
- [ ] Audit logging (who changed what, when)
- [ ] Entity discovery: denylist and all modes

### Week 3 (Polish)
- [ ] File upload widgets
- [ ] Rich text editor for TEXT fields
- [ ] Inline editing (click to edit)
- [ ] Advanced filters (date ranges, multi-select)
- [ ] Saved filter sets

### Week 4 (Advanced)
- [ ] Dashboard with entity stats
- [ ] Charts and visualizations
- [ ] Custom entity pages
- [ ] API endpoints (JSON responses)
- [ ] Webhooks on entity changes

## Troubleshooting

### Admin interface returns 403 Forbidden

**Cause**: User doesn't have `:admin` role

**Solution**: Ensure logged-in user has `:role :admin`

```clojure
;; Check user role
(:role (:user request))  ;=> :admin (required)
```

### Entity not appearing in sidebar

**Cause**: Entity not in allowlist

**Solution**: Add entity to `:allowlist` in config

```clojure
:entity-discovery {:mode :allowlist
                   :allowlist #{:users :products :your-entity}}
```

### Fields not displaying correctly

**Cause**: Database column type not recognized

**Solution**: Add manual field configuration

```clojure
:entities
{:your-entity
 {:fields {:special-field {:type :string
                           :widget :text-input}}}}
```

### Table metadata fetch fails

**Cause**: Database adapter doesn't support table introspection

**Solution**: Check database adapter supports `get-table-info`

```clojure
;; Supported: PostgreSQL, MySQL, SQLite, H2
;; Not supported: Custom adapters (Week 2+ will have manual schema)
```

## Performance Considerations

### Large Datasets

- **Pagination**: Always enabled (default 50 per page)
- **Indexing**: Ensure search fields are indexed in database
- **Limits**: Max 200 items per page (configurable)

### Query Optimization

```sql
-- Add indexes for frequently searched fields
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_name ON users(name);

-- For sort columns
CREATE INDEX idx_users_created_at ON users(created_at DESC);
```

### Caching (Week 2+)

Schema metadata is fetched on every request in Week 1.

Week 2+ will add TTL-based caching:

```clojure
:schema-cache {:ttl 300}  ; 5 minutes
```

## Security Best Practices

1. **Always use allowlist mode in production**
   ```clojure
   :entity-discovery {:mode :allowlist :allowlist #{:users}}
   ```

2. **Hide sensitive fields**
   ```clojure
   :hide-fields #{:password-hash :api-key :ssn}
   ```

3. **Mark system fields readonly**
   ```clojure
   :readonly-fields #{:id :created-at :stripe-customer-id}
   ```

4. **Use HTTPS in production**
   - Admin interface transmits sensitive data
   - Always serve over HTTPS

5. **Implement rate limiting** (Week 2+)
   - Prevent brute force on admin login
   - Limit bulk operations

## Contributing

The admin module follows Boundary's FC/IS architecture:

1. **Pure functions** go in `core/`
2. **Side effects** go in `shell/`
3. **Protocols** define interfaces in `ports.clj`
4. **Schemas** validate in `schema.clj`

See `AGENTS.md` in project root for development guidelines.

## License

Same as Boundary framework

## Support

- **Issues**: https://github.com/thijs-creemers/boundary/issues
- **Discussions**: https://github.com/thijs-creemers/boundary/discussions
- **Docs**: See [docs-site/](../../../../docs-site/) in the repository
