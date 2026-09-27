(ns wagoe.platform.shell.database.seed-hooks-test
  "`bb db:seed --system <app>.system-config` hands what it inserted to the
   application's seed hooks, so a seeded row with a workflow gets one
   (BOU-578). This namespace stands in for the application's system-config."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.platform.shell.database.cli-seed :as cli-seed]
            [wagoe.platform.shell.database.seed :as seed]))

(def ^:private seen (atom []))

(defn load-config [] {:active {}})

(defn ig-config
  [_config]
  {::hook      {:dep (ig/ref ::dep)}
   ::dep       {}
   ::unrelated {}})

(derive ::hook :wagoe/seed-hook)

(defmethod ig/init-key ::dep [_ _] :dep)
(defmethod ig/init-key ::hook [_ {:keys [dep]}]
  (fn [inserted] (swap! seen conj [:hook dep inserted])))
(defmethod ig/halt-key! ::hook [_ _] (swap! seen conj [:halted]))
(defmethod ig/init-key ::unrelated [_ _] (throw (ex-info "not a seed hook's dependency" {})))

(deftest ^:unit the-application-s-seed-hooks-get-the-inserted-rows
  (reset! seen [])
  (let [rows {"invoices" [{:id 1 :status "paid"}]}]
    ;; ::unrelated throws on init: only the hooks and what they need start.
    (is (= 1 (seed/run-seed-hooks! "wagoe.platform.shell.database.seed-hooks-test" rows)))
    (testing "each hook is started with its dependencies, called, and halted"
      (is (= [[:hook :dep rows] [:halted]] @seen)))))

(deftest ^:unit the-arguments-name-the-application-s-system
  (is (= {:force? false :path "resources/seeds/dev.edn" :system nil}
         (cli-seed/parse-args [])))
  (is (= {:force? true :path "x.edn" :system "shop.system-config"}
         (cli-seed/parse-args ["x.edn" "--system" "shop.system-config" "--force"]))))

(deftest ^:unit a-system-that-does-not-resolve-is-named
  ;; BOU-578: a wrong --system failed with an NPE and an empty message.
  (doseq [system-ns ["no.such.system-config" "clojure.string"]]
    (let [e (try (seed/run-seed-hooks! system-ns {}) nil (catch Exception e e))]
      (is (some? e) system-ns)
      (is (str/includes? (str (ex-message e)) system-ns) (str (ex-message e))))))
