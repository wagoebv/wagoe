(ns wagoe.scaffolder.generated-service-test
  "The service `bb scaffold generate` writes, actually calling a repository.

   The generated service reached its repository by Java interop —
   `(.find-by-id repository id)`. The port is a protocol, and the way through
   a port is its protocol function: interop asks the reflector for a method on
   whatever `repository` happens to be, so every call is reflective and
   nothing links the service to the port it claims to use (BOU-478).

   Two things are asserted, because only the second one fails on text. The
   calls have to reach the repository, and compiling the service has to emit
   no reflection warning — which the interop version cannot do, since the
   binding it calls through is an untyped `Object`."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.scaffolder.core.generators :as gen]
            [wagoe.scaffolder.core.template :as template]))

(def ^:private ctx
  (template/build-module-context
   {:module-name "widget"
    :base-ns     "wagoe"
    :entities    [{:name   "Widget"
                   :fields [{:name :label :type :string}]}]}))

(defn- eval-source!
  "Evaluate a generated source string, form by form, as the reader sees it."
  [source]
  (binding [*ns* *ns*]
    (doseq [form (read-string (str "[" source "]"))]
      (eval form))))

(def ^:private stub-repository-source
  "A repository shaped like the generated one: a record implementing the
   repository port inline, recording what it was asked for."
  "(ns wagoe.widget.test-repository
     (:require [wagoe.widget.ports :as ports]))

   (defrecord StubWidgetRepository [calls]
     ports/IWidgetRepository
     (find-by-id [_ id]
       (swap! calls conj [:find-by-id id])
       {:id id :label \"found\"})
     (find-all [_ options]
       (swap! calls conj [:find-all options])
       [{:id 1 :label \"listed\"}])
     (create [_ entity]
       (swap! calls conj [:create entity])
       entity)
     (update-entity [_ entity]
       (swap! calls conj [:update-entity entity])
       entity)
     (delete [_ id]
       (swap! calls conj [:delete id])
       true))")

(defn- load-generated-module!
  "Load schema, ports, core and service for the generated module, plus a stub
   repository, and return the service and the call log."
  []
  (doseq [generate [gen/generate-schema-file
                    gen/generate-ports-file
                    gen/generate-core-file
                    gen/generate-service-file]]
    (eval-source! (generate ctx)))
  (eval-source! stub-repository-source)
  (let [calls (atom [])
        repo  ((ns-resolve 'wagoe.widget.test-repository '->StubWidgetRepository) calls)]
    {:service ((ns-resolve 'wagoe.widget.shell.service 'create-service) repo)
     :calls   calls}))

(defn- port-fn
  "The protocol function `sym` from the generated ports namespace."
  [sym]
  (ns-resolve 'wagoe.widget.ports sym))

(deftest ^:unit the-generated-service-reaches-its-repository
  (let [{:keys [service calls]} (load-generated-module!)]

    (testing "a read goes through"
      (let [found ((port-fn 'get-widget) service 42)]
        (is (= {:id 42 :label "found"} found))
        (is (= [[:find-by-id 42]] @calls))))

    (testing "a list goes through"
      (reset! calls [])
      (is (= [{:id 1 :label "listed"}] ((port-fn 'list-widgets) service {:limit 10})))
      (is (= [[:find-all {:limit 10}]] @calls)))

    (testing "a create goes through, with the core-prepared entity"
      (reset! calls [])
      (let [created ((port-fn 'create-widget) service {:label "new"})]
        (is (= "new" (:label created)))
        (is (uuid? (:id created)) "the shell supplies the id")
        (is (inst? (:created-at created)))
        (is (= [:create] (mapv first @calls)))))

    (testing "an update goes through, carrying the id"
      (reset! calls [])
      ((port-fn 'update-widget) service 7 {:label "changed"})
      (is (= [[:update-entity {:label "changed" :id 7}]] @calls)))

    (testing "a delete goes through"
      (reset! calls [])
      (is (true? ((port-fn 'delete-widget) service 7)))
      (is (= [[:delete 7]] @calls)))))

(deftest ^:unit the-generated-service-compiles-without-reflection
  ;; The check the behaviour test cannot make. `(.find-by-id repository id)`
  ;; resolves at runtime — the reflector munges the dash back — so calling it
  ;; works and says nothing. What it costs is a reflective call on every
  ;; repository access, which `*warn-on-reflection*` names and a project
  ;; compiling with warnings as errors refuses.
  (doseq [generate [gen/generate-schema-file
                    gen/generate-ports-file
                    gen/generate-core-file]]
    (eval-source! (generate ctx)))
  (let [warnings (with-out-str
                   (binding [*warn-on-reflection* true
                             *err* *out*]
                     (eval-source! (gen/generate-service-file ctx))))]
    (is (= "" warnings)
        (str "the generated service reaches its repository reflectively:\n" warnings))))
