(ns wagoe.cli.list-modules
  (:require [wagoe.cli.catalogue :as cat]
            [cheshire.core :as json]))

(defn- pad [s width]
  (let [s (str s)]
    (if (>= (count s) width)
      (subs s 0 width)
      (str s (apply str (repeat (- width (count s)) " "))))))

(defn print-table []
  (let [modules (cat/optional-modules)
        fmt     "  %-12s  %-50s  %s"]
    (println)
    (println (format fmt "Module" "Description" "Command"))
    (println (format fmt (apply str (repeat 12 "-"))
                     (apply str (repeat 50 "-"))
                     (apply str (repeat 28 "-"))))
    (doseq [{:keys [name description add-command]} modules]
      (println (format fmt (pad name 12) (pad description 50) add-command)))
    (println)))

;; print-json includes all modules (core + optional) so AI tools can discover
;; everything installed by default, not just what can be added.
(defn print-json []
  (let [catalogue (cat/load-catalogue)
        modules   (map (fn [m]
                         {:name             (:name m)
                          :description      (:description m)
                          :clojars          (str (:clojars m))
                          :version          (:version m)
                          :category         (name (:category m))
                          :add-command      (:add-command m)
                          :docs-url         (:docs-url m)})
                       (:modules catalogue))]
    (println (json/generate-string
              {:cli-version       (:cli-version catalogue)
               :catalogue-version (:catalogue-version catalogue)
               :modules           modules}
              {:pretty true}))))

(defn -main [args]
  (let [[subcmd & rest-args] args]
    (when-not (= "modules" subcmd)
      (println (str "Unknown list subcommand: " (or subcmd "<none>")))
      (println "Usage: boundary list modules [--json]")
      (System/exit 1))
    (if (some #(= "--json" %) rest-args)
      (print-json)
      (print-table))))
