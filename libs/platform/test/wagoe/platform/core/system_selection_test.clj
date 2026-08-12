(ns wagoe.platform.core.system-selection-test
  (:require [wagoe.platform.core.system-selection :as selection]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]))

(def ^:private config
  "A config shaped like the real one in the way that matters: a single
   `:handler` key referring to every module, which is what makes naive pruning
   keep everything."
  {:core/db      {}
   :core/logging {}
   :core/router  {}
   :core/handler {:router   (ig/ref :core/router)
                  :db       (ig/ref :core/db)
                  :user     (ig/ref :user/service)
                  :tenant   (ig/ref :tenant/service)
                  :billing  (ig/ref :billing/service)
                  :extra-mw (ig/ref :tenant/middleware)}
   :core/server  {:handler (ig/ref :core/handler)}

   :user/repo       {:db (ig/ref :core/db)}
   :user/service    {:repo (ig/ref :user/repo) :log (ig/ref :core/logging)}

   :tenant/repo       {:db (ig/ref :core/db)}
   :tenant/service    {:repo (ig/ref :tenant/repo)}
   :tenant/middleware {:svc (ig/ref :tenant/service)}

   :billing/service {:db (ig/ref :core/db)}})

(def ^:private catalogue
  {:user    {:keys [:user/repo :user/service]}
   :tenant  {:keys [:tenant/repo :tenant/service :tenant/middleware]}
   :billing {:keys [:billing/service]}})

(defn- dangling-refs
  "Refs pointing at keys the config does not define. Integrant refuses these."
  [cfg]
  (let [present (set (keys cfg))]
    (->> cfg
         vals
         (mapcat #(tree-seq coll? seq %))
         (filter ig/ref?)
         (map :key)
         (remove present)
         set)))

(deftest ^:unit a-service-runs-its-own-modules-and-the-platform
  (let [result (selection/service-config config catalogue #{:user})]

    (testing "the selected module is there"
      (is (contains? result :user/service))
      (is (contains? result :user/repo)))

    (testing "so is everything no service claimed"
      (is (contains? result :core/db))
      (is (contains? result :core/handler))
      (is (contains? result :core/server)))

    (testing "and the modules that were not asked for are gone"
      (is (not (contains? result :tenant/service)))
      (is (not (contains? result :tenant/repo)))
      (is (not (contains? result :tenant/middleware)))
      (is (not (contains? result :billing/service))))))

(deftest ^:unit the-closure-is-taken-after-detaching-not-before
  ;; The trap this whole namespace exists to avoid. `:core/handler` refers to
  ;; every module, so following refs on the untouched config reaches all of
  ;; them and the result is the system you started with — which still boots,
  ;; still passes a health check, and looks like it worked.
  (let [result (selection/service-config config catalogue #{:user})]
    (is (< (count result) (count config))
        "a selection that keeps everything is the bug, not a degenerate case")
    (is (= #{:core/db :core/logging :core/router :core/handler :core/server
             :user/repo :user/service}
           (set (keys result))))))

(deftest ^:unit nothing-is-left-pointing-at-a-key-that-went
  ;; Integrant refuses a config with a dangling ref, so this is the difference
  ;; between a service that starts and one that dies at boot.
  (testing "for a single service"
    (is (empty? (dangling-refs (selection/service-config config catalogue #{:user})))))

  (testing "for several"
    (is (empty? (dangling-refs (selection/service-config config catalogue #{:user :billing})))))

  (testing "and for every single-service selection the catalogue allows"
    ;; Cheap exhaustiveness: a ref missed in one module's shape would otherwise
    ;; wait for that module to be deployed alone.
    (doseq [service (keys catalogue)]
      (is (empty? (dangling-refs (selection/service-config config catalogue #{service})))
          (str "dangling ref when running " service)))))

(deftest ^:unit a-component-keeps-the-inputs-it-still-has
  (let [handler (:core/handler (selection/service-config config catalogue #{:user}))]

    (testing "refs to dropped modules are removed"
      (is (not (contains? handler :tenant)))
      (is (not (contains? handler :billing)))
      (is (not (contains? handler :extra-mw))))

    (testing "refs to the selected module survive"
      (is (= (ig/ref :user/service) (:user handler))))

    (testing "and so do refs to the platform"
      (is (= (ig/ref :core/router) (:router handler)))
      (is (= (ig/ref :core/db) (:db handler))))))

(deftest ^:unit several-services-can-share-a-process
  ;; The web/worker split and per-service deployment are the same mechanism
  ;; with different arguments.
  (let [result (selection/service-config config catalogue #{:user :tenant})]
    (is (contains? result :user/service))
    (is (contains? result :tenant/service))
    (is (contains? result :tenant/middleware))
    (is (not (contains? result :billing/service)))

    (testing "and a shared component keeps both refs"
      (let [handler (:core/handler result)]
        (is (contains? handler :user))
        (is (contains? handler :tenant))
        (is (not (contains? handler :billing)))))))

(deftest ^:unit selecting-everything-changes-nothing
  ;; A useful invariant: running all services must be the system as configured,
  ;; or the pruning is dropping something it should not.
  (is (= config (selection/service-config config catalogue (set (keys catalogue))))))

(deftest ^:unit a-key-nobody-claimed-is-treated-as-platform
  ;; The failure direction that matters. A catalogue that has fallen behind
  ;; should boot a service that is bigger than it needs to be, not one missing
  ;; a component it depended on.
  (let [stale (dissoc catalogue :billing)
        result (selection/service-config config stale #{:user})]
    (is (contains? result :billing/service)
        "unclaimed, so kept — wasteful, but it starts")))

(deftest ^:unit unknown-and-empty-selections-are-refused
  (testing "a name the catalogue does not know"
    (let [problem (selection/selection-problem catalogue config #{:reporting})]
      (is (re-find #"Unknown service" problem))
      (is (re-find #"billing, tenant, user" problem)
          "and says what it could have been asked for")))

  (testing "no name at all"
    (is (re-find #"No service named" (selection/selection-problem catalogue config #{}))))

  (testing "a service this configuration builds nothing for"
    ;; Asking for a module that is disabled in config would otherwise start a
    ;; process serving nothing, which reports itself healthy.
    (let [problem (selection/selection-problem catalogue (dissoc config :billing/service)
                                               #{:billing})]
      (is (re-find #"builds nothing for" problem))
      (is (re-find #"disabled in config" problem))))

  (testing "and a valid selection has no problem"
    (is (nil? (selection/selection-problem catalogue config #{:user})))
    (is (nil? (selection/selection-problem catalogue config #{:user :tenant})))))

(deftest ^:unit a-malformed-catalogue-is-reported
  (testing "an empty or non-map catalogue"
    (is (some? (selection/catalogue-problem {})))
    (is (some? (selection/catalogue-problem [:not :a :map]))))

  (testing "an entry with no keys, which would select nothing"
    (is (re-find #"non-empty :keys"
                 (selection/catalogue-problem (assoc catalogue :ghost {:keys []})))))

  (testing "an entry whose keys are not Integrant keys"
    (is (some? (selection/catalogue-problem (assoc catalogue :odd {:keys ["a-string"]})))))

  (testing "a valid catalogue is fine"
    (is (nil? (selection/catalogue-problem catalogue))))

  (testing "and one naming keys this config does not build is NOT an error"
    ;; Optional modules make that the normal case: a module gated off in config
    ;; builds none of its keys, and the catalogue still names them correctly.
    ;; Asking to *run* such a service is what gets refused, by
    ;; `selection-problem`, which knows which one was asked for.
    (is (nil? (selection/catalogue-problem
               (assoc catalogue :reporting {:keys [:reporting/service]}))))
    (is (some? (selection/selection-problem
                (assoc catalogue :reporting {:keys [:reporting/service]})
                config #{:reporting})))))

(deftest ^:unit the-summary-says-what-was-left-out
  ;; `started successfully` on a service that quietly kept everything is the
  ;; thing an operator cannot otherwise notice.
  (let [result  (selection/service-config config catalogue #{:user})
        summary (selection/summary config result #{:user})]
    (is (= ["user"] (:services summary)))
    (is (= 7 (:running summary)))
    (is (= 11 (:available summary)))
    (is (some #{":billing/service"} (:omitted summary)))
    (is (some #{":tenant/service"} (:omitted summary)))))
