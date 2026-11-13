(ns wagoe.tenant.shell.tenant-iteration
  "Run a function once per provisioned tenant schema, with that tenant's
   search_path pinned for the duration (ZZP-86).

   This is the background-job analogue of the HTTP `wrap-tenant-schema`
   middleware: jobs that must touch tenant-scoped entity tables iterate every
   provisioned `tenant_<slug>` schema and run their work inside each, so the
   same connection-pinned tenant isolation applies as on the request path.
   Without this, a job calling a repo with the pooled context runs under the
   default `search_path = public` and only sees the (now empty) public tables."
  (:require [wagoe.platform.database :as db]
            [wagoe.tenant.ports :as ports]
            [clojure.tools.logging :as log]))

(defn- each-schema
  "`(f item)` for each of `items`, in a transaction on `(schema-of item)`, with
   `db/*tenant-id*` bound to `(tenant-of item)` when `tenant-of` is given.
   Failures are logged and counted; the rest still run."
  [schema-provider db-ctx items schema-of tenant-of f]
  (reduce
   (fn [acc item]
     (let [schema (schema-of item)
           step   #(ports/with-tenant-schema schema-provider db-ctx schema (fn [_tx] (f item)))]
       (try
         ;; Bound outside the transaction, so its after-commit hooks run in the
         ;; tenant too: a job they enqueue carries it (BOU-987)
         (let [r (if tenant-of (binding [db/*tenant-id* (tenant-of item)] (step)) (step))]
           (-> acc (update :processed inc) (update :results conj r)))
         (catch Exception e
           (log/error e "Tenant-schema step failed" {:schema schema})
           (update acc :failed inc)))))
   {:processed 0 :failed 0 :results []}
   items))

(defn for-each-tenant-schema
  "For each provisioned tenant schema, run `(f schema-name)` inside that
   schema's search_path via the schema provider's `with-tenant-schema`.

   Per-tenant failures are isolated and counted — one tenant's error never
   aborts the others.

   Args:
     schema-provider - an `ITenantSchemaProvider`
     db-ctx          - database context (`{:datasource ... :adapter ...}`)
     f               - 1-arg fn, receives the schema name; runs inside the
                       tenant's pinned connection + search_path

   Returns:
     {:processed n :failed n :results [<f results for succeeded tenants>]}"
  [schema-provider db-ctx f]
  (each-schema schema-provider db-ctx (ports/list-tenant-schemas schema-provider db-ctx)
               identity nil f))

(def ^:private tenant-page-size 100)

(defn- active-tenants
  "Every active tenant, listed before any runs, so a tenant created or
   suspended meanwhile cannot shift the offset pages and repeat or skip one."
  [tenant-service]
  (loop [offset 0 acc []]
    (let [ts  (ports/list-tenants tenant-service {:status :active
                                                  :limit  tenant-page-size
                                                  :offset offset})
          acc (into acc ts)]
      (if (= tenant-page-size (count ts))
        (recur (+ offset tenant-page-size) acc)
        acc))))

(defn for-each-tenant
  "Call `(f tenant)` for every active tenant, in one transaction on its schema
   with `db/*tenant-id*` bound, as a tenant request runs. A tenant whose run
   throws is logged and counted, and the rest still run. Refused inside a
   transaction on `db-ctx`'s database: each tenant's would join it and leave
   its search_path behind. Another database's is no concern of it.

   Returns {:processed n :failed n :results [<f's result per tenant that ran>]}."
  [tenant-service schema-provider db-ctx f]
  (when (db/current-transaction db-ctx)
    (throw (ex-info "for-each-tenant cannot run inside a transaction"
                    {:type    :configuration-error
                     :message "Call it outside any transaction on its database; each tenant gets its own"})))
  (each-schema schema-provider db-ctx (active-tenants tenant-service) :schema-name :id f))

(defn- find-tenant
  "`tenant-id`'s tenant, or nil. TenantService throws `:not-found` for an
   unknown id, which a handler's own not-found would be mistaken for (BOU-985)."
  [tenant-service tenant-id]
  (try
    (ports/get-tenant tenant-service tenant-id)
    (catch clojure.lang.ExceptionInfo e
      (when-not (= :not-found (:type (ex-data e))) (throw e)))))

(defn in-tenant
  "Call `(f)` in one transaction on `tenant-id`'s schema, with `db/*tenant-id*`
   bound. Throws `:tenant-not-found` for an unknown tenant, so its work never
   runs against public. A suspended tenant's work still runs: suspension stops
   its HTTP, not the jobs that may reactivate it (BOU-992)."
  [tenant-service schema-provider db-ctx tenant-id f]
  (let [tenant (or (find-tenant tenant-service tenant-id)
                   (throw (ex-info "Tenant not found"
                                   {:type      :tenant-not-found
                                    :tenant-id tenant-id
                                    :message   "No tenant with this id; it may have been deleted"})))]
    ;; Bound outside the transaction, as each-schema does (BOU-987)
    (binding [db/*tenant-id* tenant-id]
      (ports/with-tenant-schema schema-provider db-ctx (:schema-name tenant) (fn [_tx] (f))))))
