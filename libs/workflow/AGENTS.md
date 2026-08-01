# Workflow Library - Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

Declarative state machine workflows for domain entities. Provides permission-based transitions, automatic audit trails, and optional side-effect dispatch via `wagoe-jobs`.

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `wagoe.workflow.schema` | Malli schemas: WorkflowDefinition, WorkflowInstance, AuditEntry |
| `wagoe.workflow.ports` | Protocols: IWorkflowStore, IWorkflowEngine, IWorkflowRegistry |
| `wagoe.workflow.core.machine` | Pure state-machine introspection (`states`, `initial-state`, `transitions`) |
| `wagoe.workflow.core.transitions` | Pure transition logic; `available-transitions-with-status` returns enabled/disabled candidates |
| `wagoe.workflow.core.audit` | Pure audit entry constructors |
| `wagoe.workflow.shell.registry` | `defworkflow` macro, in-process definition registry, `IWorkflowRegistry` adapter |
| `wagoe.workflow.shell.service` | Orchestration: load → validate → persist → side-effects |
| `wagoe.workflow.shell.persistence` | DB persistence (IWorkflowStore via next.jdbc + HoneySQL) |
| `wagoe.workflow.shell.module-wiring` | Integrant `:wagoe/workflow` init/halt |
| `wagoe.workflow.shell.http` | REST API routes (start, transition, state, audit log) |

## Defining a Workflow

```clojure
(require '[wagoe.workflow.shell.registry :refer [defworkflow]])

(defworkflow order-workflow
  {:id             :order-workflow
   :initial-state  :pending
   :description    "E-commerce order lifecycle"
   :states         #{:pending :paid :shipped :delivered :cancelled}
   :state-config   {:pending   {:label "Awaiting Payment"}
                    :paid      {:label "Payment Received"}
                    :shipped   {:label "In Transit"}
                    :delivered {:label "Delivered"}
                    :cancelled {:label "Cancelled"}}
   :hooks          {:on-enter-paid     (fn [instance] (notify-finance! instance))
                    :on-any-transition  (fn [instance] (sync-external! instance))}
   :transitions    [{:from :pending :to :paid
                     :label                "Mark as Paid"
                     :required-permissions [:finance :admin]}
                    {:from :paid    :to :shipped
                     :guard         :payment-confirmed}
                    {:from :shipped :to :delivered
                     :name          :deliver
                     :label         "Confirm Delivery"}
                    {:from :pending :to :cancelled
                     :auto?         true
                     :side-effects  [:notify-cancellation]}
                    {:from :paid    :to :cancelled}]})
```

`defworkflow` binds the var and registers the definition in the in-process registry.

## Transition Fields

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `:from` | keyword | yes | Source state |
| `:to` | keyword | yes | Target state |
| `:name` | keyword | no | Transition name (defaults to `:to`) |
| `:label` | string | no | Human-readable display label |
| `:required-permissions` | `[keyword]` | no | Actor needs at least one |
| `:guard` | keyword | no | Key in guard-registry map |
| `:side-effects` | `[keyword]` | no | Job types enqueued after success |
| `:auto?` | boolean | no | If `true`, eligible for `process-auto-transitions!` |

## Usage Patterns

```clojure
(require '[wagoe.workflow.ports :as ports])

;; Start a workflow instance
(def instance
  (ports/start-workflow! engine
                         {:workflow-id :order-workflow
                          :entity-type :order
                          :entity-id   order-uuid}))

;; Execute a transition
(def result
  (ports/transition! engine
                     {:instance-id (:id instance)
                      :transition  :paid
                      :actor-roles [:admin]
                      :context     {:payment-method "stripe"}}))

(:success? result)    ;; => true
(:current-state (:instance result)) ;; => :paid

;; Read current state
(ports/current-state engine (:id instance)) ;; => :paid

;; Available transitions (with enabled/disabled status and labels)
(ports/available-transitions engine (:id instance) {:actor-roles [:admin]})
;; => [{:id :paid :to :paid :label "Mark as Paid" :enabled? true}
;;     {:id :cancelled :to :cancelled :enabled? true}]

;; Audit log
(ports/audit-log engine (:id instance))
;; => [{:from-state :pending :to-state :paid :transition :paid ...}]
```

## Lifecycle Hooks

Hooks fire synchronously after every successful transition (after the audit entry is
saved). Exceptions are caught and logged — they never abort the transition.

Register hooks under the `:hooks` key in your `defworkflow` definition:

```clojure
:hooks {:on-enter-paid      (fn [instance] ...)  ; entering :paid
        :on-exit-pending    (fn [instance] ...)  ; leaving :pending
        :on-any-transition  (fn [instance] ...)} ; every transition
```

Supported hook keys:
- `:on-enter-<state>` — fires when transitioning INTO the named state
- `:on-exit-<state>` — fires when transitioning OUT OF the named state
- `:on-any-transition` — fires on every successful transition

Each hook receives the updated `WorkflowInstance` map.

---

## Guards

Guards are plain functions registered at service creation time:

```clojure
(def guard-registry
  {:payment-confirmed (fn [ctx] (= :confirmed (:payment-status ctx)))})

(service/create-workflow-service store registry nil guard-registry)
```

Guards receive the `:context` map from the transition request and return `true` (allow) or `false` (reject).

## Auto-Transitions

Transitions declared with `:auto? true` are candidate for system-initiated firing:

```clojure
;; Process all eligible auto-transitions for a given workflow
(ports/process-auto-transitions! engine {:workflow-id :order-workflow
                                         :limit        100})
;; => {:attempted 5 :processed 3 :failed 0}
```

Auto-transitions use `[:system]` as the actor-roles vector, bypassing
permission checks. Design them for always-valid, system-initiated events.
Intended to be called from a scheduled job or `wagoe-jobs` trigger.

---

## Side Effects

Side effects are job-type keywords declared on a transition. When a `job-queue` is provided, `wagoe-jobs` is used to enqueue a job for each key after a successful transition:

```clojure
;; Transition declares: :side-effects [:notify-cancellation]
;; After the transition, a job with :job-type :notify-cancellation is enqueued.
;; Register a handler in your app:
(ports/register-handler! job-registry :notify-cancellation
                         (fn [args] ...))
```

The `job-queue` dependency is optional. If nil, side effects are silently skipped.

## Integrant Wiring

```edn
;; resources/conf/dev/config.edn
{:wagoe/workflow
 {:db-ctx    #ig/ref :wagoe/database-context
  :job-queue #ig/ref :wagoe/job-queue}}  ; optional
```

The component map returned is:
```clojure
{:store    <IWorkflowStore>
 :registry <IWorkflowRegistry>
 :engine   <IWorkflowEngine>}
```

## HTTP API

Routes are defined in `shell/http.clj` using the normalized map format (no `/api` prefix) and mounted by the platform versioning middleware under `/api/v1`.

| Method | Mounted path | Description |
|--------|-------------|-------------|
| `POST` | `/api/v1/workflow/instances` | Start a new workflow instance |
| `GET` | `/api/v1/workflow/instances/:id` | Current state + `availableTransitions` (id, label, enabled?) |
| `GET` | `/api/v1/workflow/instances/:id/audit` | Full audit log |
| `POST` | `/api/v1/workflow/instances/:id/transition` | Execute a transition |

## Database Migrations

Two tables are required:

```sql
CREATE TABLE workflow_instances (
  id             TEXT PRIMARY KEY,
  workflow_id    TEXT NOT NULL,
  entity_type    TEXT NOT NULL,
  entity_id      TEXT NOT NULL,
  current_state  TEXT NOT NULL,
  created_at     TEXT NOT NULL,
  updated_at     TEXT NOT NULL,
  metadata       TEXT
);

CREATE TABLE workflow_audit (
  id           TEXT PRIMARY KEY,
  instance_id  TEXT NOT NULL REFERENCES workflow_instances(id),
  workflow_id  TEXT NOT NULL,
  entity_type  TEXT NOT NULL,
  entity_id    TEXT NOT NULL,
  transition   TEXT NOT NULL,
  from_state   TEXT NOT NULL,
  to_state     TEXT NOT NULL,
  actor_id     TEXT,
  actor_roles  TEXT,
  context      TEXT,
  occurred_at  TEXT NOT NULL
);
```

## Gotchas

1. **`defonce` has no docstring** — do not add a docstring to `defonce` in Clojure (only takes 2 args).
2. **Registry lives in the shell** — the definition registry (`defworkflow`, `register-workflow!`, `get-workflow`, `list-workflows`, `unregister-workflow!`, `clear-registry!`, `create-workflow-registry`) is in `wagoe.workflow.shell.registry`; `wagoe.workflow.core.machine` is pure (state-machine introspection only). `clear-registry!` is provided for tests; always call it in fixtures.
3. **Side effects are best-effort** — a failure to enqueue is logged as a warning but does not roll back the transition.
4. **`find-instance` is on `IWorkflowStore`, not `IWorkflowEngine`** — use `*store*` in tests, not `*service*`.
5. **snake_case only at DB boundary** — all internal maps use kebab-case; `instance->db`/`db->instance` handle conversion.
6. **Hooks are best-effort** — exceptions inside hook functions are caught and logged; they do NOT roll back the transition.
7. **Auto-transitions bypass permissions** — they fire with `[:system]` roles; only mark transitions `:auto? true` when no user authorisation is required.
8. **API routes must use normalized map format, not Reitit vectors** — `workflow-routes` must return `[{:path "..." :methods {...}}]`. Returning Reitit-style vectors `[["/path" {:get ...}]]` causes `IllegalArgumentException: Key must be integer` in the versioning middleware at startup. Paths must also omit the `/api` prefix — the platform adds `/api/v1` automatically.

## Testing

```bash
clojure -M:test :workflow
```

Test fixture pattern:
```clojure
(defn with-clean-system [f]
  (registry/clear-registry!)
  (registry/register-workflow! my-workflow-def)
  (let [store    (create-memory-store)
        registry (registry/create-workflow-registry)
        svc      (service/create-workflow-service store registry)]
    (binding [*store* store *service* svc]
      (f)))
  (registry/clear-registry!))
```

## Links

- [Root AGENTS Guide](../../AGENTS.md)
