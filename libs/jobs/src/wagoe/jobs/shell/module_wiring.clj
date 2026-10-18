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
        :scheduler {:tick-ms 5000 :lease-ms 30000 :zone \"UTC\"}}

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
     adds. `:scheduler {:tick-ms :lease-ms :zone}` tunes it.

   :wagoe/job-registry
     The handler map every module contributed. Modules contribute handlers the
     way they contribute routes: a `:job-handlers` vector in their `ig-config`,
     collected by `wagoe.platform.shell.system.config` (BOU-330 pattern).

   :wagoe/job-workers
     The worker pool. `:count 0` builds none — that is a web node in the
     web/worker split; the default of 1 is a single process that both enqueues
     and runs, which is what an application without a deployment topology has.

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
            [wagoe.jobs.shell.recurring :as recurring]
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
(defmethod ig/init-key :wagoe/job-dead-letter-retention
  [_ {:keys [store]}]
  (reify privacy/IRetentionPolicy
    (purge-before! [_ cutoff] (ports/purge-dead-letters-before! store cutoff))))

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

(defmethod ig/init-key :wagoe/job-workers
  [_ {:keys [queue store registry workers]}]
  (let [count*  (:count workers 1)
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
                       (merge workers {:queue-name q :worker-count count*})
                       queue store registry)))
            queues)
      (do (log/info "Jobs: no workers started (:count 0) — this node enqueues only")
          []))))

(defmethod ig/halt-key! :wagoe/job-workers
  [_ workers]
  (when (seq workers) (worker/stop-worker-pool! workers)))

(defmethod ig/init-key :wagoe/job-scheduler
  [_ {:keys [runtime registry recurring scheduler]}]
  (let [s (recurring/create-scheduler {:queue       (:queue runtime)
                                       :state       (:recurring-state runtime)
                                       :idempotency (:idempotency runtime)
                                       :registry    registry}
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
  [settings _ctx]
  (let [settings (or settings {})]
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
      :wagoe/job-scheduler {:runtime   (ig/ref :wagoe/jobs-runtime)
                            :registry  (ig/ref :wagoe/job-registry)
                            :recurring (:recurring settings)
                            :scheduler (:scheduler settings)}
      :wagoe/job-registry {:handler-maps []}
      :wagoe/job-workers  {:queue    (ig/ref :wagoe/job-queue)
                           :store    (ig/ref :wagoe/job-store)
                           :registry (ig/ref :wagoe/job-registry)
                           :workers  (:workers settings)}
      :wagoe/job-dead-letter-retention {:store (ig/ref :wagoe/job-store)}}
     :retention {:jobs/dead-letter (ig/ref :wagoe/job-dead-letter-retention)}}))
