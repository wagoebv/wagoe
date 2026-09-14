# wagoe/events

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-events.svg)](https://clojars.org/com.wagoe/wagoe-events)

An event bus for telling other modules something happened. The publisher does
not know who is listening, does not wait, and is unaffected if a consumer is
down.

A port is for getting an answer; this is for the other case.

## Installation

**deps.edn**:
```clojure
{:deps {com.wagoe/wagoe-events {:mvn/version "1.0.0-beta-8"}}}
```

## Features

| Feature | Adapter | Description |
|---------|---------|-------------|
| **Two adapters** | both | `:memory` for one process, `:redis` (Redis Streams) across replicas |
| **At-least-once** | `:redis` | An event is redelivered until acknowledged, so consumers must be idempotent |
| **Consumer groups** | `:redis` | Each event reaches exactly one member of a group, and every group |
| **Dead-letter** | `:redis` | After `:max-deliveries` an event is moved to `<stream>:dead` rather than stalling its topic |
| **History** | both | `IEventHistory` replays what a topic has seen — within stream retention, or the in-process buffer |

Under `:memory` there is no acknowledgement and no retry: a handler that throws
is logged and the event is dropped, and nothing survives the process. That is
the right trade for a test or a single node, and the wrong one to build on —
use `:redis` where delivery has to be reliable.

## Quick Start

```clojure
;; config.edn, under :active
:wagoe/events
{:provider :redis            ; or :memory for one process
 :host     #env REDIS_HOST
 :group    "my-app"}         ; one per logical consumer
```

```clojure
(require '[wagoe.events.ports :as events]
         '[wagoe.events.shell.publisher :as publisher])

;; emit! wraps the payload in the envelope the bus requires — :id, :type,
;; :source and :published-at — and publishes it to a topic. Publishing a bare
;; map instead returns {:error {:type :events/invalid}}.
(publisher/emit! bus :orders :order/placed :checkout {:order-id id :total 42.00})

;; The handler receives the whole event; the map you published is its :payload.
(events/subscribe! bus :orders
                   (fn [event]
                     (send-confirmation! (:order-id (:payload event)))))
```

Events are statements of fact in the past tense — `:order/placed`, not
`:place-order`. A command with one recipient belongs on a port; work to be done
later belongs in `wagoe-jobs`.

## Testing

```bash
clojure -M:test :events
```

The Redis cases need a Redis on `localhost:6379`; without one they are skipped,
and CI runs them against a `redis:7-alpine` service.

## Documentation

- [AGENTS.md](AGENTS.md) — module reference: config, delivery semantics, adapter differences, pitfalls.
- [events library guide](../../docs/modules/libraries/pages/events.adoc) — narrative documentation.

## License

Copyright © 2024-2026 Thijs Creemers

Distributed under the Eclipse Public License version 2.0.
