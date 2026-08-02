(ns wagoe.tools.quickstart-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.tools.quickstart :as quickstart]))

;; =============================================================================
;; Preset resolution
;; =============================================================================

(deftest ^:unit presets-contain-all-database-options
  (testing "all supported databases are represented"
    (let [db-values (set (map :database (vals quickstart/presets)))]
      (is (contains? db-values "h2"))
      (is (contains? db-values "postgresql"))
      (is (contains? db-values "sqlite"))
      (is (contains? db-values "mysql")))))

(deftest ^:unit presets-have-required-fields
  (testing "every preset has :database and :description"
    (doseq [[name preset] quickstart/presets]
      (is (string? (:database preset)) (str "preset " name " missing :database"))
      (is (string? (:description preset)) (str "preset " name " missing :description")))))

(deftest ^:unit resolve-preset-returns-preset-map
  (testing "known presets resolve to maps"
    ;; Assert the database, not the description. This line used to pin the
    ;; exact copy ("H2 in-memory, no extras"), so correcting that description
    ;; when BOU-265 made dev H2 file-backed failed a test that had nothing to
    ;; do with the change. The three lines below it only ever checked
    ;; :database; presets-have-required-fields already covers the description
    ;; being present.
    (is (= "h2" (:database (#'quickstart/resolve-preset "minimal"))))
    (is (= "postgresql" (:database (#'quickstart/resolve-preset "standard"))))
    (is (= "sqlite" (:database (#'quickstart/resolve-preset "sqlite"))))
    (is (= "mysql" (:database (#'quickstart/resolve-preset "mysql")))))

  (testing "unknown preset returns nil"
    (is (nil? (#'quickstart/resolve-preset "banana")))
    (is (nil? (#'quickstart/resolve-preset "")))))

;; =============================================================================
;; Config injection
;; =============================================================================

(deftest ^:unit inject-module-config-test
  (testing "injects :wagoe/tasks into single-module :active section"
    (let [tmp (java.io.File/createTempFile "config" ".edn")
          path (.getAbsolutePath tmp)]
      (try
        (spit path (str "{:active\n"
                        " {:wagoe/settings {:name \"test\"}}\n"
                        "\n"
                        " :inactive\n"
                        " {:wagoe/cache {:provider :redis}}}\n"))
        (is (true? (#'quickstart/inject-module-config path)))
        (let [result (slurp path)]
          (is (re-find #":wagoe/tasks" result)
              "config should contain :wagoe/tasks after injection")
          (is (re-find #":enabled\? true" result)
              "config should contain :enabled? true"))
        (finally
          (.delete tmp)))))

  (testing "injects correctly into multi-module :active section"
    (let [tmp (java.io.File/createTempFile "config" ".edn")
          path (.getAbsolutePath tmp)]
      (try
        ;; Realistic config: multiple modules with nested maps
        (spit path (str "{:active\n"
                        " {:wagoe/settings {:name \"test\"}\n"
                        "  :wagoe/http {:port 3000 :host \"0.0.0.0\"}\n"
                        "  :wagoe/admin {:enabled? true\n"
                        "                   :base-path \"/web/admin\"}}\n"
                        "\n"
                        " :inactive\n"
                        " {:wagoe/cache {:provider :redis}}}\n"))
        (is (true? (#'quickstart/inject-module-config path)))
        (let [result (slurp path)]
          (is (re-find #":wagoe/tasks" result)
              "config should contain :wagoe/tasks")
          ;; The snippet must NOT be inside another module's value map
          (is (not (re-find #":wagoe/admin \{[^}]*:wagoe/tasks" result))
              "tasks must not be nested inside admin config"))
        (finally
          (.delete tmp)))))

  (testing "skips injection when :wagoe/tasks already present"
    (let [tmp (java.io.File/createTempFile "config" ".edn")
          path (.getAbsolutePath tmp)]
      (try
        (spit path ":wagoe/tasks {:enabled? true}\n:inactive {}")
        (is (true? (#'quickstart/inject-module-config path)))
        ;; Should not duplicate
        (is (= 1 (count (re-seq #":wagoe/tasks" (slurp path)))))
        (finally
          (.delete tmp)))))

  (testing "returns nil for non-existent file"
    (is (nil? (#'quickstart/inject-module-config "/nonexistent/config.edn"))))

  (testing "returns false when no :active section found"
    (let [tmp (java.io.File/createTempFile "config" ".edn")
          path (.getAbsolutePath tmp)]
      (try
        (spit path "{:some-key {:value 1}}")
        (is (false? (#'quickstart/inject-module-config path)))
        (finally
          (.delete tmp))))))
