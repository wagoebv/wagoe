(ns wagoe.observability.logging.shell.adapters.stdout-integration-test
  "Integration tests for stdout logging adapter wired via Integrant system wiring."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [integrant.core :as ig]
            [wagoe.observability.logging.ports :as ports]
            ;; Ensure Integrant init-key/ halt-key! for :boundary/logging are loaded
            [wagoe.platform.shell.system.wiring]))

(deftest ^:integration stdout-logging-component-end-to-end
  (testing "stdout logging component is initialized via Integrant and emits log output"
    (let [ig-config {:boundary/logging {:provider :stdout
                                        :level :info
                                        :format :text
                                        :include-timestamp false
                                        :include-level true
                                        :include-thread false
                                        :colors false
                                        :default-tags {:service "stdout-integration-test"}}}
          system (ig/init ig-config)]
      (try
        (let [logger (:boundary/logging system)
              out    (with-out-str
                       (ports/info logger "integration test message" {:integration true}))
              lines  (->> (str/split-lines out)
                          (remove str/blank?))
              line   (first lines)]
          (is (satisfies? ports/ILogger logger) "Component should implement ILogger")
          (is (some? line) "Log line should be produced")
          (is (str/includes? line "integration test message"))
          (is (str/includes? line "INFO") "Level should be included in output")
          (is (or (str/includes? line "integration=true")
                  (str/includes? line "integration true"))
              "Context key should be rendered in output"))
        (finally
          (ig/halt! system))))))
