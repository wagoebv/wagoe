(ns wagoe.scaffolder.subscriber-test
  "`bb scaffold subscriber --module-name billing --event :admin/entity-created
   --entity invoices`: an Integrant component that subscribes to the event bus,
   a handler to fill in, its test, and the wiring that starts it when the bus
   is on. The rc-4 acceptance run wrote all of it by hand (BOU-578)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [integrant.core :as ig]
            [wagoe.events.core.event :as event]
            [wagoe.events.ports :as events]
            [wagoe.events.shell.module-wiring]
            [wagoe.scaffolder.cli :as cli]
            [wagoe.scaffolder.multi-entity-test :as multi]
            [wagoe.scaffolder.shell.service :as service]))

(def ^:private svc (service/create-scaffolder-service))
(def ^:private temp-dir #'multi/temp-dir)
(def ^:private files-under #'multi/files-under)
(def ^:private load-and-test! #'multi/load-and-test!)

(defn- scaffold! [dir base & args]
  (let [status (atom nil)
        out    (with-out-str
                 (binding [*err* *out*]
                   (reset! status (cli/run-cli! svc (into (vec args) ["--base-ns" base "--output-dir" (.getPath dir)])))))]
    {:status @status :out out}))

(defn- billing! [base & generate-args]
  (let [dir (temp-dir)]
    (apply scaffold! dir base "generate" "--module-name" "billing" "--entity" "Invoice" "--field" "number:string:required"
           generate-args)
    dir))

(defn- boot
  "The module's graph with `active` switched on, and an in-memory bus."
  [base active]
  (let [build (ns-resolve (symbol (str base ".billing.shell.module-wiring")) 'ig-config)
        graph (:components (build {:enabled? true} {:config {:active active}}))]
    (ig/init (merge (walk/postwalk-replace {(ig/ref :wagoe/db-context) :stand-in} graph)
                    {:wagoe/events {:provider :memory}}))))

(defn- admin-event [entity]
  (event/event {:id (random-uuid) :type :admin/entity-created :source :admin
                :published-at (java.time.Instant/now) :payload {:entity entity :id 1}}))

(deftest ^:integration a-subscriber-is-generated-wired-and-started
  (let [base "bou578s"
        dir  (billing! base)
        {:keys [status out]} (scaffold! dir base "subscriber" "--module-name" "billing"
                                   "--event" ":admin/entity-created" "--entity" "invoices")
        files (files-under dir)
        src   "src/bou578s/billing/shell/invoices_entity_created_subscriber.clj"]
    (is (= 0 status) out)
    (testing "the component, its handler and its test"
      (is (contains? files src))
      (is (contains? files "test/bou578s/billing/shell/invoices_entity_created_subscriber_test.clj"))
      (is (str/includes? (get files src) "(defn handle")))
    (testing "the next steps say how to switch the bus on"
      (is (str/includes? out "wagoe add events") out))
    (testing "it loads, and its test passes"
      (let [{:keys [fail error test]} (load-and-test! dir)]
        (is (pos? test))
        (is (= 0 fail))
        (is (= 0 error))))
    (let [sub-ns  (symbol "bou578s.billing.shell.invoices-entity-created-subscriber")
          k       :wagoe/billing-invoices-entity-created-subscriber
          handled (atom [])]
      (testing "with the event bus on it is started, and gets its events only"
        (with-redefs-fn {(ns-resolve sub-ns 'handle) #(swap! handled conj %)}
          (fn []
            (let [system (boot base {:wagoe/events {}})
                  bus    (:wagoe/events system)]
              (try
                (is (contains? system k))
                (events/publish! bus :admin (admin-event :payments))
                (events/publish! bus :admin (admin-event :invoices))
                (loop [n 50] (when (and (empty? @handled) (pos? n)) (Thread/sleep 20) (recur (dec n))))
                (Thread/sleep 100)
                (is (= [:invoices] (map #(get-in % [:payload :entity]) @handled)))
                (finally (ig/halt! system)))))))
      (testing "with the bus off it is not in the graph"
        (let [system (boot base {})]
          (try
            (is (not (contains? system k)))
            (finally (ig/halt! system))))))))

(deftest ^:unit a-subscriber-without-an-entity-takes-every-event-of-its-type
  (let [dir (billing! "bou578t")
        {:keys [status out]} (scaffold! dir "bou578t" "subscriber" "--module-name" "billing"
                                   "--event" "admin/entity-deleted")]
    (is (= 0 status) out)
    (let [src (get (files-under dir) "src/bou578t/billing/shell/entity_deleted_subscriber.clj")]
      (is (some? src))
      (is (str/includes? src "(events/subscribe! bus :admin"))
      (is (not (str/includes? src ":entity payload"))))))

(deftest ^:unit a-second-subscriber-joins-the-first-and-the-same-one-is-refused
  (let [dir (billing! "bou578v")
        add #(scaffold! dir "bou578v" "subscriber" "--module-name" "billing" "--event" ":admin/entity-created"
                   "--entity" %)]
    (is (= 0 (:status (add "invoices"))))
    (is (= 0 (:status (add "payments"))))
    (let [before (files-under dir)
          {:keys [status out]} (add "invoices")]
      (is (= 1 status))
      (is (str/includes? out "already") out)
      (is (= before (files-under dir))))))

(deftest ^:unit a-module-with-a-workflow-takes-one-too
  (let [dir (billing! "bou578w" "--workflow" "status:entered>paid")]
    (is (= 0 (:status (scaffold! dir "bou578w" "subscriber" "--module-name" "billing"
                            "--event" ":admin/entity-created" "--entity" "invoices"))))
    (is (str/includes? (slurp (io/file dir "src/bou578w/billing/shell/module_wiring.clj"))
                       "invoices-entity-created-subscriber"))))

(deftest ^:unit the-event-has-to-be-qualified
  (let [dir (billing! "bou578x")
        {:keys [status out]} (scaffold! dir "bou578x" "subscriber" "--module-name" "billing" "--event" "entity-created")]
    (is (= 1 status))
    (is (str/includes? out ":admin/entity-created") out)))

(deftest ^:unit subscriber-help-is-its-own
  (let [out (with-out-str (cli/run-cli! svc ["subscriber" "--help"]))]
    (is (= (str cli/subscriber-help "\n") out))
    (is (str/includes? cli/root-help "subscriber"))))
