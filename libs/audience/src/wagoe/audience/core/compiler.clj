(ns wagoe.audience.core.compiler
  "Compile audience segment definitions into execution plans.
   Partitions filters into SQL-evaluable and predicate-evaluable phases."
  (:require [wagoe.audience.core.filter :as f]))

(defn compile-segment
  "Compile a segment definition into an execution plan.
   Returns {:query-filters [...] :predicates [...] :unsupported [...]}

   `:query-filters` are the filters `filter->sql` can express, left as data for
   the user source to translate.

   `:unsupported` holds the filters that could be turned into neither a clause
   nor a predicate — today that means a date filter compiled without `:now`.
   The shell refuses to resolve an audience that has any, rather than silently
   evaluating a smaller set of filters than the definition asked for.
   Accepts optional :now (java.time.LocalDate) for predicate date comparisons.
   If not provided, predicates that need dates will receive :now from the filter map."
  ([definition]
   (compile-segment definition {}))
  ([definition {:keys [now]}]
   (reduce
    (fn [plan filter-def]
      ;; `filter-with-now` to both: the date filters compute their cutoff from
      ;; `:now` on the SQL side too, since a bound parameter replaced the
      ;; PostgreSQL-only interval expression (BOU-425). Passing the bare
      ;; `filter-def` here is what left `filter->sql` unable to see it.
      (let [filter-with-now (if now (assoc filter-def :now now) filter-def)
            sql (f/filter->sql filter-with-now)]
        (cond
          sql
          (update plan :query-filters conj filter-with-now)

          ;; A filter that can be expressed neither way. The date filters answer
          ;; nil to both when no `:now` reached them, and building a predicate
          ;; anyway meant one that threw on the first user carrying the
          ;; timestamp — while an empty plan would have matched everyone, which
          ;; is worse (BOU-425 review).
          :else
          (if-let [pred (f/filter->predicate filter-with-now)]
            (update plan :predicates conj pred)
            (update plan :unsupported conj filter-def)))))
    {:query-filters [] :predicates [] :unsupported []}
    (:filters definition))))
