(ns wagoe.test.logging
  "Utilities for shrinking log noise in tests."
  (:require [clojure.tools.logging :as log]
            [clojure.tools.logging.impl :as log-impl]))

(defmacro with-silent-logging
  "Run `body` with logging switched off.

   Binds the logger factory rather than redefining `log/info` and friends.
   Those are *macros*, and `with-redefs` on a macro var is unsound: a namespace
   compiled while the redefinition is in place expands `(log/info \"x\")` into a
   call to whatever the var now holds, so the call site becomes an ordinary
   function invocation. Once the window closes and the real macro var is back,
   that compiled code calls a macro as a function and dies with

     Wrong number of args (1) passed to: clojure.tools.logging/info

   which surfaces far from here — at halt time, inside a namespace that was
   merely unlucky enough to be loaded within the block. Any lazily-loaded
   namespace can be, and the framework loads optional module wiring exactly
   that way (BOU-131), so this was a trap waiting for whichever module was
   first required inside a silenced test. BOU-93's event bus found it.

   Binding the factory is also what tools.logging is designed for: it is
   consulted when a message is logged, not when the call site is compiled, so
   nothing about how that call site was compiled can matter. It is thread-local
   rather than global, which is the safer direction for a test helper."
  [& body]
  `(binding [log/*logger-factory* log-impl/disabled-logger-factory]
     ~@body))
