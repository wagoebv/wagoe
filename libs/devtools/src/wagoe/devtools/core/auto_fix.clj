(ns wagoe.devtools.core.auto-fix
  "Fix descriptor registry — maps error codes to auto-fix descriptors.
   Pure functions — no I/O, no side effects.

   Fix descriptors describe WHAT to fix, not HOW. The shell layer
   (wagoe.devtools.shell.auto-fix) handles execution.")

(def ^:private fix-catalog
  "Map of BND error codes to fix descriptors."
  {"BND-301" {:fix-id :apply-migration
              :label  "Apply pending database migration"
              :safe?  true
              :action :migrate-up}

   "BND-101" {:fix-id :set-env-var
              :label  "Set missing environment variable for current session"
              :safe?  true
              :action :set-env}

   "BND-103" {:fix-id :set-jwt-secret
              :label  "Generate and set dev JWT_SECRET for current session"
              :safe?  true
              :action :set-jwt}

   "BND-601" {:fix-id :refactor-fcis
              :label  "Show FC/IS refactoring steps"
              :safe?  false
              :action :show-refactoring}

   "BND-WIRING" {:fix-id :integrate-module
                 :label  "Wire scaffolded module into the system"
                 :safe?  true
                 :action :integrate-module}

   "BND-DEP" {:fix-id :add-dependency
              :label  "Add missing dependency to deps.edn"
              :safe?  false
              :action :add-dependency}})

(defn match-fix
  "Find a fix descriptor for a classified error.
   Returns a fix descriptor map or nil if no fix is available.

   The :data from the classified error is merged into :params
   so the executor has context about what to fix."
  [{:keys [code data]}]
  (when-let [fix (get fix-catalog code)]
    (assoc fix :params (or data {}))))
