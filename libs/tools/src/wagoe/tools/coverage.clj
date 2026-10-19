(ns wagoe.tools.coverage
  "`bb coverage`: Cloverage through every kaocha suite, measuring the code
   tests.edn names, so no second list of paths can fall behind it (BOU-620)."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn src-paths
  "What to measure: tests.edn's :kaocha/source-paths, plus the src next to each
   suite's test path that has one."
  [tests-edn dir?]
  (distinct
   (concat (:kaocha/source-paths tests-edn)
           (keep #(let [src (str/replace % #"/test$" "/src")]
                    (when (and (not= src %) (dir? src)) src))
                 (mapcat :test-paths (:tests tests-edn))))))

(defn command
  "The clojure invocation for `src` paths. :test/all brings what every suite
   needs, on any platform, so no alias list is kept here."
  [src]
  (concat ["clojure" "-M:test:test/all:coverage" "--plugin" "cloverage" "--no-cov-html" "--cov-text"]
          (mapcat (fn [p] ["--cov-src-ns-path" p]) src)))

(defn -main [& _]
  (let [tests (edn/read-string {:default (fn [_ v] v)} (slurp "tests.edn"))
        cmd   (command (src-paths tests fs/directory?))]
    (System/exit (:exit (apply process/shell {:continue true} cmd)))))
