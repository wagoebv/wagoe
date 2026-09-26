(ns wagoe.tools.quickstart-test
  (:require [wagoe.tools.config-edn :as config-edn]
            [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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
        (is (= :written (config-edn/inject-key! path ":wagoe/tasks"
                                                "\n  :wagoe/tasks\n  {:enabled? true}\n" {})))
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
        (is (= :written (config-edn/inject-key! path ":wagoe/tasks"
                                                "\n  :wagoe/tasks\n  {:enabled? true}\n" {})))
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
        (spit path "{:active\n {:wagoe/tasks {:enabled? true}}}\n")
        (is (= :already-present
               (config-edn/inject-key! path ":wagoe/tasks"
                                       "\n  :wagoe/tasks\n  {:enabled? true}\n" {})))
        ;; Running integrate twice must not duplicate the key — EDN keeps the
        ;; last one, so a second copy silently discards hand edits to the first.
        (is (= 1 (count (re-seq #":wagoe/tasks" (slurp path)))))
        (finally
          (.delete tmp)))))

  (testing "a missing file is reported, not silently treated as done"
    (is (= :no-file (config-edn/inject-key! "/nonexistent/config.edn" ":wagoe/tasks" "x" {}))))

  (testing "a config with no :active section is reported too"
    (let [tmp (java.io.File/createTempFile "config" ".edn")
          path (.getAbsolutePath tmp)]
      (try
        (spit path "{:some-key {:value 1}}")
        (is (= :no-active-section (config-edn/inject-key! path ":wagoe/tasks" "x" {})))
        (finally
          (.delete tmp)))))

  (testing "--dry-run reports what it would do and leaves the file alone"
    (let [tmp (java.io.File/createTempFile "config" ".edn")
          path (.getAbsolutePath tmp)]
      (try
        (spit path "{:active\n {:wagoe/settings {:name \"x\"}}}\n")
        (let [before (slurp path)]
          (is (= :written (config-edn/inject-key! path ":wagoe/tasks" "\n  :wagoe/tasks\n  {}\n"
                                                  {:dry-run? true})))
          (is (= before (slurp path)) "--dry-run must not write"))
        (finally
          (.delete tmp))))))

(deftest ^:unit inject-sample-module-config-writes-every-profile
  ;; Integrate writes prod too; when it failed, this fallback left prod without
  ;; :wagoe/tasks (BOU-529).
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "quickstart-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        conf #(io/file root "resources" "conf" % "config.edn")]
    (doseq [env ["dev" "test" "prod"]]
      (io/make-parents (conf env))
      (spit (conf env) "{:active\n {:wagoe/settings {}}\n :inactive {}}\n"))
    (is (true? (quickstart/inject-sample-module-config (str root))))
    (doseq [env ["dev" "test" "prod"]]
      (is (= :already-present (config-edn/key-status (slurp (conf env)) ":wagoe/tasks")) env))))

;; =============================================================================
;; The closing banner (BOU-545)
;; =============================================================================

(defn- run-quickstart
  "-main with every step stubbed. `failing` is the set of commands that fail,
   named by their second word (`scaffold`, `migrate`, ...)."
  [failing]
  (let [ran (atom [])
        out (with-out-str
              (with-redefs [process/shell (fn [_opts & cmd]
                                            (swap! ran conj (vec cmd))
                                            {:exit (if (failing (second cmd)) 1 0)})
                            quickstart/inject-sample-module-config (constantly true)]
                (quickstart/-main)))]
    {:out out :ran @ran}))

(deftest ^:unit a-failed-sample-module-is-reported-not-called-complete
  (testing "all steps pass: Complete"
    (let [{:keys [out]} (run-quickstart #{})]
      (is (str/includes? out "Quickstart Complete"))
      (is (not (str/includes? out "failed step")))))

  (testing "the scaffold step fails: named at the end, and the rest still runs"
    (let [{:keys [out ran]} (run-quickstart #{"scaffold"})]
      (is (str/includes? out "Completed with 1 failed step(s): [4/8] Scaffolding sample module"))
      (is (not (str/includes? out "ready to start!")))
      (is (some #(= ["bb" "migrate" "up"] %) ran) "migrations still run")
      (is (not (some #(= ["bb" "scaffold" "integrate" "tasks"] %) ran))
          "nothing to integrate"))))
