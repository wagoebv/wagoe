(ns wagoe.test.logging
  "Utilities for shrinking log noise in tests."
  (:require [clojure.tools.logging :as log]))

(defmacro with-silent-logging [& body]
  `(with-redefs [log/info (fn [& args#] nil)
                 log/error (fn [& args#] nil)
                 log/debug (fn [& args#] nil)
                 log/warn (fn [& args#] nil)]
     ~@body))
