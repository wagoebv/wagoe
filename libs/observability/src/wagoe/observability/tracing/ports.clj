(ns wagoe.observability.tracing.ports
  "Ports for distributed tracing.

   A minimal, backend-agnostic span/tracer abstraction. Feature code depends on
   `ITracer` (via the `wagoe.observability.tracing.core/with-span` sugar) and
   never on a concrete backend. The default adapter is no-op; a `:logging`
   adapter records spans to the log; an OTLP adapter (OpenTelemetry) is wired
   behind the same port so any OTel backend (SigNoz, Grafana Tempo, Jaeger, …)
   works by pointing at its collector.

   A `span` is an opaque handle returned by `start-span!` and passed back to the
   other methods. No-op adapters may return a sentinel; real adapters return
   whatever they need to correlate + finish the span.")

(defprotocol ITracer
  "Create and manage spans."

  (start-span! [this name] [this name attributes]
    "Start a span named `name` (string/keyword), optionally with an initial
     `attributes` map, and return an opaque span handle. The span is a child of
     the current active span when the adapter tracks context.")

  (end-span! [this span]
    "Finish `span`. Idempotent — ending an already-ended/nil span is a no-op.")

  (add-event! [this span name] [this span name attributes]
    "Record a timestamped event `name` (with optional `attributes`) on `span`.")

  (set-attributes! [this span attributes]
    "Merge `attributes` (a map) onto `span`.")

  (record-exception! [this span throwable]
    "Record `throwable` on `span` and mark the span as errored.")

  (span-context [this span]
    "Return `{:trace-id <str-or-nil> :span-id <str-or-nil>}` for propagation and
     log correlation. Returns nil ids for a no-op span.")

  (with-span* [this name attributes f]
    "Run `(f span)`: start a span, invoke `f` with it, `end-span!` it in a
     `finally`, and `record-exception!` + rethrow on error. Returns `f`'s value.
     This is the primitive the `with-span` macro expands to."))
