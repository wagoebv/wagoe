(ns wagoe.observability.tracing.core
  "Pure ergonomics for tracing — the `with-span` macro. Depends only on the
   tracing port; the side effects happen in the tracer the macro is handed."
  (:require [wagoe.observability.tracing.ports :as ports]))

(defmacro with-span
  "Run `body` inside a span, bound to `span-sym`. Delegates to the tracer's
   `with-span*`, so the span is started, has any thrown exception recorded and
   rethrown, and is always ended. Returns the value of `body`.

     (with-span tracer [sp \"handle-request\" {:route \"/x\"}]
       (handle sp request))

   The attributes map is optional:

     (with-span tracer [sp \"work\"] (do-work))"
  [tracer [span-sym name & [attributes]] & body]
  `(ports/with-span* ~tracer ~name ~(or attributes {})
     (fn [~span-sym] ~@body)))
