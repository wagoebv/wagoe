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
  "`(f item)` for each of `items`, in a transaction on `(schema-of item)`.
   Failures are logged and counted; the rest still run."
  [schema-provider db-ctx items schema-of f]
  (reduce
   (fn [acc item]
     (let [schema (schema-of item)]
       (try
         (let [r (ports/with-tenant-schema schema-provider db-ctx schema (fn [_tx] (f item)))]
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
               identity f))

(def ^:private tenant-page-size 100)

(defn- active-tenants
  "Every active tenant, a page at a time."
  [tenant-service]
  (letfn [(page [offset]
            (lazy-seq
             (let [ts (ports/list-tenants tenant-service {:status :active
                                                          :limit  tenant-page-size
                                                          :offset offset})]
               (when (seq ts)
                 (concat ts (when (= tenant-page-size (count ts))
                              (page (+ offset tenant-page-size))))))))]
    (page 0)))

(defn for-each-tenant
  "Call `(f tenant)` for every active tenant, in one transaction on its schema
   with `db/*tenant-id*` bound, as a tenant request runs. A tenant whose run
   throws is logged and counted, and the rest still run.

   Returns {:processed n :failed n :results [<f's result per tenant that ran>]}."
  [tenant-service schema-provider db-ctx f]
  (each-schema schema-provider db-ctx (active-tenants tenant-service) :schema-name
               (fn [tenant] (binding [db/*tenant-id* (:id tenant)] (f tenant)))))
