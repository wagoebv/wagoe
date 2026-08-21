(ns acme.tasks.shell.module-wiring
  "Stands in for what `bb scaffold generate --module-name tasks` writes.

   `system-config` requires a scaffolded module's wiring namespace and fails the
   boot when it will not load — a typo in a config key looks exactly like a
   missing module, and the silent skip is the defect that gate exists for. So a
   test of discovery needs a namespace that really is on the classpath; a stub
   loader would test the injection rather than the thing."
  (:require [integrant.core :as ig]))

(defmethod ig/init-key :wagoe/tasks            [_ config] config)
(defmethod ig/init-key :wagoe/tasks-repository [_ config] config)
(defmethod ig/init-key :wagoe/tasks-service    [_ config] config)
(defmethod ig/init-key :wagoe/tasks-routes     [_ config] config)
