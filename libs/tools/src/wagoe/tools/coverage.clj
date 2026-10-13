(ns wagoe.tools.coverage
  "`bb coverage`: Cloverage through kaocha's :unit suite, measuring the code
   tests.edn names, so no second list of paths can fall behind it (BOU-620)."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn src-paths
  "What to measure: tests.edn's :kaocha/source-paths, plus the src next to each
   :unit test path that has one (libs/config is on neither list)."
  [tests-edn dir?]
  (let [unit (first (filter #(= :unit (:id %)) (:tests tests-edn)))]
    (distinct
     (concat (:kaocha/source-paths tests-edn)
             (keep #(let [src (str/replace % #"/test$" "/src")]
                      (when (and (not= src %) (dir? src)) src))
                   (:test-paths unit))))))

(defn command
  "The clojure invocation for `src` paths; the Mac PostgreSQL binary on a Mac."
  [src mac?]
  (concat ["clojure" (str "-M:test:db:test/pg:test/otel:test/http" (when mac? ":test/pg-mac") ":coverage")
           ":unit" "--plugin" "cloverage" "--no-cov-html" "--cov-text"]
          (mapcat (fn [p] ["--cov-src-ns-path" p]) src)))

(defn -main [& _]
  (let [tests (edn/read-string {:readers {'kaocha/v1 identity}} (slurp "tests.edn"))
        cmd   (command (src-paths tests fs/directory?)
                       (= "aarch64" (System/getProperty "os.arch")))]
    (System/exit (:exit (apply process/shell {:continue true} cmd)))))
