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
   :hooks          {:on-enter-paid     [(fn [instance _audit-entry _context]
                                          (notify-finance! instance))]
                    :on-any-transition [(fn [instance _audit-entry _context]
                                          (sync-external! instance))]}
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
                    {:from :paid    :to :cancelled}]
   :guards         {:payment-confirmed (fn [{:keys [payment-status]}]
                                         (= :confirmed payment-status))}})
```

`defworkflow` binds the var and registers the definition in the in-process registry.

### Two ways to register, and what each costs

`defworkflow` is the shorter one, and it is not free. It lives in
`wagoe.workflow.shell.registry`, so requiring it from your own module's shell
is one module's shell reaching into another's — which is exactly what
`bb check:ports` exists to catch. Your namespace needs the escape hatch:

```clojure
(ns ^:wagoe/allow-direct acme.order.shell.workflow
  (:require [wagoe.workflow.shell.registry :refer [defworkflow]]))
```

The alternative needs no escape hatch. Define the map with a plain `def` and
register it through the published port from your module's Integrant wiring —
a composition root, which the checker exempts:

```clojure
;; acme/order/shell/workflow.clj
(def order-workflow {:id :order-workflow ...})

;; acme/order/shell/module_wiring.clj
(defmethod ig/init-key :acme/order-service [_ {:keys [registry]}]
  (workflow-ports/register-workflow! registry order-workflow)
  ...)
```

Both are supported. Prefer the port when you care about keeping
`bb check:ports` clean without exemptions; prefer the macro for a quick
definition in a project that is not policing boundaries.

**Linting:** the macro defines a var clj-kondo cannot see from a published jar,
so the bound name reads as an unresolved symbol. From 1.0.0-rc-4,
`wagoe-workflow` ships a clj-kondo export carrying the rule (BOU-503) — but an
export only applies once it has been copied into the project's
`.clj-kondo/imports/`. clj-kondo does not read exports during an ordinary lint,
and `bb check` passes source paths rather than the classpath. Import them once,
and again after changing dependencies:

```bash
bb lint:imports     # writes .clj-kondo/imports/ — commit it
```

After that the project needs no `:lint-as` entry of its own. Before rc-4, or if
you would rather not commit the imports, keep the rule local:

```clojure
{:lint-as {wagoe.workflow.shell.registry/defworkflow clojure.core/def}}
```

## Transition Fields

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `:from` | keyword | yes | Source state |
| `:to` | keyword | yes | Target state |
| `:name` | keyword | no | Transition name (defaults to `:to`) |
| `:label` | string | no | Human-readable display label |
| `:required-permissions` | `[keyword]` | no | Actor needs at least one |
| `:guard` | keyword | no | Key in the workflow's `:guards` or the service's guard registry |
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

;; Transitions out of the current state; args are actor roles and the guard context.
;; A disabled one carries a :reason, e.g. :guard-rejected.
(ports/available-transitions engine (:id instance) [:admin] {:payment-status :confirmed})
;; => [{:id :shipped :to :shipped :enabled? true}
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
:hooks {:on-enter-paid     [(fn [instance _audit-entry _context]      ; entering :paid
                              (notify-finance! instance))]
        :on-exit-pending   [(fn [instance _audit-entry _context]      ; leaving :pending
                              (release-reservation! instance))]
        :on-any-transition [(fn [_instance audit-entry context]       ; every transition
                              (sync-external! audit-entry context))]}
```

Supported hook keys:
- `:on-enter-<state>` — fires when transitioning INTO the named state
- `:on-exit-<state>` — fires when transitioning OUT OF the named state
- `:on-any-transition` — fires on every successful transition

Each value is a vector of functions, and each function takes three arguments:
the updated `WorkflowInstance`, the `AuditEntry` and the transition context.
A test evaluates the example above, so keep it runnable.

---

## Guards

A guard is a function of one map that returns truthy (allow) or falsy (reject).
Put it under `:guards` in the workflow definition. The map holds:

- every key of the request's `:context`, as before
- `:workflow/instance`, the stored instance (`:entity-type`, `:entity-id`, `:current-state`, ...)
- `:workflow/entity`, a delay, when the workflow has an `:entity-loader`.
  Deref it to call `(entity-loader entity-type entity-id)`; it is loaded only when
  a guard derefs it, and at most once per check.

A caller's own `:workflow/*` context keys are dropped, so a request cannot forge
either. A loader that throws fails `transition!` with an `:internal-error`, and
`available-transitions` shows that guard's transitions as unavailable and logs it.
This guard refuses to deliver an invoice with no lines:

```clojure
(def invoice-workflow
  {:id            :invoice-workflow
   :initial-state :draft
   :states        #{:draft :delivered}
   :transitions   [{:from :draft :to :delivered :name :deliver :guard :has-lines?}]
   ;; count-invoice-lines is yours, e.g. SELECT count(*) FROM invoice_lines WHERE invoice_id = ?
   :entity-loader (fn [_entity-type invoice-id]
                    {:line-count (count-invoice-lines invoice-id)})
   :guards        {:has-lines? (fn [{:workflow/keys [entity]}]
                                 (pos? (:line-count @entity)))}})
```

Register it with `defworkflow` or `register-workflow!` like any other definition.
A test runs this example, so keep it runnable.

Guards can also be passed to the service for every workflow; a workflow's own
`:guards` win over these:

```clojure
(service/create-workflow-service store registry nil
                                 {:payment-confirmed (fn [ctx] (= :confirmed (:payment-status ctx)))})
```

## Auto-Transitions

Transitions declared with `:auto? true` are candidate for system-initiated firing:

```clojure
(ports/start-workflow! engine {:workflow-id :order-workflow
                              :entity-type :order
                              :entity-id   (random-uuid)})

;; Fire every :auto? transition out of a state an instance is in, up to 100 per transition
(ports/process-auto-transitions! engine :order-workflow)
;; => {:processed 1 :attempted 1 :failed 0}
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

`wagoe add workflow` writes this under `:active`, and it is all the config the
module needs:

```edn
{:wagoe/workflow {}}
```

The value is not read. The module wires its own database context and schema,
and the job queue when `:wagoe/jobs` is enabled (`wagoe.workflow.shell.module-wiring/ig-config`).

The component map returned is:
```clojure
{:store    <IWorkflowStore>
 :registry <IWorkflowRegistry>
 :engine   <IWorkflowEngine>}
```

## HTTP API

Routes are defined in `shell/http.clj` as Reitit route data (no `/api` prefix) and mounted by the platform versioning middleware under `/api/v1`.

| Method | Mounted path | Description |
|--------|-------------|-------------|
| `POST` | `/api/v1/workflow/instances` | Start a new workflow instance |
| `GET` | `/api/v1/workflow/instances/:id` | Current state + `availableTransitions` (id, label, enabled?) |
| `GET` | `/api/v1/workflow/instances/:id/audit` | Full audit log |
| `POST` | `/api/v1/workflow/instances/:id/transition` | Execute a transition |

## Database Migrations

The tables ship as migrations in `libs/workflow/resources/wagoe/workflow/migrations/`,
so `bb migrate up` creates them with no boot. `:wagoe/workflow-db-schema` creates
them at boot for installations that do not migrate. Timestamps are
`TIMESTAMP WITH TIME ZONE`; tables created before BOU-502 stored them as TEXT and
are converted by the second migration, which only `migrate up` runs. Workflow
supports PostgreSQL, H2 and SQLite, not MySQL.

## Gotchas

1. **`defonce` has no docstring** — do not add a docstring to `defonce` in Clojure (only takes 2 args).
2. **Registry lives in the shell** — the definition registry (`defworkflow`, `register-workflow!`, `get-workflow`, `list-workflows`, `unregister-workflow!`, `clear-registry!`, `create-workflow-registry`) is in `wagoe.workflow.shell.registry`; `wagoe.workflow.core.machine` is pure (state-machine introspection only). `clear-registry!` is provided for tests; always call it in fixtures.
3. **Side effects are best-effort** — a failure to enqueue is logged as a warning but does not roll back the transition.
4. **`find-instance` is on `IWorkflowStore`, not `IWorkflowEngine`** — use `*store*` in tests, not `*service*`.
5. **snake_case only at DB boundary** — all internal maps use kebab-case; `instance->db`/`db->instance` handle conversion.
6. **Hooks are best-effort** — exceptions inside hook functions are caught and logged; they do NOT roll back the transition.
7. **Auto-transitions bypass permissions** — they fire with `[:system]` roles; only mark transitions `:auto? true` when no user authorisation is required.
8. **Route paths omit the `/api` prefix** — `workflow-routes` returns Reitit route data (`[["/path" {:get ...}]]`) at paths relative to the mount point. Writing `/api/workflow` there serves it at `/api/v1/api/workflow`; the platform adds `/api/v1` itself.

## Testing

```bash
clojure -M:test:test/pg :workflow
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
