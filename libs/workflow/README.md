# wagoe/workflow

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-workflow.svg)](https://clojars.org/com.wagoe/wagoe-workflow)

**Declarative state machine workflows for domain entities in the Wagoe Framework**

Define state machines as data, execute guarded transitions, and get a full audit trail automatically.
Optionally enqueue side-effect jobs via `wagoe-jobs` after successful transitions.

---

## Quick Start

### 1. Define a Workflow

```clojure
(require '[wagoe.workflow.shell.registry :refer [defworkflow]])

(defworkflow order-workflow
  {:id            :order-workflow
   :initial-state :pending
   :description   "E-commerce order lifecycle"
   :states        #{:pending :paid :shipped :delivered :cancelled}
   :transitions   [{:from :pending :to :paid
                    :required-permissions [:finance :admin]}
                   {:from :paid    :to :shipped
                    :guard         :payment-confirmed}
                   {:from :shipped :to :delivered}
                   {:from :pending :to :cancelled
                    :side-effects  [:notify-cancellation]}
                   {:from :paid    :to :cancelled}]})
```

`defworkflow` binds the var and registers the definition in the in-process registry.

### 2. Start an Instance

```clojure
(require '[wagoe.workflow.ports :as ports])

(def instance
  (ports/start-workflow! engine
                         {:workflow-id :order-workflow
                          :entity-type :order
                          :entity-id   order-uuid}))
```

### 3. Execute a Transition

```clojure
(def result
  (ports/transition! engine
                     {:instance-id (:id instance)
                      :transition  :paid
                      :actor-roles [:admin]
                      :context     {:payment-method "stripe"}}))

(:success? result)                          ;; => true
(:current-state (:instance result))         ;; => :paid
```

### 4. Query State and History

```clojure
;; Current state
(ports/current-state engine (:id instance))  ;; => :paid

;; Full audit log
(ports/audit-log engine (:id instance))
;; => [{:from-state :pending :to-state :paid :transition :paid ...}]
```

---

## Core Concepts

### Transition Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `:from` | keyword | yes | Source state |
| `:to` | keyword | yes | Target state |
| `:name` | keyword | no | Transition name (defaults to `:to`) |
| `:required-permissions` | `[keyword]` | no | Actor must have at least one |
| `:guard` | keyword | no | Key in the guard-registry map |
| `:side-effects` | `[keyword]` | no | Job types enqueued after success |

### Guards

Guards are pure functions registered at service creation time. They receive the `:context`
map from the transition request and return `true` (allow) or `false` (reject):

```clojure
(def guard-registry
  {:payment-confirmed (fn [ctx] (= :confirmed (:payment-status ctx)))})

(service/create-workflow-service store registry nil guard-registry)
```

### Side Effects

Side-effect keywords declared on a transition are enqueued as `wagoe-jobs` jobs after
a successful transition. The `job-queue` dependency is optional — if nil, side effects are
silently skipped:

```clojure
;; Transition declares: :side-effects [:notify-cancellation]
;; Register a handler in your app:
(ports/register-handler! job-registry :notify-cancellation
                         (fn [args] ...))
```

---

## Configuration

```edn
;; resources/conf/dev/config.edn
{:wagoe/workflow
 {:db-ctx    #ig/ref :wagoe/database-context
  :job-queue #ig/ref :wagoe/job-queue}}  ; optional
```

The component map returned by Integrant:

```clojure
{:store    <IWorkflowStore>
 :registry <IWorkflowRegistry>
 :engine   <IWorkflowEngine>}
```

---

## HTTP API

| Method | Canonical Path | Description |
|--------|----------------|-------------|
| `POST` | `/api/v1/workflow/instances` | Start a new workflow instance |
| `GET` | `/api/v1/workflow/instances/:id` | Current state + allowed transitions |
| `GET` | `/api/v1/workflow/instances/:id/audit` | Full audit log |
| `POST` | `/api/v1/workflow/instances/:id/transition` | Execute a transition |

Unversioned `/api/workflow/*` paths are backward-compatibility redirects to `/api/v1/workflow/*`.

---

## Database Schema

The tables ship as migrations in `libs/workflow/resources/wagoe/workflow/migrations/`,
so `bb migrate up` creates them with no boot. `:wagoe/workflow-db-schema` creates
them at boot for installations that do not migrate. Timestamps are
`TIMESTAMP WITH TIME ZONE`; tables created before BOU-502 stored them as TEXT and
are converted by the second migration, which only `migrate up` runs. Workflow
supports PostgreSQL, H2 and SQLite, not MySQL.

---

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

---

## License

Part of Wagoe Framework — see main LICENSE file.
