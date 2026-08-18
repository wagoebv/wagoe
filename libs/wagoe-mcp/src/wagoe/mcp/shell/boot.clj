(ns ^:wagoe/allow-direct wagoe.mcp.shell.boot
  ;; Composition root: start! wires concrete adapters (audit, system-source, the
  ;; scaffolder service) the way an Integrant config would. Constructing another
  ;; module's adapter here is the canonical hexagonal exception, so this ns is
  ;; exempt from check:ports' cross-module rule (it still calls the scaffolder
  ;; only through wagoe.scaffolder.ports thereafter).
  "Everything the MCP server needs, loaded *after* the entry point has claimed
   stdout. Requiring any of these namespaces initialises logging, which is why
   they are not on wagoe.mcp.shell.server.

   Tool handlers added in BOU-100/101 must call
   `wagoe.mcp.core.security/authorize` against the context and record the
   decision via the audit log before performing any mutation."
  (:require [wagoe.mcp.core.registry :as registry]
            [wagoe.mcp.core.resources :as resources]
            [wagoe.mcp.core.security :as security]
            [wagoe.mcp.core.tools :as tools]
            [wagoe.mcp.ports :as ports]
            [wagoe.mcp.shell.audit :as audit]
            [wagoe.mcp.shell.context :as context]
            [wagoe.mcp.shell.dispatch :as dispatch]
            [wagoe.mcp.shell.evaluator :as evaluator]
            [wagoe.mcp.shell.migrator :as migrator]
            [wagoe.mcp.shell.stdio :as stdio]
            [wagoe.mcp.shell.system-source :as system-source]
            [wagoe.mcp.shell.test-runner :as test-runner]
            [wagoe.scaffolder.shell.service :as scaffolder]
            [clojure.tools.logging :as log]))

(defn- seed-registry
  "Register the reflective resources (BOU-99) and Tier 0 tools (BOU-100).

   Only the resources this project can serve: the catalog is filtered against a
   snapshot, so `resources/list` stops advertising four resources that answer
   \"not available in the current context\" in every project (BOU-320)."
  [system-source]
  (as-> registry/empty-registry r
    (reduce registry/register-resource r
            (resources/available-catalog (ports/snapshot system-source)))
    (reduce registry/register-tool r tools/catalog)))

(defn start!
  "Wire the server and block until stdin reaches EOF.

   `protocol-out` is the real stdout, handed over by the entry point after it
   claimed it — see wagoe.mcp.shell.server/claim-stdout!."
  [protocol-out]
  (let [ctx        (context/from-env)
        audit-log  (audit/logging-audit-log)
        sys-source (system-source/in-process-system-source)
        deps      {:registry      (seed-registry sys-source)
                   :security      ctx
                   :audit         audit-log
                   :system-source sys-source
                   ;; Tier 1 generate tools (BOU-101): the scaffolder writes the
                   ;; code; the test-runner runs the project's affected tests in
                   ;; the closed verify loop.
                   :scaffolder    (scaffolder/create-scaffolder-service)
                   :test-runner   test-runner/default-test-runner
                   ;; Tier 2 execute tools (BOU-102): all RCE-class, gated to the
                   ;; :full context. The evaluator/migrator shell into (or run
                   ;; in) the project; query-db needs a read-only datasource not
                   ;; yet wired, so it returns :unavailable until one is.
                   :evaluator     evaluator/default-evaluator
                   :migrator      migrator/default-migrator
                   :db-query      nil
                   ;; sql-preview / gen-tests AI provider is config-driven; nil
                   ;; yields a graceful :unavailable result until one is wired.
                   :ai-provider   nil}]
    (doseq [w (:warnings ctx)]
      (log/warn w))
    (ports/record! audit-log {:event    :server-start
                              :security (security/describe ctx)
                              :warnings (:warnings ctx)})
    (stdio/serve (stdio/transport System/in protocol-out)
                 (fn [msg] (dispatch/dispatch deps msg)))))
