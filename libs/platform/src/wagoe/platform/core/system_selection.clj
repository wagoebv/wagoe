(ns wagoe.platform.core.system-selection
  "Cut an Integrant config down to the modules one service runs.

   Modules are already gated by `:enabled?`, and the router mounts only the
   routes it is handed, so a process *can* run a subset — there was just no way
   to say which subset (BOU-91). This is that, as a pure transformation:
   config in, smaller config out.

   The whole difficulty is refs. Dropping `:wagoe/tenant-service` leaves
   `:wagoe/http-handler` pointing at a key that no longer exists, and Integrant
   refuses to build a config with a dangling ref — so the refs have to go with
   the keys, and they have to go *first*, or the closure that works out what is
   still needed follows them straight back to everything.

   FC/IS: pure. Nothing here starts, stops or reads anything."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [integrant.core :as ig]))

;; =============================================================================
;; Reading the catalogue
;; =============================================================================

(defn catalogue-problem
  "Why `catalogue` is malformed, or nil.

   Shape only. It is tempting to also reject a catalogue naming keys the
   configuration does not build — that would catch a module renamed out from
   under it — but optional modules make it the normal case: workflow, search
   and payments are gated by `:enabled?`, so a config that has them off builds
   none of their keys and a correct catalogue still names them all.

   The same mistake is caught at the moment it matters instead. Asking for a
   service this configuration builds nothing for is what `selection-problem`
   refuses, and it can tell the two apart because it knows which service was
   asked for."
  [catalogue]
  (cond
    (not (map? catalogue))
    "Service catalogue must be a map of service name to definition"

    (empty? catalogue)
    "Service catalogue is empty, so no service can be selected"

    :else
    (let [malformed (remove (fn [[_ definition]]
                              (and (map? definition)
                                   (seq (:keys definition))
                                   (every? keyword? (:keys definition))))
                            catalogue)]
      (when (seq malformed)
        (str "Service catalogue entries need a non-empty :keys vector of "
             "Integrant keys; malformed: "
             (str/join ", " (sort (map (comp str key) malformed))))))))

(defn selection-problem
  "Why `service-names` cannot be run, or nil.

   Fails on a name the catalogue does not know, and on one whose keys this
   configuration does not build. The second is the quieter mistake: asking for
   a module that is disabled in config would otherwise start a process that
   serves nothing and reports itself healthy."
  [catalogue ig-config service-names]
  (let [names (set service-names)]
    (cond
      (empty? names)
      "No service named. Pass at least one, e.g. `service user`"

      :else
      (let [unknown (set/difference names (set (keys catalogue)))]
        (if (seq unknown)
          (str "Unknown service(s): " (str/join ", " (sort (map name unknown)))
               ". Known: " (str/join ", " (sort (map name (keys catalogue)))))
          (let [empty-services
                (remove (fn [service-name]
                          (some (set (keys ig-config))
                                (get-in catalogue [service-name :keys])))
                        (sort names))]
            (when (seq empty-services)
              (str "This configuration builds nothing for: "
                   (str/join ", " (map name empty-services))
                   ". The module is probably disabled in config."))))))))

;; =============================================================================
;; Partitioning
;; =============================================================================

(defn owned-keys
  "Every Integrant key the catalogue assigns to some service."
  [catalogue]
  (into #{} (mapcat :keys) (vals catalogue)))

(defn core-keys
  "Keys no service owns — the ones every service needs anyway.

   Derived rather than listed: the catalogue says what belongs to a module, and
   what is left over is the platform. A key nobody claimed is kept, so a
   catalogue that has fallen behind boots a service that is larger than
   necessary rather than one that is missing a component it needed."
  [catalogue ig-config]
  (set/difference (set (keys ig-config)) (owned-keys catalogue)))

(defn keys-for
  "The Integrant keys belonging to `service-names`, that this config builds."
  [catalogue ig-config service-names]
  (let [present (set (keys ig-config))]
    (into #{}
          (comp (mapcat #(get-in catalogue [% :keys]))
                (filter present))
          service-names)))

;; =============================================================================
;; Pruning
;; =============================================================================

(defn- refs-in
  "The Integrant keys `value` refers to, at any depth."
  [value]
  (into #{} (comp (filter ig/ref?) (map :key)) (tree-seq coll? seq value)))

(defn without-refs-to
  "`value` with every ref to a key in `gone` removed.

   A component that loses an input has to cope with its absence — which is the
   same thing it already does for a module disabled in config, since that
   produces exactly this shape. `http-handler` reads `(:web routes)` off a nil
   and mounts nothing, which is the behaviour the acceptance criterion asks
   for.

   Collections as well as map values. This dropped only map values until routes
   became a collection: `service user` then kept
   `:module-routes [#ref :wagoe/admin-routes ...]` pointing at keys the service
   had discarded, and Integrant refused to build a config with dangling refs
   (BOU-330)."
  [value gone]
  (letfn [(dangling? [v] (and (ig/ref? v) (contains? gone (:key v))))]
    (walk/postwalk
     (fn [node]
       (cond
         (and (map? node) (not (record? node)))
         (into (empty node) (remove (comp dangling? val)) node)

         ;; A vector or list of refs — how every module now contributes routes.
         ;; `map-entry?` first: postwalk hands entries as two-element vectors,
         ;; and rebuilding one as a plain vector makes the parent map's `into`
         ;; throw "Vector arg to map conj must be a pair".
         (and (vector? node) (not (map-entry? node)) (some dangling? node))
         (filterv (complement dangling?) node)

         (and (set? node) (some dangling? node))
         (into (empty node) (remove dangling?) node)

         (and (seq? node) (some dangling? node))
         (remove dangling? node)

         :else node))
     value)))

(defn- reachable
  "`roots` plus everything they refer to, transitively."
  [ig-config roots]
  (loop [seen #{} todo (set roots)]
    (if-let [k (first todo)]
      (recur (conj seen k)
             (into (disj todo k) (remove seen) (refs-in (get ig-config k))))
      seen)))

(defn service-config
  "`ig-config` reduced to what `service-names` need.

   Order matters and is the whole trick. Refs to unselected modules are removed
   *before* the closure is taken: `:wagoe/http-handler` refers to every
   module's routes and services, so following its refs first drags the entire
   system back in — computing the closure on the untouched config keeps 28 of
   33 keys and looks like it worked.

   Returns the reduced config. Validate with `selection-problem` first; this
   assumes the names are known."
  [ig-config catalogue service-names]
  (let [wanted    (keys-for catalogue ig-config service-names)
        discarded (set/difference (owned-keys catalogue) wanted)
        ;; Step 1: nothing points at a module we are not running.
        detached  (reduce-kv (fn [m k v] (assoc m k (without-refs-to v discarded)))
                             {}
                             (apply dissoc ig-config discarded))
        ;; Step 2: now the closure describes this service and not the old one.
        keeping   (reachable detached (set/union (core-keys catalogue ig-config) wanted))
        dropped   (set/difference (set (keys ig-config)) keeping)]
    ;; Step 3: a kept key may still refer to one that nothing reached. Belt and
    ;; braces — Integrant would refuse the config, and the message it gives
    ;; names the ref rather than the module the operator asked about.
    (reduce-kv (fn [m k v] (assoc m k (without-refs-to v dropped)))
               {}
               (select-keys detached keeping))))

(defn summary
  "What a service boot dropped, for the log line that says so.

   An operator reading `started successfully` needs to be able to tell a
   correctly slimmed service from one that quietly kept everything because the
   catalogue was stale."
  [ig-config service-config' service-names]
  {:services  (vec (sort (map name service-names)))
   :running   (count service-config')
   :available (count ig-config)
   :omitted   (vec (sort (map str (set/difference (set (keys ig-config))
                                                  (set (keys service-config'))))))})
