(ns wagoe.jobs.shell.module-wiring
  "Integrant wiring for the jobs module.

   Config keys:

   :wagoe/jobs
     The settings block that switches the module on:
       {:provider :memory | :db | :redis     ; default :memory
        :lease-ms 60000                      ; :db only, in-flight lease
        :redis    {:host \"localhost\" :port 6379}
        :workers  {:count 1 :queue-name :default :virtual-threads? false}
        :recurring {:nightly-report {:cron \"0 2 * * *\" :zone \"Europe/Amsterdam\"}}
        :scheduler {:tick-ms 5000 :lease-ms 30000 :zone \"UTC\"}
        :on-outcome my.app.jobs/on-outcome   ; optional, (fn [outcome])
        :report-failures-every-ms 3600000    ; error-tracker throttle
        :expect-success {:nightly-report 26}} ; job type -> hours, else reported

   :wagoe/jobs-runtime
     Queue, store and stats from one adapter, so the three share a backend.

   :wagoe/job-queue, :wagoe/job-store, :wagoe/job-stats
     What other modules and the devtools dashboard take a ref to — `workflow`
     and `push` already document `:job-queue`. Projections of the runtime rather
     than components of their own, because the in-memory adapter's queue and
     store must share state. `:wagoe/job-stats` is nil under `:provider :db`,
     which ships no IJobStats.

   :wagoe/idempotency-store
     `IIdempotencyStore` on the runtime's backend, for any module.

   :wagoe/job-scheduler
     The recurring-job ticker: `:recurring {job-type {:cron \"0 2 * * *\" …}}`
     from the settings block, plus whatever `recurring/schedule-recurring!`
     adds. `:scheduler {:tick-ms :lease-ms :zone :watch-ms}` tunes it. With
     `:expect-success` its lease holder reports a job type that has not
     succeeded within its hours (`outcomes/check-stale!`).

   :wagoe/job-registry
     The handler map every module contributed. Modules contribute handlers the
     way they contribute routes: a `:job-handlers` vector in their `ig-config`,
     collected by `wagoe.platform.shell.system.config` (BOU-330 pattern).

   :wagoe/job-workers
     The worker pool. `:count 0` builds none — that is a web node in the
     web/worker split; the default of 1 is a single process that both enqueues
     and runs, which is what an application without a deployment topology has.
     Each run's outcome goes to the store's `IJobOutcomes`, `:wagoe/metrics`,
     `:wagoe/error-reporting` and `:on-outcome` (`wagoe.jobs.shell.outcomes`).

   :wagoe/job-tenant-runner
     With the tenant module on: what runs a job that carries a tenant in that
     tenant's schema. The workers take it; without it such a job fails.

   :wagoe/job-dead-letter-retention
     The `:jobs/dead-letter` retention policy, contributed under `:retention`.
     Runs only when `:wagoe/privacy :retention` names it.

   Until BOU-418 this namespace wired none of it: `:wagoe/jobs` was a settings
   passthrough, so `java -jar wagoe.jar worker` booted and processed nothing."
  (:require [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [wagoe.jobs.ports :as ports]
            [wagoe.platform.ports.privacy :as privacy]
            [wagoe.jobs.shell.adapters.db :as db]
            [wagoe.jobs.shell.adapters.in-memory :as mem]
            [wagoe.jobs.shell.adapters.redis :as redis]
            [wagoe.jobs.shell.outcomes :as outcomes]
            [wagoe.jobs.shell.recurring :as recurring]
            [wagoe.jobs.shell.tenant-context :as tenant-context]
            [wagoe.tenant.shell.provisioning :as provisioning]
            [wagoe.jobs.shell.worker :as worker]))

(defmethod ig/init-key :wagoe/jobs [_ config] config)

(defmethod ig/halt-key! :wagoe/jobs [_ _config] nil)

;; =============================================================================
;; Runtime: one adapter, three roles
;; =============================================================================

(defn- db-runtime
  [db-ctx lease-ms]
  (let [ds (:datasource db-ctx)]
    (when-not ds
      (throw (ex-info (str ":wagoe/jobs is configured with :provider :db but "
                           ":wagoe/db-context has no :datasource. Configure a "
                           "database adapter, or use :provider :memory.")
                      {:type :wagoe/jobs-no-datasource})))
    ;; Idempotent DDL, so a fresh database needs no separate migration step and
    ;; an existing one is untouched.
    ;; job_store too: create-jobs-table! makes both.
    (db/create-jobs-table! ds)
    (db/create-recurring-tables! ds)
    {:queue  (db/create-db-job-queue ds :lease-ms lease-ms)
     :store  (db/create-db-job-store ds)
     :idempotency     (db/create-db-idempotency-store ds)
     :recurring-state (db/create-db-recurring-state ds)
     ;; No IJobStats for the DB backend; the two that have one expose it, and a
     ;; nil here is honest where a silently-empty implementation would not be.
     :stats  nil
     :close! (fn [] nil)}))

(defn- redis-runtime
  [redis-settings]
  (let [pool (redis/create-redis-pool (or redis-settings {}))]
    {:queue  (redis/create-redis-job-queue pool)
     :store  (redis/create-redis-job-store pool)
     :stats  (redis/create-redis-job-stats pool)
     :idempotency     (redis/create-redis-idempotency-store pool)
     :recurring-state (redis/create-redis-recurring-state pool)
     :close! (fn [] (redis/close-redis-pool! pool))}))

(defn- memory-runtime
  []
  ;; One shared state on purpose: the worker dequeues from the queue and records
  ;; the outcome on the store, and with two states the store would never find
  ;; the job it is asked about.
  (let [{:keys [queue store stats]} (mem/create-in-memory-jobs-system)]
    {:queue           queue
     :store           store
     :stats           stats
     :idempotency     (mem/create-in-memory-idempotency-store)
     :recurring-state (mem/create-in-memory-recurring-state)
     :close!          (fn [] nil)}))

(defn normalize-provider
  "The provider keyword in the vocabulary cache, realtime, events and jobs
   share: `:memory` | `:redis`, and `:db` — which this is the only module to
   have, because it is the only one with a database adapter.

   Jobs already spelled it this way; `:in-memory` and `:database` are accepted
   because the other three modules used them, and a user moving between config
   blocks should not have to remember which is which (BOU-436)."
  [provider]
  (case provider
    nil :memory
    :in-memory (do (log/warn "Jobs :provider :in-memory is spelled :memory")
                   :memory)
    :database (do (log/warn "Jobs :provider :database is spelled :db")
                  :db)
    provider))

(defmethod ig/init-key :wagoe/jobs-runtime
  [_ {:keys [provider db-ctx lease-ms redis]}]
  (let [provider (normalize-provider provider)]
    (log/info "Jobs: initializing runtime" {:provider provider})
    (case provider
      :memory (memory-runtime)
      :db     (db-runtime db-ctx (or lease-ms db/default-lease-ms))
      :redis  (redis-runtime redis)
      (throw (ex-info (str "Unknown :wagoe/jobs :provider " (pr-str provider)
                           ". Use :memory, :db or :redis.")
                      {:type :wagoe/jobs-unknown-provider :provider provider})))))

(defmethod ig/halt-key! :wagoe/jobs-runtime
  [_ {:keys [close!]}]
  (when close! (close!))
  (log/info "Jobs: runtime halted"))

(defmethod ig/init-key :wagoe/job-queue [_ {:keys [runtime]}] (:queue runtime))
(defmethod ig/halt-key! :wagoe/job-queue [_ _] nil)

(defmethod ig/init-key :wagoe/job-store [_ {:keys [runtime]}] (:store runtime))
(defmethod ig/halt-key! :wagoe/job-store [_ _] nil)

;; nil under `:provider :db`, which has no IJobStats — devtools reads this key
;; and shows what it finds, so an absent implementation reads as absent rather
;; than as an implementation that answers zero. The key was missing entirely
;; and the dashboard's Jobs page showed zeros for a busy queue (BOU-418 review).
(defmethod ig/init-key :wagoe/job-stats [_ {:keys [runtime]}] (:stats runtime))
(defmethod ig/halt-key! :wagoe/job-stats [_ _] nil)

(defmethod ig/init-key :wagoe/idempotency-store [_ {:keys [runtime]}] (:idempotency runtime))
(defmethod ig/halt-key! :wagoe/idempotency-store [_ _] nil)

;; The job store is the policy: each adapter implements IDeadLetterRetention.
;; nil for a store that does not, so configuring the policy fails the boot
;; (:wagoe/retention) rather than every run.
(defmethod ig/init-key :wagoe/job-dead-letter-retention
  [_ {:keys [store]}]
  (if (satisfies? ports/IDeadLetterRetention store)
    (reify privacy/IRetentionPolicy
      (purge-before! [_ cutoff] (ports/purge-dead-letters-before! store cutoff)))
    (log/warn "Jobs: the job store does not implement IDeadLetterRetention, so :jobs/dead-letter retention cannot run"
              {:store (some-> store class .getName)})))

;; =============================================================================
;; Registry and workers
;; =============================================================================

(defmethod ig/init-key :wagoe/job-registry
  [_ {:keys [handler-maps]}]
  (let [registry (worker/create-job-registry)
        handlers (into {} (remove nil?) handler-maps)]
    (doseq [[job-type handler] handlers]
      (ports/register-handler! registry job-type handler))
    (log/info "Jobs: registered handlers" {:job-types (vec (sort (keys handlers)))})
    registry))

(defmethod ig/halt-key! :wagoe/job-registry [_ _] nil)

(defmethod ig/init-key :wagoe/job-tenant-runner
  [_ {:keys [db-ctx tenant-service]}]
  (tenant-context/tenant-runner db-ctx tenant-service (provisioning/create-tenant-schema-provider)))

(defmethod ig/halt-key! :wagoe/job-tenant-runner [_ _] nil)

(defn- resolve-hook
  "`:on-outcome` as config.edn names it, a symbol, resolved to its fn."
  [hook]
  (if (symbol? hook)
    (or (some-> (try (requiring-resolve hook) (catch Exception _ nil)) deref)
        (throw (ex-info (str ":wagoe/jobs :on-outcome names " hook ", which resolves to nothing")
                        {:type :configuration-error :symbol hook})))
    hook))

(defmethod ig/init-key :wagoe/job-workers
  [_ {:keys [queue store registry workers tenant-runner metrics error-reporter
             on-outcome report-failures-every-ms idempotency]}]
  (let [count*  (:count workers 1)
        ;; One reporter for every pool: the error-tracker throttle is per job
        ;; type, whichever queue it runs on, and on whichever node.
        report  (outcomes/reporter {:store                    store
                                    :metrics                  metrics
                                    :error-reporter           error-reporter
                                    :on-outcome               (resolve-hook on-outcome)
                                    :report-failures-every-ms report-failures-every-ms
                                    :idempotency              idempotency})
        ;; One pool per queue. A worker polls a single queue, so a module that
        ;; enqueues elsewhere is only served if a pool names that queue — push
        ;; enqueued on :push while the only pool polled :default, and every
        ;; push sat there (BOU-418 review). Naming the queues here is what
        ;; makes that arrangement deliberate instead of silent.
        queues  (or (seq (:queues workers))
                    [(:queue-name workers :default)])]
    (if (pos? count*)
      (into []
            (mapcat (fn [q]
                      (worker/create-worker-pool
                       (merge workers {:queue-name q :worker-count count* :tenant-runner tenant-runner
                                       :report-outcome report})
                       queue store registry)))
            queues)
      (do (log/info "Jobs: no workers started (:count 0) — this node enqueues only")
          []))))

(defmethod ig/halt-key! :wagoe/job-workers
  [_ workers]
  (when (seq workers) (worker/stop-worker-pool! workers)))

(defn- expect-success!
  "`expect-success`, {job-type hours}, refused unless each hours is positive."
  [expect-success]
  (when-not (or (nil? expect-success)
                (and (map? expect-success)
                     (every? (fn [[k v]] (and (keyword? k) (number? v) (pos? v))) expect-success)))
    (throw (ex-info (str ":wagoe/jobs :expect-success takes {job-type hours}, hours above 0; got "
                         (pr-str expect-success))
                    {:type :validation-error :expect-success expect-success})))
  expect-success)

(defmethod ig/init-key :wagoe/job-scheduler
  [_ {:keys [runtime registry recurring scheduler expect-success error-reporter
             report-failures-every-ms]}]
  (let [watch (when (seq (expect-success! expect-success))
                (let [opts {:store                    (:store runtime)
                            :idempotency              (:idempotency runtime)
                            :error-reporter           error-reporter
                            :expect-success           expect-success
                            :report-failures-every-ms report-failures-every-ms}]
                  (log/info "Jobs: expecting successes" {:hours expect-success})
                  #(outcomes/check-stale! opts %)))
        s     (recurring/create-scheduler {:queue       (:queue runtime)
                                           :state       (:recurring-state runtime)
                                           :idempotency (:idempotency runtime)
                                           :registry    registry
                                           :watch       watch}
                                          scheduler)]
    (doseq [[job-type {:keys [cron] :as opts}] recurring]
      (recurring/schedule-recurring! s job-type cron opts))
    (when (seq recurring)
      (log/info "Jobs: recurring schedules" {:job-types (vec (sort (keys recurring)))}))
    (recurring/start! s)))

(defmethod ig/halt-key! :wagoe/job-scheduler [_ scheduler] (recurring/stop! scheduler))

;; =============================================================================
;; Module graph
;; =============================================================================

(defn ig-config
  "This module's Integrant entries, for `wagoe.platform.shell.system.config`.

   `:handler-maps` is filled in by the application's system config from what the
   enabled modules contributed, the way `:module-routes` is — jobs cannot name
   the modules that have handlers any more than the HTTP handler can name the
   modules that have routes."
  [settings {:keys [enabled]}]
  (let [settings (or settings {})
        tenancy? (contains? (or enabled #{}) :wagoe/tenant)]
    (cond->
     {:components
      {:wagoe/jobs         settings
       :wagoe/jobs-runtime {:provider (:provider settings :memory)
                            :lease-ms (:lease-ms settings)
                            :redis    (:redis settings)
                            :db-ctx   (ig/ref :wagoe/db-context)}
       :wagoe/job-queue    {:runtime (ig/ref :wagoe/jobs-runtime)}
       :wagoe/job-store    {:runtime (ig/ref :wagoe/jobs-runtime)}
       :wagoe/job-stats    {:runtime (ig/ref :wagoe/jobs-runtime)}
       :wagoe/idempotency-store {:runtime (ig/ref :wagoe/jobs-runtime)}
       :wagoe/job-scheduler {:runtime        (ig/ref :wagoe/jobs-runtime)
                             :registry       (ig/ref :wagoe/job-registry)
                             :recurring      (:recurring settings)
                             :scheduler      (:scheduler settings)
                             :expect-success (:expect-success settings)
                             :error-reporter (ig/ref :wagoe/error-reporting)
                             :report-failures-every-ms (:report-failures-every-ms settings)}
       :wagoe/job-registry {:handler-maps []}
       :wagoe/job-workers  {:queue          (ig/ref :wagoe/job-queue)
                            :store          (ig/ref :wagoe/job-store)
                            :registry       (ig/ref :wagoe/job-registry)
                            :workers        (:workers settings)
                            :metrics        (ig/ref :wagoe/metrics)
                            :error-reporter (ig/ref :wagoe/error-reporting)
                            :on-outcome     (:on-outcome settings)
                            :report-failures-every-ms (:report-failures-every-ms settings)
                            :idempotency    (ig/ref :wagoe/idempotency-store)}
       :wagoe/job-dead-letter-retention {:store (ig/ref :wagoe/job-store)}}
      :retention {:jobs/dead-letter (ig/ref :wagoe/job-dead-letter-retention)}}
      tenancy?
      (-> (assoc-in [:components :wagoe/job-tenant-runner]
                    {:db-ctx         (ig/ref :wagoe/db-context)
                     :tenant-service (ig/ref :wagoe/tenant-service)})
          (assoc-in [:components :wagoe/job-workers :tenant-runner]
                    (ig/ref :wagoe/job-tenant-runner))))))
